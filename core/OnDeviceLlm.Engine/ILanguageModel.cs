namespace OnDeviceLlm.Engine;

/// <summary>
/// An on-device language model. <see cref="MockLanguageModel"/> ships in this repo so the
/// full streaming path can be exercised with no weights or network; a real backend
/// (e.g. llama.cpp via P/Invoke) implements this same contract with no other changes.
/// </summary>
public interface ILanguageModel
{
    /// <summary>
    /// Streams generated text fragments for <paramref name="request"/> until the model
    /// completes or <paramref name="cancellationToken"/> fires.
    /// </summary>
    IAsyncEnumerable<string> GenerateAsync(InferenceRequest request, CancellationToken cancellationToken = default);
}
