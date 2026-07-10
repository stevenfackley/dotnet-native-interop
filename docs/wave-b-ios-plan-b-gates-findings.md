# Wave B — iOS Plan B gate findings (framed-protobuf binary transport)

**Date:** 2026-07-10 · verified on the Mac build host (Xcode 26.5.1, iOS 26.5 SDK, iPad Pro 13-inch sim).

Plan B (the 4th interop transport: length-prefixed `Google.Protobuf` frames + an opt-in post-quantum
secure channel, mirroring the merged Android Plan C) has two spike gates that decide whether iOS uses the
native path or a fallback. **Both PASSED — iOS Plan B proceeds native on both.**

---

## Gate 1 — SwiftProtobuf via SPM ✅ PASS

**Question:** can the project take its *first* Swift Package Manager dependency (SwiftProtobuf), with the
proto codegen compiling into the xcodegen-generated project? (Fallback would have been hand-written Swift
structs from the small proto.)

**Result: works, no fallback needed.**
- `brew install swift-protobuf` provides `protoc-gen-swift` (1.38.1). `build/gen-ios-proto.sh` runs
  `protoc --swift_out` over `proto/dni_frame.proto` → `ios/Shared/Pb/dni_frame.pb.swift` (committed, 1108
  lines, `Dni_Frame_V1_*` types).
- `ios/project.yml` gained a top-level `packages:` block (SwiftProtobuf, `from: 1.38.0` — tracks the
  plugin) and a `- package: SwiftProtobuf` dependency on the app + Tests targets. `xcodegen generate`
  emits the package reference; `xcodebuild` **fetched and checked out SwiftProtobuf 1.38.1 from GitHub**
  and compiled the generated types → `** BUILD SUCCEEDED **`, 0 errors.
- **Byte-exact wire contract is verified at runtime** (`ios/Tests/PbWireContractTests.swift`):
  `Envelope{request_id:"abc", ping:{}}` encodes to exactly `0A 03 61 62 63 2A 00` (field 1 then field 5),
  proving the Swift field numbers match `proto/dni_frame.proto` and therefore the .NET/Kotlin sides.

**Recipe (build host):** `brew install swift-protobuf` → `bash build/gen-ios-proto.sh` → build. The
generated `.pb.swift` is committed so a normal checkout needs neither `protoc` nor the plugin.

---

## Gate 2 — CryptoKit ML-KEM / ML-DSA on iOS 26 ✅ PASS

**Question:** does the iOS 26 SDK's CryptoKit expose native ML-KEM (FIPS 203) + ML-DSA (FIPS 204), so the
PQ handshake can be CryptoKit-native? (Fallback: a classical channel with an honest "PQ unavailable on
this OS" disclosure in the Trust inspector, same policy as FoundationModels.)

**Result: available, no fallback needed.** The full handshake chain type-checks against the iOS 26 SDK
(`xcrun --sdk iphonesimulator swiftc -target arm64-apple-ios26.0-simulator -typecheck`):

```swift
import CryptoKit; import Foundation
let kemSK = try MLKEM768.PrivateKey()
let kemPK = kemSK.publicKey
let kemPKBytes: Data = kemPK.rawRepresentation          // → HandshakeOffer.kem_public_key
let enc = try kemPK.encapsulate()
let shared: SymmetricKey = enc.sharedSecret             // KEM shared secret IS a SymmetricKey
let ct: Data = enc.encapsulated                         // → HandshakeReply.ciphertext
let shared2: SymmetricKey = try kemSK.decapsulate(ct)

let dsaSK = try MLDSA65.PrivateKey()
let dsaPKBytes: Data = dsaSK.publicKey.rawRepresentation // → HandshakeOffer.sig_public_key
let sig: Data = try dsaSK.signature(for: msg)            // → HandshakeOffer.signature (over kem_pub||session_id)
_ = dsaSK.publicKey.isValidSignature(sig, for: msg)

let key = HKDF<SHA256>.deriveKey(inputKeyMaterial: shared, salt: ct,
                                 info: Data("dni-pb c2s v1".utf8), outputByteCount: 32)
let sealed = try AES.GCM.seal(plaintext, using: key)     // AES-256-GCM, ct||tag
```

**Notes for the implementation:** `MLKEM768.PrivateKey()` / `MLDSA65.PrivateKey()` are **throwing**
initializers; `encapsulate().sharedSecret` is a `SymmetricKey` (feeds HKDF/AES-GCM directly); wire bytes
come from `.rawRepresentation`. Algorithm identifiers on the wire stay the FIPS-final `ML-KEM-768` /
`ML-DSA-65` names the proto advertises. BouncyCastle is the .NET/Android provider; iOS uses CryptoKit — a
different implementation of the **same** FIPS-final parameter sets, so cross-execution stays byte-compatible.

---

## What this unblocks

The 8-item byte-exact wire contract (see `MEMORY.md` → project-wave-program) can be implemented directly:
proto framing `[u32 LE length][Envelope]`, HKDF labels `"dni-pb c2s v1"` / `"dni-pb s2c v1"`, salt =
`ciphertext‖session_id`, AES-256-GCM nonce = 12-byte LE frame counter, per-direction keys, signature over
`kem_public_key‖session_id`. **Next increment:** the framed-protobuf transport client (loopback socket +
`PbFeatureService`), then the PQ handshake + Trust inspector, byte-matching Android Plan C.
