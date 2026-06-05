namespace OnDeviceLlm.Engine;

/// <summary>A single inference request: the prompt plus decoding parameters.</summary>
/// <param name="Prompt">The user prompt to complete.</param>
/// <param name="MaxTokens">Hard cap on the number of generated tokens.</param>
/// <param name="Temperature">Sampling temperature; 0 = greedy/deterministic.</param>
public sealed record InferenceRequest(string Prompt, int MaxTokens = 256, float Temperature = 0.8f);
