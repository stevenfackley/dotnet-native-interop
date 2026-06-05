namespace OnDeviceLlm.Engine;

/// <summary>
/// Renders a deterministic Mandelbrot set to a grayscale pixel buffer entirely in managed code, so a
/// native UI can prove that .NET computed every pixel in-process (no GPU, no cloud, no JIT). The output
/// is fixed for a given viewport + iteration budget, which lets the showcase self-check it like any
/// other feature.
/// </summary>
public static class FractalRenderer
{
    /// <summary>Side length (the image is square) of the rendered buffer, in pixels.</summary>
    public const int Size = 128;

    private const int MaxIterations = 100;

    /// <summary>
    /// Computes the Mandelbrot escape-iteration count for every pixel and maps it to one grayscale
    /// byte (row-major, 8-bit). Points inside the set are black; escaping points get brighter the
    /// faster they escape.
    /// </summary>
    public static byte[] Render()
    {
        var pixels = new byte[Size * Size];
        const double minX = -2.0, maxX = 1.0, minY = -1.5, maxY = 1.5;

        for (var py = 0; py < Size; py++)
        {
            var y0 = minY + ((maxY - minY) * py / (Size - 1));
            for (var px = 0; px < Size; px++)
            {
                var x0 = minX + ((maxX - minX) * px / (Size - 1));
                double x = 0, y = 0;
                var iteration = 0;
                while ((x * x) + (y * y) <= 4.0 && iteration < MaxIterations)
                {
                    var xTemp = (x * x) - (y * y) + x0;
                    y = (2 * x * y) + y0;
                    x = xTemp;
                    iteration++;
                }

                pixels[(py * Size) + px] = iteration >= MaxIterations
                    ? (byte)0
                    : (byte)(255 - (iteration * 255 / MaxIterations));
            }
        }

        return pixels;
    }

    /// <summary>
    /// Renders the fractal and packs it as <c>"{Size}x{Size}:{base64}"</c> — a compact, deterministic
    /// string the native side splits on <c>':'</c> and decodes into an 8-bit grayscale image.
    /// </summary>
    public static string RenderBase64() => $"{Size}x{Size}:{Convert.ToBase64String(Render())}";
}
