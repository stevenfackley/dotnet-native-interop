using System.Buffers.Binary;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// Length-prefixed frame I/O over a socket stream: <c>[u32 little-endian length][payload]</c>. The payload
/// is a serialized <see cref="Envelope"/> on the plain channel. (The PQ secure-channel slice attaches an
/// AEAD cipher so the payload becomes AES-256-GCM <c>ciphertext||tag</c>; the length framing is unchanged.)
/// Symmetric — both the server and the loopback client use it.
/// </summary>
internal sealed class FrameConnection(Stream stream)
{
    // Cap a single frame so a malformed/hostile length prefix can't drive an unbounded allocation.
    private const int MaxFrameBytes = 16 * 1024 * 1024;

    /// <summary>Reads one frame and returns its payload bytes, or null on a clean EOF at a frame boundary.</summary>
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

        return payload;
    }

    /// <summary>Writes <paramref name="payload"/> as one length-prefixed frame.</summary>
    internal async Task WriteFrameAsync(byte[] payload, CancellationToken cancellationToken)
    {
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
