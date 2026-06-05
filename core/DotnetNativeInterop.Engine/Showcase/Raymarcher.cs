namespace DotnetNativeInterop.Engine;

/// <summary>
/// A tiny signed-distance-field raymarcher rendered entirely in managed code: a sphere on a checkered
/// ground plane with Lambert shading and a soft shadow, lit by one directional light. Proves NativeAOT
/// .NET can do real-time 3D on the CPU with no GPU, shaders, or external libraries. Output is row-major
/// 8-bit RGB; the camera orbits the origin by the cameraAngle parameter (radians).
/// </summary>
public static class Raymarcher
{
    private readonly record struct V(double X, double Y, double Z)
    {
        public static V operator +(V a, V b) => new(a.X + b.X, a.Y + b.Y, a.Z + b.Z);
        public static V operator -(V a, V b) => new(a.X - b.X, a.Y - b.Y, a.Z - b.Z);
        public static V operator *(V a, double s) => new(a.X * s, a.Y * s, a.Z * s);
        public double Dot(V b) => (X * b.X) + (Y * b.Y) + (Z * b.Z);
        public double Length() => Math.Sqrt(Dot(this));
        public V Normalized() { var l = Length(); return l > 0 ? this * (1.0 / l) : this; }
    }

    public static byte[] Render(double cameraAngle, int width, int height)
    {
        var pixels = new byte[width * height * 3];

        var camPos = new V(Math.Sin(cameraAngle) * 4.0, 1.6, Math.Cos(cameraAngle) * 4.0);
        var forward = (new V(0, 0, 0) - camPos).Normalized();
        var right = Cross(forward, new V(0, 1, 0)).Normalized();
        var up = Cross(right, forward);
        var light = new V(-0.6, 0.7, -0.4).Normalized();
        var aspect = (double)width / height;

        for (var py = 0; py < height; py++)
        {
            var v = 1.0 - (2.0 * py / (height - 1));
            for (var px = 0; px < width; px++)
            {
                var u = ((2.0 * px / (width - 1)) - 1.0) * aspect;
                var dir = (forward + (right * (u * 0.6)) + (up * (v * 0.6))).Normalized();
                var color = Trace(camPos, dir, light);
                var offset = ((py * width) + px) * 3;
                pixels[offset] = ToByte(color.X);
                pixels[offset + 1] = ToByte(color.Y);
                pixels[offset + 2] = ToByte(color.Z);
            }
        }

        return pixels;
    }

    public static string RenderBase64(double cameraAngle, int width, int height) =>
        $"{width}x{height}x3:{Convert.ToBase64String(Render(cameraAngle, width, height))}";

    private static V Cross(V a, V b) =>
        new((a.Y * b.Z) - (a.Z * b.Y), (a.Z * b.X) - (a.X * b.Z), (a.X * b.Y) - (a.Y * b.X));

    // Scene: unit sphere at origin + ground plane at y = -1.
    private static double Scene(V p) => Math.Min((p - new V(0, 0, 0)).Length() - 1.0, p.Y + 1.0);

    private static V Normal(V p)
    {
        const double e = 0.001;
        return new V(
            Scene(p + new V(e, 0, 0)) - Scene(p - new V(e, 0, 0)),
            Scene(p + new V(0, e, 0)) - Scene(p - new V(0, e, 0)),
            Scene(p + new V(0, 0, e)) - Scene(p - new V(0, 0, e))).Normalized();
    }

    private static V Trace(V origin, V dir, V light)
    {
        var t = 0.0;
        for (var i = 0; i < 96; i++)
        {
            var p = origin + (dir * t);
            var d = Scene(p);
            if (d < 0.001)
            {
                var n = Normal(p);
                var diffuse = Math.Max(0.0, n.Dot(light));
                var shadow = SoftShadow(p + (n * 0.01), light);
                var lit = Math.Min(1.0, 0.15 + (diffuse * shadow));
                var baseColor = p.Y <= -0.999 ? Checker(p) : new V(0.9, 0.4, 0.3);
                return baseColor * lit;
            }

            t += d;
            if (t > 20.0)
            {
                break;
            }
        }

        var sky = 0.5 + (0.5 * dir.Y);
        return new V(0.05 + (0.10 * sky), 0.07 + (0.18 * sky), 0.15 + (0.35 * sky));
    }

    private static double SoftShadow(V origin, V light)
    {
        var res = 1.0;
        var t = 0.02;
        for (var i = 0; i < 32; i++)
        {
            var h = Scene(origin + (light * t));
            if (h < 0.001)
            {
                return 0.0;
            }

            res = Math.Min(res, 8.0 * h / t);
            t += h;
            if (t > 10.0)
            {
                break;
            }
        }

        return Math.Clamp(res, 0.0, 1.0);
    }

    private static V Checker(V p) =>
        (((int)Math.Floor(p.X) + (int)Math.Floor(p.Z)) & 1) == 0
            ? new V(0.85, 0.85, 0.85)
            : new V(0.3, 0.3, 0.35);

    private static byte ToByte(double value) => (byte)(Math.Clamp(value, 0.0, 1.0) * 255.0);
}
