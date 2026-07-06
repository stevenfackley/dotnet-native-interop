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

/// <summary>
/// Cross-layer holder for per-transport security posture that only the native bridge knows at runtime
/// (currently just the negotiated PQ channel params). The static per-transport posture text itself lives
/// with the <c>trust~posture</c> command; this holder is only for the live, negotiated bits.
/// </summary>
public static class TrustPosture
{
    private static volatile PqChannelParams? _binaryPqChannel;

    /// <summary>The live PQ channel params if a secure framed-protobuf channel is up; otherwise null (plaintext).</summary>
    public static PqChannelParams? BinaryPqChannel => _binaryPqChannel;

    /// <summary>Publishes (or clears, with null) the live PQ channel params. Called by the transport layer.</summary>
    public static void SetBinaryPqChannel(PqChannelParams? value) => _binaryPqChannel = value;
}
