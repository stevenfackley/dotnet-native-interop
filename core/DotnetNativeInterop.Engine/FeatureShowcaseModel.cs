using System.Runtime.CompilerServices;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// Streams the <see cref="LanguageFeatureCatalog"/> as formatted text so every transport and the
/// native UI render the live results of C# / .NET language features running inside NativeAOT.
/// Implements <see cref="ILanguageModel"/> so the entire pipeline (orchestrator, channel session,
/// FFI/SQLite transports, streaming UI) is reused unchanged.
/// </summary>
public sealed class FeatureShowcaseModel(TimeSpan? perLineDelay = null) : ILanguageModel
{
    private readonly TimeSpan _delay = perLineDelay ?? TimeSpan.FromMilliseconds(14);

    /// <inheritdoc/>
    public async IAsyncEnumerable<string> GenerateAsync(
        InferenceRequest request,
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        _ = request;

        foreach (var feature in LanguageFeatureCatalog.Descriptors)
        {
            var block = $"▸ {feature.Title}  ({feature.Version})\n{feature.Code}\n=>  {feature.Expected}\n\n";

            foreach (var line in block.Split('\n'))
            {
                cancellationToken.ThrowIfCancellationRequested();
                await Task.Delay(_delay, cancellationToken).ConfigureAwait(false);
                yield return line + "\n";
            }
        }
    }
}
