import CryptoKit
import Foundation

/// Per-frame AES-256-GCM for the framed-protobuf secure channel — the Swift/CryptoKit mirror of the
/// engine's `AeadFrameCipher` and the Android one. Each direction has its OWN key (HKDF with a distinct
/// info label; see `PqHandshakeClient`) and its OWN monotonic frame counter used as the GCM nonce, so a
/// nonce is never reused under a key.
///
/// On the wire an encrypted frame payload is `ciphertext || tag(16)`; the 12-byte nonce is implicit (both
/// endpoints derive it from their frame counter) and never transmitted. CryptoKit's `SealedBox` exposes
/// `.ciphertext` and `.tag` separately — concatenated they are the exact `ciphertext || tag` layout the
/// engine's `AesGcm.Encrypt(cipherSpan, tagSpan)` and Android's `javax.crypto` produce. No AAD (a
/// deliberate match: the monotonic counter=nonce already authenticates frame order).
///
/// CryptoKit's ML-KEM/ML-DSA + this class's `AES.GCM` explicit-nonce API are iOS 26+. The app targets
/// iOS 17, so the whole PQ path is gated `@available(iOS 26.0, *)`; on older OS the binary transport
/// stays plaintext (honest disclosure in the Trust inspector — the same policy as FoundationModels).
@available(iOS 26.0, macOS 15.0, *)
final class AeadFrameCipher {
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    static let algorithm = "AES-256-GCM"
    private static let tagBytes = 16

    init(sendKey: SymmetricKey, recvKey: SymmetricKey) {
        self.sendKey = sendKey
        self.recvKey = recvKey
    }

    /// Encrypts one frame payload; returns `ciphertext || tag`. Advances the send counter.
    func encryptOutbound(_ plaintext: Data) throws -> Data {
        let nonce = try Self.nonce(for: sendCounter)
        sendCounter += 1
        let sealed = try AES.GCM.seal(plaintext, using: sendKey, nonce: nonce)
        return sealed.ciphertext + sealed.tag   // nonce is implicit; never on the wire
    }

    /// Decrypts one `ciphertext || tag` frame payload. Advances the receive counter. Throws
    /// `CryptoKitError.authenticationFailure` (the analogue of the engine's tag-mismatch) on a tampered,
    /// forged, or reordered frame.
    func decryptInbound(_ frame: Data) throws -> Data {
        guard frame.count >= Self.tagBytes else { throw PbTransportError.closedEarly }
        let nonce = try Self.nonce(for: recvCounter)
        recvCounter += 1
        let split = frame.count - Self.tagBytes
        let ciphertext = Data(frame.prefix(split))     // fresh Data — normalize slice indices to 0
        let tag = Data(frame.suffix(Self.tagBytes))
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try AES.GCM.open(box, using: recvKey)
    }

    /// Nonce = little-endian frame counter in the low 8 bytes, high 4 bytes zero — identical to the
    /// engine's `BinaryPrimitives.WriteInt64LittleEndian` into a 12-byte buffer and Android's `nonceFor`.
    private static func nonce(for counter: UInt64) throws -> AES.GCM.Nonce {
        var bytes = [UInt8](repeating: 0, count: 12)
        withUnsafeBytes(of: counter.littleEndian) { src in
            for i in 0..<8 { bytes[i] = src[i] }
        }
        return try AES.GCM.Nonce(data: Data(bytes))
    }
}
