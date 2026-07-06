using System.Security.Cryptography;
using System.Text;
using Google.Protobuf;
using DotnetNativeInterop.NativeBridge.Pqc;

namespace DotnetNativeInterop.NativeBridge.Pb;

/// <summary>
/// The opt-in PQ handshake for the framed-protobuf transport, run over a still-plaintext
/// <see cref="FrameConnection"/> before any application frames flow. Protocol (one round trip):
///
///   server → client  HandshakeOffer  { ML-KEM-768 public key, ML-DSA-65 public key, session_id (fresh),
///                                       ML-DSA signature over (kem_public_key || session_id), alg labels }
///   client → server  HandshakeReply  { ML-KEM ciphertext encapsulating the shared secret }
///
/// The client verifies the signature (over the KEM key AND the fresh session_id) before encapsulating.
/// Both sides then HKDF-SHA256 the shared secret into two directional AES-256-GCM keys and switch the
/// connection to <see cref="AeadFrameCipher"/>. Handshake public keys, session_id and the ciphertext are
/// not secret, so these frames are sent in the clear.
///
/// SECURITY NOTE (this is a loopback teaching artifact): the ML-DSA signature authenticates handshake
/// TRANSCRIPT INTEGRITY against a passive tamperer — it does NOT pin the server's identity. This is
/// trust-on-first-use: nothing here checks that <c>SigPublicKey</c> belongs to the expected server, so an
/// active man-in-the-middle could present its own signed offer. Do NOT use off loopback without pinning
/// the server's <c>SigPublicKey</c> (e.g. baking the expected key into the client).
/// </summary>
internal static class PqSecureChannel
{
    private static readonly byte[] InfoClientToServer = Encoding.ASCII.GetBytes("dni-pb c2s v1");
    private static readonly byte[] InfoServerToClient = Encoding.ASCII.GetBytes("dni-pb s2c v1");

    /// <summary>Server side of the handshake. Returns the negotiated cipher + sizes for the Trust inspector.</summary>
    internal static async Task<ServerHandshakeResult> ServerHandshakeAsync(
        FrameConnection connection, IPqcProvider provider, IPqcServerIdentity identity, CancellationToken cancellationToken)
    {
        // Fresh per-handshake server randomness (OS CSPRNG — equivalent to BouncyCastle's SecureRandom, kept
        // out of the transport layer to preserve the provider seam). This is what makes key derivation
        // NON-replayable: without it, salt = ciphertext + the per-boot KEM key is entirely replayable public
        // data, so a replayed client ciphertext would resurrect identical AES-GCM keys (key + nonce reuse).
        var sessionId = RandomNumberGenerator.GetBytes(32);

        var offer = new Envelope
        {
            HandshakeOffer = new HandshakeOffer
            {
                KemPublicKey = ByteString.CopyFrom(identity.KemPublicKey),
                SigPublicKey = ByteString.CopyFrom(identity.SigPublicKey),
                // Sign the KEM key bound to this handshake's session_id — the signed message is now fresh per
                // handshake (so re-signing every handshake is correct, not wasteful re-work of a static value).
                Signature = ByteString.CopyFrom(identity.Sign(Concat(identity.KemPublicKey, sessionId))),
                KemAlgorithm = provider.KemAlgorithm,
                SigAlgorithm = provider.SigAlgorithm,
                Cipher = AeadFrameCipher.Algorithm,
                SessionId = ByteString.CopyFrom(sessionId),
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
        var (clientToServer, serverToClient) = DeriveKeys(sharedSecret, ciphertext, sessionId);

        // Server sends on the server→client key, receives on the client→server key.
        var cipher = new AeadFrameCipher(sendKey: serverToClient, recvKey: clientToServer);
        return new ServerHandshakeResult(cipher, identity.KemPublicKey.Length, ciphertext.Length, sharedSecret.Length);
    }

    /// <summary>Client side of the handshake (used by the loopback proof harness). Returns the negotiated cipher + session id.</summary>
    internal static async Task<ClientHandshakeResult> ClientHandshakeAsync(
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
        var sessionId = offer.SessionId.ToByteArray();

        // Verify the signature over (kem_public_key || session_id) — matching what the server signed.
        if (!provider.Verify(offer.SigPublicKey.ToByteArray(), Concat(kemPublicKey, sessionId), offer.Signature.ToByteArray()))
        {
            // The KEM key + session_id are not authenticated to the server's ML-DSA identity — abort.
            throw new PqcHandshakeException("server handshake signature failed ML-DSA verification");
        }

        var encapsulation = provider.Encapsulate(kemPublicKey);
        var reply = new Envelope
        {
            HandshakeReply = new HandshakeReply { Ciphertext = ByteString.CopyFrom(encapsulation.Ciphertext) },
        };
        await connection.WriteFrameAsync(reply.ToByteArray(), cancellationToken).ConfigureAwait(false);

        var (clientToServer, serverToClient) = DeriveKeys(encapsulation.SharedSecret, encapsulation.Ciphertext, sessionId);

        // Client sends on the client→server key, receives on the server→client key (mirror of the server).
        var cipher = new AeadFrameCipher(sendKey: clientToServer, recvKey: serverToClient);
        return new ClientHandshakeResult(cipher, sessionId);
    }

    // HKDF-SHA256 the KEM shared secret into two independent directional keys. The SALT is
    // (ciphertext || session_id): session_id goes into the salt (not info) deliberately — salt feeds
    // HKDF-Extract, so a fresh session_id randomizes the extracted PRK itself, making EVERY derived key
    // fresh even if the client ciphertext (and the per-boot KEM key) is replayed byte-for-byte. The info
    // label then only separates the two directions.
    private static (byte[] ClientToServer, byte[] ServerToClient) DeriveKeys(
        byte[] sharedSecret, byte[] ciphertext, byte[] sessionId)
    {
        var salt = Concat(ciphertext, sessionId);
        var clientToServer = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, outputLength: 32, salt, InfoClientToServer);
        var serverToClient = HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, outputLength: 32, salt, InfoServerToClient);
        return (clientToServer, serverToClient);
    }

    private static byte[] Concat(byte[] first, byte[] second)
    {
        var result = new byte[first.Length + second.Length];
        Buffer.BlockCopy(first, 0, result, 0, first.Length);
        Buffer.BlockCopy(second, 0, result, first.Length, second.Length);
        return result;
    }
}

/// <summary>The server handshake outcome: the negotiated cipher plus sizes for the Trust inspector's live params.</summary>
internal sealed record ServerHandshakeResult(AeadFrameCipher Cipher, int KemPublicKeyBytes, int CiphertextBytes, int SharedSecretBytes);

/// <summary>The client handshake outcome: the negotiated cipher plus the fresh session id (for replay-freshness checks).</summary>
internal sealed record ClientHandshakeResult(AeadFrameCipher Cipher, byte[] SessionId);
