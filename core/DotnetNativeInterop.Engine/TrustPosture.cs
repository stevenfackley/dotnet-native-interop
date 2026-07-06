using System.Text.Json;
using System.Text.Json.Serialization;

namespace DotnetNativeInterop.Engine;

/// <summary>
/// The live parameters of an active framed-protobuf PQ secure channel, published by the transport when a
/// handshake completes and cleared when the server stops. Lives in the engine (not the native bridge) so
/// the <c>trust~posture</c> command — which the engine's command grammar answers — can read it without the
/// engine depending on the transport layer.
/// </summary>
public sealed record PqChannelParams(
    string Kem, string Sig, string Cipher,
    int KemPublicKeyBytes, int CiphertextBytes, int SharedSecretBytes, double HandshakeUs);

/// <summary>The security posture of one interop transport, told honestly (a plaintext channel says so).</summary>
/// <param name="Transport">Transport name: <c>ffi</c> / <c>http</c> / <c>sqlcipher</c> / <c>binary</c>.</param>
/// <param name="InProcess">True when no socket/file is involved (the FFI in-process boundary).</param>
/// <param name="Encrypted">True when the bytes on the wire/at rest are encrypted.</param>
/// <param name="Wire">What actually carries the bytes.</param>
/// <param name="Detail">The honest one-line disclosure the Trust inspector shows.</param>
public sealed record TransportPosture(string Transport, bool InProcess, bool Encrypted, string Wire, string Detail);

/// <summary>The full <c>trust~posture</c> payload: per-transport posture plus the live PQ params when a channel is up.</summary>
public sealed record TrustPostureReport(TransportPosture[] Transports, PqChannelParams? BinaryPqChannel);

/// <summary>
/// Per-transport security posture for the Trust inspector. The static posture text lives here; the live,
/// negotiated PQ channel params are published by the native transport layer via <see cref="SetBinaryPqChannel"/>.
/// Honesty is the whole point: HTTP is reported as plaintext loopback, and the Binary transport reports
/// plaintext until (and unless) a PQ handshake actually completes.
/// </summary>
public static class TrustPosture
{
    private static volatile PqChannelParams? _binaryPqChannel;

    /// <summary>The live PQ channel params if a secure framed-protobuf channel is up; otherwise null (plaintext).</summary>
    public static PqChannelParams? BinaryPqChannel => _binaryPqChannel;

    /// <summary>Publishes (or clears, with null) the live PQ channel params. Called by the transport layer.</summary>
    public static void SetBinaryPqChannel(PqChannelParams? value) => _binaryPqChannel = value;

    /// <summary>Builds the current per-transport posture report (static text + live PQ params).</summary>
    public static TrustPostureReport Report()
    {
        var pq = _binaryPqChannel;
        var binary = pq is null
            ? new TransportPosture("binary", InProcess: false, Encrypted: false,
                "127.0.0.1 loopback TCP · framed protobuf",
                "Framed protobuf over PLAINTEXT loopback. Start with the PQ flag to negotiate an ML-KEM/ML-DSA channel.")
            : new TransportPosture("binary", InProcess: false, Encrypted: true,
                "127.0.0.1 loopback TCP · framed protobuf",
                $"PQ channel up: {pq.Kem} key exchange, {pq.Sig} handshake signature, {pq.Cipher} per frame.");

        return new TrustPostureReport(
            [
                new TransportPosture("ffi", InProcess: true, Encrypted: false,
                    "in-process (no socket)",
                    "C ABI call across the managed/native boundary; the trust boundary is the OS process, not a network."),
                new TransportPosture("http", InProcess: false, Encrypted: false,
                    "127.0.0.1 loopback TCP",
                    "PLAINTEXT loopback HTTP — deliberate and disclosed; there is no TLS on the local socket."),
                new TransportPosture("sqlcipher", InProcess: false, Encrypted: true,
                    "on-disk SQLCipher database (WAL)",
                    "AES-256 encrypted at rest via PRAGMA key; the native host owns and supplies the key."),
                binary,
            ],
            pq);
    }

    /// <summary>Serializes <see cref="Report"/> to camelCase JSON via the source-gen context.</summary>
    public static string ReportJson() => JsonSerializer.Serialize(Report(), TrustJsonContext.Default.TrustPostureReport);
}

/// <summary>Source-generated JSON metadata for the trust posture payload (AOT-safe, no reflection).</summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(TrustPostureReport))]
internal sealed partial class TrustJsonContext : JsonSerializerContext;
