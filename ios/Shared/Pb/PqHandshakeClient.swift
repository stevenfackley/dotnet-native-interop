import CryptoKit
import Foundation

/// The negotiated params of a completed PQ handshake — feeds the Trust inspector's live-params card.
/// Mirrors the engine's / Android's `PqChannelParams`.
struct PqChannelParams: Sendable, Equatable {
    let kem: String
    let sig: String
    let cipher: String
    let kemPublicKeyBytes: Int
    let ciphertextBytes: Int
    let sharedSecretBytes: Int
    let handshakeUs: Double
}

/// The outcome of a client handshake: the per-frame cipher plus the negotiated params for the UI.
/// Gated with the cipher (iOS 26+); `PqChannelParams` above is plain data and stays always-available.
@available(iOS 26.0, macOS 15.0, *)
struct PqHandshakeResult {
    let cipher: AeadFrameCipher
    let params: PqChannelParams
}

enum PqHandshakeError: LocalizedError {
    case noOffer                    // peer closed before sending the offer
    case wrongFrame(String)         // a frame that isn't a HandshakeOffer
    case badKemKey(String)          // malformed ML-KEM public key in the offer
    case signatureFailed            // ML-DSA verification of the offer failed (fatal)

    var errorDescription: String? {
        switch self {
        case .noOffer:            return "framed-protobuf PQ: server closed before sending a handshake offer"
        case .wrongFrame(let b):  return "framed-protobuf PQ: expected a handshake offer but got \(b)"
        case .badKemKey(let m):   return "framed-protobuf PQ: invalid ML-KEM public key in offer (\(m))"
        case .signatureFailed:    return "framed-protobuf PQ: server handshake signature failed ML-DSA verification"
        }
    }
}

/// Client side of the framed-protobuf PQ handshake — the CryptoKit mirror of the engine's
/// `PqSecureChannel.ServerHandshakeAsync` and Android's `PqHandshakeClient`, run over a still-plaintext
/// `PbConnection` before any application frames flow. One round trip:
///
///   server -> client  HandshakeOffer  { ML-KEM-768 public key, ML-DSA-65 public key, session_id (fresh),
///                                        ML-DSA signature over (kem_public_key || session_id), alg labels }
///   client -> server  HandshakeReply  { ML-KEM ciphertext encapsulating the shared secret }
///
/// The client verifies the signature over (kem_public_key || session_id) BEFORE encapsulating, then both
/// sides HKDF-SHA256 the shared secret into two directional AES-256-GCM keys (salt = ciphertext ||
/// session_id, so a replayed ciphertext can never resurrect the same keys) and switch to `AeadFrameCipher`.
/// CryptoKit implements the SAME FIPS-final parameter sets (ML-KEM-768 / ML-DSA-65) as the .NET/Android
/// BouncyCastle provider, so the shared secret — and every derived key — is byte-compatible cross-platform.
///
/// SECURITY NOTE (loopback teaching artifact): the ML-DSA signature authenticates handshake transcript
/// INTEGRITY against a passive tamperer — it does NOT pin the server's identity (trust-on-first-use).
/// Do not use off loopback without pinning the server's ML-DSA public key.
@available(iOS 26.0, macOS 15.0, *)
enum PqHandshakeClient {
    // Directional HKDF info labels — ASCII, byte-identical to the engine's / Android's. The client SENDS
    // on c2s and RECEIVES on s2c (mirror of the server).
    private static let infoClientToServer = Data("dni-pb c2s v1".utf8)
    private static let infoServerToClient = Data("dni-pb s2c v1".utf8)
    private static let keyBytes = 32

    /// Runs the handshake over `connection` (which must NOT yet have a cipher attached) and returns the
    /// negotiated cipher + params. The caller attaches the cipher after this returns, so the handshake
    /// frames themselves stay plaintext (as the server expects). `nowNs` supplies the monotonic clock so
    /// the caller can time the handshake without this depending on a global clock.
    static func handshake(_ connection: PbConnection, nowNs: () -> UInt64) throws -> PqHandshakeResult {
        let start = nowNs()

        guard let offerEnvelope = try connection.readEnvelope() else { throw PqHandshakeError.noOffer }
        guard case .handshakeOffer(let offer)? = offerEnvelope.body else {
            throw PqHandshakeError.wrongFrame(String(describing: offerEnvelope.body))
        }

        let kemPublicKey = offer.kemPublicKey
        let sessionId = offer.sessionID

        // Verify the ML-DSA-65 signature over (kem_public_key || session_id) — exactly what the server
        // signed. A malformed key/sig, or a well-formed-but-wrong signature, is fatal.
        let signedMessage = kemPublicKey + sessionId
        guard let sigPub = try? MLDSA65.PublicKey(rawRepresentation: offer.sigPublicKey),
              sigPub.isValidSignature(offer.signature, for: signedMessage) else {
            throw PqHandshakeError.signatureFailed
        }

        // Encapsulate to the server's ML-KEM public key -> (ciphertext on the wire, shared secret local).
        let kemPub: MLKEM768.PublicKey
        do {
            kemPub = try MLKEM768.PublicKey(rawRepresentation: kemPublicKey)
        } catch {
            throw PqHandshakeError.badKemKey(error.localizedDescription)
        }
        let encapsulated = try kemPub.encapsulate()
        let ciphertext = encapsulated.encapsulated
        let sharedSecret = encapsulated.sharedSecret          // SymmetricKey (the 32-byte KEM secret)

        var reply = Dni_Frame_V1_Envelope()
        var handshakeReply = Dni_Frame_V1_HandshakeReply()
        handshakeReply.ciphertext = ciphertext
        reply.handshakeReply = handshakeReply
        try connection.writeRequest(reply)                    // still plaintext (cipher not yet attached)

        // Derive the two directional keys. salt = ciphertext || session_id (session_id in the SALT, so a
        // fresh session_id randomizes the extracted PRK — matching the engine's / Android's DeriveKeys).
        let salt = ciphertext + sessionId
        let clientToServer = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: sharedSecret, salt: salt, info: infoClientToServer, outputByteCount: keyBytes)
        let serverToClient = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: sharedSecret, salt: salt, info: infoServerToClient, outputByteCount: keyBytes)
        let cipher = AeadFrameCipher(sendKey: clientToServer, recvKey: serverToClient)

        let handshakeUs = Double(nowNs() - start) / 1000.0
        let params = PqChannelParams(
            kem: offer.kemAlgorithm.isEmpty ? "ML-KEM-768" : offer.kemAlgorithm,
            sig: offer.sigAlgorithm.isEmpty ? "ML-DSA-65" : offer.sigAlgorithm,
            cipher: offer.cipher.isEmpty ? AeadFrameCipher.algorithm : offer.cipher,
            kemPublicKeyBytes: kemPublicKey.count,
            ciphertextBytes: ciphertext.count,
            sharedSecretBytes: sharedSecret.withUnsafeBytes { $0.count },
            handshakeUs: handshakeUs)
        return PqHandshakeResult(cipher: cipher, params: params)
    }
}
