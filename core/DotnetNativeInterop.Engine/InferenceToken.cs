namespace DotnetNativeInterop.Engine;

/// <summary>
/// One streamed unit of model output. This is the value that crosses every interop
/// boundary (FFI callback, HTTP/SSE, gRPC message, SQLite row) unchanged.
/// </summary>
/// <param name="Index">Zero-based position in the stream.</param>
/// <param name="Text">Token text (marshalled as UTF-8 natively). Empty on the final marker.</param>
/// <param name="IsFinal">True for the terminal marker that closes the stream.</param>
public readonly record struct InferenceToken(int Index, string Text, bool IsFinal)
{
    /// <summary>Builds the terminal marker appended after the last real token.</summary>
    public static InferenceToken Final(int index) => new(index, string.Empty, IsFinal: true);
}
