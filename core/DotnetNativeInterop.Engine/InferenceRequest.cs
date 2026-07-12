namespace DotnetNativeInterop.Engine;

/// <summary>A single inference request: the prompt plus decoding parameters.</summary>
/// <param name="Prompt">The user prompt to complete.</param>
/// <param name="MaxTokens">Hard cap on the number of generated tokens.</param>
/// <param name="Temperature">Sampling temperature; 0 = greedy/deterministic.</param>
/// <param name="Grammar">
/// Optional llama.cpp GBNF grammar (root rule "root") that HARD-CONSTRAINS decoding: when set, a
/// grammar-capable backend (<see cref="Llama.LlamaLanguageModel"/>) masks every grammar-violating token
/// so the output cannot be malformed (Foreman's tool-call turn uses this). Backends without a grammar
/// sampler (Mock/Extractive/Rag) ignore it — it is a decode constraint, not a prompt.
/// </param>
public sealed record InferenceRequest(
    string Prompt, int MaxTokens = 256, float Temperature = 0.8f, string? Grammar = null);
