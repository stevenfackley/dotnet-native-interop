using System.Security.Cryptography;
using System.Text;
using Google.Protobuf;
using DotnetNativeInterop.NativeBridge.Pqc;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// The opt-in PQ handshake for the framed-protobuf transport, run over a still-plaintext
/// <see cref="FrameConnection"/> before any application frames flow. Protocol (one round trip):
///
///   server → client  HandshakeOffer  { ML-KEM-768 public key, ML-DSA-65 public key,
///                                       ML-DSA signature over the KEM public key, alg labels }
///   client → server  HandshakeReply  { ML-KEM ciphertext encapsulating the shared secret }
///
/// The client verifies the signature (authenticating the ephemeral KEM key to the per-boot ML-DSA
/// identity) before encapsulating. Both sides then HKDF-SHA256 the shared secret into two directional
/// AES-256-GCM keys (distinct info labels) and switch the connection to <see cref="AeadFrameCipher"/>.
/// Handshake public keys and the ciphertext are not secret, so these frames are sent in the clear.
/// </summary>
internal static class PqSecureChannel
{
    private static readonly byte[] InfoClientToServer = Encoding.ASCII.GetBytes("dni-pb c2s v1");
    private static readonly byte[] InfoServerToClient = Encoding.ASCII.GetBytes("dni-pb s2c v1");

    /// <summary>Server side of the handshake. Returns the negotiated cipher + the client ciphertext length (for the Trust inspector).</summary>
    internal static async Task<ServerHandshakeResult> ServerHandshakeAsync(
        FrameConnection connection, IPqcProvider provider, IPqcServerIdentity identity, CancellationToken cancellationToken)
    {
        var offer = new Envelope
        {
            HandshakeOffer = new HandshakeOffer
            {
                KemPublicKey = ByteString.CopyFrom(identity.KemPublicKey),
                SigPublicKey = ByteString.CopyFrom(identity.SigPublicKey),
                Signature = ByteString.CopyFrom(identity.Sign(identity.KemPublicKey)),
                KemAlgorithm = provider.KemAlgorithm,
                SigAlgorithm = provider.SigAlgorithm,
                Cipher = AeadFrameCipher.Algorithm,
            },
        };
        await connection.WriteFrameAsync(offer.ToByteArray(), cancellationToken).ConfigureAwait(false);

        var replyBytes = await connection.ReadFrameAsync(cancellationToken).ConfigureAwait(false)
            ?? throw new PqcHandshakeException("client closed the connection before sending a handshake reply");
        var reply = Envelope.Parser.ParseFrom(replyBytes);
        if (reply.BodyCase != Envelope.BodyOneofCase.HandshakeReply)
        {
            throw new PqcHandshakeException($"expected a handshake reply but received {reply.BodyCase}");
        }

        var ciphertext = reply.HandshakeReply.Ciphertext.ToByteArray();
        var sharedSecret = identity.Decapsulate(ciphertext);
        var (clientToServer, serverToClient) = DeriveKeys(sharedSecret, ciphertext);

        // Server sends on the server→client key, receives on the client→server key.
        var cipher = new AeadFrameCipher(sendKey: serverToClient, recvKey: clientToServer);
        return new ServerHandshakeResult(cipher, identity.KemPublicKey.Length, ciphertext.Length, sharedSecret.Length);
    }

    /// <summary>Client side of the handshake (used by the loopback proof harness). Returns the negotiated cipher.</summary>
    internal static async Task<AeadFrameCipher> ClientHandshakeAsync(
        FrameConnection connection, IPqcProvider provider, CancellationToken cancellationToken)
    {
        var offerBytes = await connection.ReadFrameAsync(cancellationToken).ConfigureAwait(false)
            ?? throw new PqcHandshakeException("server closed the connection before sending a handshake offer");
        var offerEnvelope = Envelope.Parser.ParseFrom(offerBytes);
        if (offerEnvelope.BodyCase != Envelope.BodyOneofCase.HandshakeOffer)
        {
            throw new PqcHandshakeException($"expected a handshake offer but received {offerEnvelope.BodyCase}");
        }

        var offer = offerEnvelope.HandshakeOffer;
        var kemPublicKey = offer.KemPublicKey.ToByteArray();
        if (!provider.Verify(offer.SigPublicKey.ToByteArray(), kemPublicKey, offer.Signature.ToByteArray()))
        {
            // The KEM key is not authenticated to the server's ML-DSA identity — abort, never proceed.
            throw new PqcHandshakeException("server handshake signature failed ML-DSA verification");
        }

        var encapsulation = provider.Encapsulate(kemPublicKey);
        var reply = new Envelope
        {
            HandshakeReply = new HandshakeReply { Ciphertext = ByteString.CopyFrom(encapsulation.Ciphertext) },
        };
        await connection.WriteFrameAsync(reply.ToByteArray(), cancellationToken).ConfigureAwait(false);

        var (clientToServer, serverToClient) = DeriveKeys(encapsulation.SharedSecret, encapsulation.Ciphertext);

        // Client sends on the client→server key, receives on the server→client key (mirror of the server).
        return new AeadFrameCipher(sendKey: clientToServer, recvKey: serverToClient);
    }

    // HKDF-SHA256 the KEM shared secret into two independent directional keys. The ciphertext (unique per
    // session, known to both sides) is the salt, binding the keys to this exact handshake transcript.
    private static (byte[] ClientToServer, byte[] ServerToClient) DeriveKeys(byte[] sharedSecret, byte[] salt)
    {
        var clientToServer = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, outputLength: 32, salt, InfoClientToServer);
        var serverToClient = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, outputLength: 32, salt, InfoServerToClient);
        return (clientToServer, serverToClient);
    }
}

/// <summary>The server handshake outcome: the negotiated cipher plus sizes for the Trust inspector's live params.</summary>
internal sealed record ServerHandshakeResult(AeadFrameCipher Cipher, int KemPublicKeyBytes, int CiphertextBytes, int SharedSecretBytes);
