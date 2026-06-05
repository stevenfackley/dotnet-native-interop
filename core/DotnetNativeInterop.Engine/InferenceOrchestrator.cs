using System.Runtime.CompilerServices;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Turns a model's raw text stream into an indexed <see cref="InferenceToken"/> stream
/// terminated by a final marker. Pure and transport-agnostic — the single code path
/// shared by every built interop layer (FFI, raw-HTTP, SQLite).
/// </summary>
public sealed class InferenceOrchestrator(ILanguageModel model)
{
    /// <summary>Runs <paramref name="request"/> and yields indexed tokens, then a final marker.</summary>
    public async IAsyncEnumerable<InferenceToken> RunAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        var index = 0;
        await foreach (var fragment in model.GenerateAsync(request, cancellationToken).ConfigureAwait(false))
        {
            yield return new InferenceToken(index++, fragment, IsFinal: false);
        }

        yield return InferenceToken.Final(index);
    }
}
