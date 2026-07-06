using System.Buffers.Binary;
using System.Security.Cryptography;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// Per-frame AES-256-GCM for the framed-protobuf secure channel. Each direction has its OWN key (derived
/// via HKDF with a distinct info label — see <see cref="PqSecureChannel"/>) and its OWN monotonic frame
/// counter used as the GCM nonce, so a nonce is never reused under a key (the one hard AES-GCM rule).
/// On the wire an encrypted frame payload is <c>ciphertext || tag(16)</c>; the 12-byte nonce is implicit
/// (both endpoints derive it from their frame counter), so it is not transmitted.
///
/// AOT-safe: <see cref="AesGcm"/> is BCL (routed through Apple CryptoKit on iOS/macOS, which requires the
/// 16-byte tag used here) and needs no reflection.
/// </summary>
internal sealed class AeadFrameCipher(byte[] sendKey, byte[] recvKey) : IDisposable
{
    private const int NonceBytes = 12; // AesGcm nonce size
    private const int TagBytes = 16;   // AesGcm tag size (also CryptoKit's required size on Apple)

    private readonly AesGcm _send = new(sendKey, TagBytes);
    private readonly AesGcm _recv = new(recvKey, TagBytes);
    private long _sendCounter;
    private long _recvCounter;

    /// <summary>The cipher name for the Trust inspector's live-params display.</summary>
    internal const string Algorithm = "AES-256-GCM";

    /// <summary>Encrypts one frame payload; returns <c>ciphertext || tag</c>. Advances the send counter.</summary>
    internal byte[] EncryptOutbound(byte[] plaintext)
    {
        var nonce = NonceFor(_sendCounter++);
        var frame = new byte[plaintext.Length + TagBytes];
        var cipherSpan = frame.AsSpan(0, plaintext.Length);
        var tagSpan = frame.AsSpan(plaintext.Length, TagBytes);
        _send.Encrypt(nonce, plaintext, cipherSpan, tagSpan);
        return frame;
    }

    /// <summary>
    /// Decrypts one <c>ciphertext || tag</c> frame payload. Advances the receive counter. Throws
    /// <see cref="AuthenticationTagMismatchException"/> if the frame was tampered with or forged.
    /// </summary>
    internal byte[] DecryptInbound(byte[] frame)
    {
        if (frame.Length < TagBytes)
        {
            throw new AuthenticationTagMismatchException("Encrypted frame is shorter than the GCM tag.");
        }

        var nonce = NonceFor(_recvCounter++);
        var cipherLen = frame.Length - TagBytes;
        var plaintext = new byte[cipherLen];
        _recv.Decrypt(nonce, frame.AsSpan(0, cipherLen), frame.AsSpan(cipherLen, TagBytes), plaintext);
        return plaintext;
    }

    // Nonce = little-endian frame counter in the low 8 bytes, high 4 bytes zero. Monotonic per direction,
    // so unique per (key, nonce) pair for the ~2^64 frames a channel could ever carry.
    private static byte[] NonceFor(long counter)
    {
        var nonce = new byte[NonceBytes];
        BinaryPrimitives.WriteInt64LittleEndian(nonce, counter);
        return nonce;
    }

    public void Dispose()
    {
        _send.Dispose();
        _recv.Dispose();
    }
}
