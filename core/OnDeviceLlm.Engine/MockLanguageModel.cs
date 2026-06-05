using System.Runtime.CompilerServices;

namespace OnDeviceLlm.Engine;

/// <summary>
/// A fully offline, deterministic stand-in for a real model. Emits a canned response
/// word-by-word at a configurable rate so the entire streaming path — channel,
/// marshalling, UI thread hop — can be exercised without model weights or a network.
/// Replace with a llama.cpp-backed <see cref="ILanguageModel"/> and nothing else changes.
/// </summary>
public sealed class MockLanguageModel(TimeSpan? perTokenDelay = null) : ILanguageModel
{
    private readonly TimeSpan _delay = perTokenDelay ?? TimeSpan.FromMilliseconds(30);

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var words = BuildResponse(request.Prompt);
        var limit = Math.Min(words.Length, request.MaxTokens);

        for (var i = 0; i < limit; i++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await Task.Delay(_delay, cancellationToken).ConfigureAwait(false);

            // Leading space on every fragment but the first reproduces real subword streaming.
            yield return i == 0 ? words[i] : " " + words[i];
        }
    }

    private static string[] BuildResponse(string prompt)
    {
        var trimmed = prompt.Trim();
        var echo = trimmed.Length == 0 ? "(empty prompt)" : trimmed;

        return ($"You asked: \"{echo}\". Running fully on-device, this reply is produced by the " +
                "OnDeviceLlm .NET 10 NativeAOT engine and streamed token-by-token across the interop " +
                "boundary you selected.").Split(' ');
    }
}
