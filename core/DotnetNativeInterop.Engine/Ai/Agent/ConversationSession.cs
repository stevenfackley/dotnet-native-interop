namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// One completed turn kept in <see cref="ConversationSession"/>'s bounded history: the user's query and
/// whatever text actually reached the answer sink for that turn (including a contained-error
/// placeholder like <c>"(agent error: ...)"</c> — history mirrors what the user actually saw, the same
/// honesty rule <see cref="ForemanStopReason"/> already applies to the status marker, not a "clean
/// answers only" view).
/// </summary>
public readonly record struct ConversationTurn(string Query, string Answer);

/// <summary>
/// Bounded, thread-safe memory of the last few completed turns in one Foreman conversation.
/// <see cref="ForemanAgent.RunTurnAsync"/> takes a <see cref="Snapshot"/> at the START of a turn (so the
/// brain sees prior turns as context via <see cref="AgentContext.History"/>) and calls <see cref="Append"/>
/// once at the END, whatever the turn's outcome.
///
/// Bounds (deliberately small — this rides either an on-device 1B-class model or a from-scratch MiniLM
/// router, neither of which has a large context/compute budget to spend on history):
///  - <see cref="MaxTurns"/> completed turns retained, oldest evicted first (FIFO). Old context is more
///    likely stale than helpful, and an unboundedly growing "one long-running app chat" would eventually
///    blow the grammar brain's prompt budget.
///  - <see cref="MaxQueryChars"/> / <see cref="MaxAnswerChars"/> per-turn clamps, so one pasted-essay
///    query or a runaway answer can't dominate the whole history budget by itself. Truncated text gets a
///    visible <c>"…(truncated)"</c> suffix so a consumer can tell it isn't verbatim.
///
/// Thread-safety: a single instance backs the process-wide "one Foreman chat" (see
/// <see cref="ForemanHost.ResetConversation"/>), and nothing on the FFI surface prevents a client from
/// starting a second turn before the first finishes — so two turns COULD run concurrently against the
/// same session. Every read/write here takes one lock, so that is at worst a benign ordering race
/// (which turn's Append lands first), never a torn list.
/// </summary>
public sealed class ConversationSession
{
    /// <summary>Max completed turns retained; the oldest is evicted once a new one exceeds this.</summary>
    public const int MaxTurns = 6;

    /// <summary>Max characters of a stored query; longer is truncated (see class remarks).</summary>
    public const int MaxQueryChars = 500;

    /// <summary>Max characters of a stored answer; longer is truncated (see class remarks).</summary>
    public const int MaxAnswerChars = 800;

    private const string TruncatedSuffix = "…(truncated)";

    private readonly object _gate = new();
    private readonly List<ConversationTurn> _turns = new(MaxTurns);

    /// <summary>A point-in-time copy of the retained turns, oldest-to-newest. Never null, never torn.</summary>
    public IReadOnlyList<ConversationTurn> Snapshot()
    {
        lock (_gate)
        {
            return _turns.ToArray();
        }
    }

    /// <summary>Appends a completed turn (clamped) and evicts the oldest beyond <see cref="MaxTurns"/>.</summary>
    public void Append(string query, string answer)
    {
        var turn = new ConversationTurn(Clamp(query, MaxQueryChars), Clamp(answer, MaxAnswerChars));
        lock (_gate)
        {
            _turns.Add(turn);
            while (_turns.Count > MaxTurns)
            {
                _turns.RemoveAt(0);
            }
        }
    }

    /// <summary>Clears all retained turns — the next turn starts a fresh conversation with no prior context.</summary>
    public void Reset()
    {
        lock (_gate)
        {
            _turns.Clear();
        }
    }

    private static string Clamp(string s, int max) =>
        s.Length <= max ? s : string.Concat(s.AsSpan(0, max), TruncatedSuffix);
}
