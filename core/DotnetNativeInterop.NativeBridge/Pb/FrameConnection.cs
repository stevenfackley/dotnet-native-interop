using System.Buffers.Binary;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// Length-prefixed frame I/O over a socket stream: <c>[u32 little-endian length][payload]</c>. The payload
/// is a serialized <see cref="Envelope"/> on the plain channel; once a PQ handshake completes the caller
/// attaches (via <see cref="UseCipher"/>) an <see cref="AeadFrameCipher"/> and the payload becomes
/// AES-256-GCM <c>ciphertext||tag</c> — the length framing is unchanged. Symmetric: both the server and the
/// loopback client use it.
/// </summary>
internal sealed class FrameConnection(Stream stream)
{
    // Cap a single frame so a malformed/hostile length prefix can't drive an unbounded allocation.
    private const int MaxFrameBytes = 16 * 1024 * 1024;

    private AeadFrameCipher? _cipher;

    /// <summary>True once <see cref="UseCipher"/> has been called (subsequent frames are encrypted).</summary>
    internal bool Encrypted => _cipher is not null;

    /// <summary>Attaches the AEAD cipher established by the PQ handshake; all later frames are encrypted.</summary>
    internal void UseCipher(AeadFrameCipher cipher) => _cipher = cipher;

    /// <summary>
    /// Reads one frame and returns its plaintext payload, or null on a clean EOF at a frame boundary. On an
    /// encrypted channel a tampered/forged frame surfaces as an <see cref="System.Security.Cryptography.
    /// AuthenticationTagMismatchException"/> — never a silently accepted or dropped frame.
    /// </summary>
    internal async Task<byte[]?> ReadFrameAsync(CancellationToken cancellationToken)
    {
        var lengthPrefix = new byte[4];
        if (!await ReadExactAsync(lengthPrefix, cancellationToken).ConfigureAwait(false))
        {
            return null; // clean EOF at a frame boundary
        }

        var length = BinaryPrimitives.ReadUInt32LittleEndian(lengthPrefix);
        if (length == 0 || length > MaxFrameBytes)
        {
            throw new InvalidDataException($"Framed-protobuf length out of range: {length}.");
        }

        var payload = new byte[length];
        if (!await ReadExactAsync(payload, cancellationToken).ConfigureAwait(false))
        {
            return null; // truncated frame — treat as EOF
        }

        return _cipher is null ? payload : _cipher.DecryptInbound(payload);
    }

    /// <summary>Writes <paramref name="plaintext"/> as one frame, encrypting first when a cipher is attached.</summary>
    internal async Task WriteFrameAsync(byte[] plaintext, CancellationToken cancellationToken)
    {
        var payload = _cipher is null ? plaintext : _cipher.EncryptOutbound(plaintext);

        var lengthPrefix = new byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(lengthPrefix, (uint)payload.Length);
        await stream.WriteAsync(lengthPrefix, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private async Task<bool> ReadExactAsync(byte[] buffer, CancellationToken cancellationToken)
    {
        var read = 0;
        while (read < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(read), cancellationToken).ConfigureAwait(false);
            if (n == 0)
            {
                return false; // EOF
            }

            read += n;
        }

        return true;
    }
}
