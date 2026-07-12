using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Threading.Channels;

namespace DotnetNativeInterop.Engine.Ai.Agent;

/// <summary>
/// Adapts <see cref="ForemanAgent"/> — a tool-calling turn loop with a callback-based answer
/// stream (<c>Action&lt;string&gt;</c>) and a <see cref="Task{ForemanTurnResult}"/> outcome — to the
/// plain <see cref="ILanguageModel"/> contract, so a Foreman turn can ride the EXACT SAME
/// <see cref="InferenceOrchestrator"/> / <c>InferenceSession</c> / <c>SessionRegistry</c> plumbing
/// <c>dni_rag_session_start</c> already uses: same session type, same cancel/free lifecycle, same
/// <c>dni_token_cb</c> callback ABI. No changes to that shared plumbing were needed or made.
///
/// Completion contract (documented alongside <c>dni_agent_session_start</c> in <c>abi/dni.h</c>):
/// every fragment this model yields is real streamed answer text EXCEPT the very last one, which is
/// always a single status fragment starting with the <see cref="StatusMarker"/> control byte
/// (<c>0x01</c>) and followed by the turn's <see cref="AgentSessionStatus"/> as JSON.
/// <see cref="InferenceOrchestrator.RunAsync"/> then appends its own always-empty terminal marker
/// (<c>is_final=1</c>) after that, unchanged. A client MUST inspect the status fragment before treating
/// a turn as a clean answer — a <see cref="ForemanStopReason.StepCapReached"/> or
/// <see cref="ForemanStopReason.Error"/> turn must never be presented as if it were
/// <see cref="ForemanStopReason.Answered"/>.
/// </summary>
public sealed class ForemanLanguageModel(ForemanAgent agent) : ILanguageModel
{
    /// <summary>
    /// Prefixes the final status fragment. Its FIRST character is U+0001 (SOH, the control byte
    /// <c>0x01</c>) — a byte no real UTF-8 answer prose from EITHER brain ever produces (the router
    /// streams manual prose; the on-device LLM streams natural-language tokens; neither emits C0
    /// control bytes), so detecting the status fragment is structurally collision-proof: a client keys
    /// on the fragment's leading <c>0x01</c> byte. Matching the readable <c>"dni.agent.status"</c> tag
    /// ALONE is NOT safe — an LLM answering a question ABOUT the marker could stream that literal text;
    /// only the leading control byte is collision-proof. The trailing readable tag is kept purely for
    /// log/greppability after the control byte. SOH is NUL-free, so the whole marker round-trips through
    /// a native NUL-terminated <c>const char*</c> intact (unlike an embedded NUL, which such a reader
    /// would silently truncate at).
    /// </summary>
    public const string StatusMarker = "\u0001dni.agent.status";

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        // Bridges ForemanAgent's push-based sink (Action<string>, invoked synchronously as the brain
        // streams its answer) to this method's pull-based IAsyncEnumerable<string> contract.
        var channel = Channel.CreateUnbounded<string>(new UnboundedChannelOptions
        {
            SingleReader = true,
            SingleWriter = true,
        });

        // Runs the turn on its own task so this iterator can concurrently drain the channel as
        // fragments arrive (rather than buffering the whole answer before yielding anything).
        var runTask = Task.Run(async () =>
        {
            // The whole run body sits in a try/finally that ALWAYS completes the channel — otherwise a
            // throw between RunTurnAsync returning and TryComplete (realistically only the status
            // JsonSerializer.Serialize) would leave the reader hanging forever with no is_final.
            try
            {
                var result = await agent.RunTurnAsync(
                    request.Prompt,
                    fragment => channel.Writer.TryWrite(fragment),
                    cancellationToken).ConfigureAwait(false);

                // ForemanAgent.RunTurnAsync contains every non-cancellation fault internally and always
                // returns a ForemanTurnResult (Answered/StepCapReached/Error) rather than throwing — so
                // this line is reached on any non-cancelled completion, cap, or contained error.
                var status = new AgentSessionStatus(result.StopReason, result.ToolSteps, agent.BackendBadge);
                var statusJson = JsonSerializer.Serialize(status, ForemanJsonContext.Default.AgentSessionStatus);
                channel.Writer.TryWrite(StatusMarker + statusJson);
            }
            finally
            {
                channel.Writer.TryComplete();
            }
        }, cancellationToken);

        try
        {
            await foreach (var fragment in channel.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
            {
                yield return fragment;
            }
        }
        finally
        {
            // Always observe runTask so a cancelled/faulted turn never becomes an unobserved-exception
            // crash later; OperationCanceledException here is the expected shape of a cancelled turn.
            try
            {
                await runTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Expected on cancel (mirrors InferenceSession.PumpAsync's own OCE handling).
            }
        }
    }
}
