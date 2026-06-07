namespace DotnetNativeInterop.Engine;

/// <summary>
/// Process-wide ONNX Runtime configuration, set by the platform host before the first inference.
/// </summary>
public static class OnnxRuntimeConfig
{
    /// <summary>
    /// When true, ORT sessions append the NNAPI execution provider (CPU fallback stays enabled).
    /// The Android host sets this via <c>dni_set_assets_dir</c>; it stays false on iOS, whose ORT
    /// framework has no NNAPI symbol.
    /// </summary>
    public static bool UseNnapi { get; set; }
}
