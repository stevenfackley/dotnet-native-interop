namespace DotnetNativeInterop.NativeBridge.Pqc;

/// <summary>
/// A post-quantum handshake failed (bad/absent frame, or — the security-critical case — an ML-DSA
/// signature that did not verify). Thrown as a typed error so the transport can surface it explicitly to
/// the peer rather than closing the socket silently (a Wave B honesty rule).
/// </summary>
internal sealed class PqcHandshakeException(string message) : Exception(message);
