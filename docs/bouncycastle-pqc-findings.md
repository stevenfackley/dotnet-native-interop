# BouncyCastle ML-KEM / ML-DSA under NativeAOT — clean pass; the mobile PQC path exists

**Date:** 2026-07-05
**Outcome:** ✅ **Works, cleanly.** BouncyCastle's **pure managed** FIPS 203/204 implementations
(ML-KEM-768 encapsulate/decapsulate, ML-DSA-65 sign/verify + a tamper-rejection negative control)
AOT-publish with **zero** trim/AOT warnings and run correctly — producing byte-for-byte the same
spec sizes as the OS-backed .NET 10 gate did.

**Why this doc exists:** `docs/pqc-nativeaot-findings.md` proved .NET 10's built-in `MLKem`/`MLDsa`
are AOT-clean but **backend-blocked on mobile** — Microsoft's platform matrix lists Apple and
Android as ❌ for every parameter set, so `IsSupported == false` on both target platforms.
BouncyCastle implements the same algorithms entirely in managed C#, with no OS crypto dependency
at all. If it survives NativeAOT, the "PQ handshake on the loopback HTTP transport" feature is
back on the table for mobile with BC as the provider. It does. It is.

## TL;DR

- ✅ `BouncyCastle.Cryptography` **2.6.2** (latest stable) AOT-publishes for `win-x64`
  `PublishAot=true` with **zero** `IL2xxx`/`IL3xxx` warnings — the PQC code paths rooted by this
  probe (key generation, KEM, signer) are plain managed math with no reflection; whatever
  reflection lives elsewhere in BC (TLS, X509, provider lookup) got trimmed away without complaint.
- ✅ Round-trip output is **numerically identical** to the .NET 10 built-in gate on the same box:
  ML-KEM-768 ciphertext 1088 B / shared secret 32 B, ML-DSA-65 signature 3309 B — two independent
  implementations agreeing on the FIPS 203/204 parameter-set sizes.
- ✅ Negative control included: a tampered message correctly fails ML-DSA verification.
- ✅ Binary cost is modest: published exe 1,831,424 B (~1.75 MB), **+0.76 MB** over the
  no-dependency baseline (see the size table in `gpb-nativeaot-findings.md`).
- ⚠️ Trade-offs vs the OS backend (below) are real but none of them block the feature.

## The spike

`spike/BcPqcGate/BcPqcGate.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="BouncyCastle.Cryptography" Version="2.6.2" />
  </ItemGroup>
</Project>
```

`Program.cs` uses BC's lightweight-crypto API (namespaces `Org.BouncyCastle.Crypto.{Generators,
Kems,Parameters,Signers}`), which in 2.6.x has first-class ML-KEM/ML-DSA types — no PQC-"experimental"
namespace anymore:
- **ML-KEM-768:** `MLKemKeyPairGenerator` + `MLKemKeyGenerationParameters(random, MLKemParameters.ml_kem_768)`
  → `MLKemEncapsulator.Init(public)` / `.Encapsulate(...)` → `MLKemDecapsulator.Init(private)` /
  `.Decapsulate(...)` → compare shared secrets. Buffer sizes come from the API itself
  (`EncapsulationLength`, `SecretLength`).
- **ML-DSA-65:** `MLDsaKeyPairGenerator` → `MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false)`
  with the streaming `Init`/`BlockUpdate`/`GenerateSignature` pattern, verify, then flip a byte in
  the message and confirm `VerifySignature` returns false.

Everything is wrapped in try/catch printing exception type + message, so any AOT/trim breakage
would surface as a named finding, matching the house pattern from the sibling gates.

**Commands:**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset environment workaround as the rest of the batch — see
`onnx-nativeaot-ios-findings.md` "Toolchain potholes".)

**Publish output:** clean — restore → `Generating native code` → publish. **Zero warnings.**

**Run output (verbatim):**
```
ML-KEM-768 (BC): ciphertext=1088B secret=32B match=True
ML-DSA-65 (BC): sig=3309B verified=True tamperedRejected=True
PASS: BouncyCastle ML-KEM encapsulate/decapsulate + ML-DSA sign/verify round-tripped
```

## BC-as-provider trade-offs (none blocking, all worth knowing)

- **Not the platform's crypto.** BC is managed code: no Secure Enclave/StrongBox key protection,
  no OS-level FIPS validation certificate, key material lives in managed memory (BC does expose
  seed-based private-key formats — `MLKemPrivateKeyParameters.GetSeed()` — which helps keep
  persisted secrets small, but at-rest protection is on the app; this repo already has SQLCipher
  for that). For a **loopback/in-sandbox handshake showcase** none of this blocks; for real
  network PQC-TLS it would deserve a second look.
- **Performance is managed-code performance.** No AVX/NEON hand-tuned assembly like OpenSSL/
  SymCrypt backends. Irrelevant at handshake frequency (one KEM + one signature per session).
- **Interop sanity is already half-proven:** the byte sizes match FIPS and the .NET-10-CNG gate
  exactly. If cross-implementation interop is ever needed (BC on mobile ↔ CNG on Windows), wire a
  BC-encapsulated ciphertext into `System.Security.Cryptography.MLKem.Decapsulate` on a supported
  platform as a one-line follow-up probe.
- **API asymmetry:** BC's lightweight API (`ICipherParameters`, `AsymmetricCipherKeyPair`) is not
  the .NET `MLKem`/`MLDsa` shape. A thin provider interface (à la the engine's `ILanguageModel`
  seam) keeps the feature code backend-agnostic if the .NET built-ins ever gain an Apple/Android
  backend and BC gets swapped out.

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). For **this** gate the caveat carries unusually little
residual risk: the entire point of BC here is that it has **no** OS/native dependency — the code
that ran is the code that would run on mobile, modulo CPU architecture. The one platform-sensitive
input is `SecureRandom`, which on modern .NET wraps the OS CSPRNG via
`System.Security.Cryptography.RandomNumberGenerator` — supported on iOS/Android and already
exercised implicitly by this repo's shipped AES-GCM demo (`nativeaot-mobile-caveats.md`, Part A).

## Verdict

**PASS — the PQ-handshake feature is back on the table for mobile, with BouncyCastle as the
provider.** Unlike the built-in gate (`pqc-nativeaot-findings.md`), this result **does** transfer
to `ios-arm64`/`linux-bionic-arm64` in the usual way, because there is no OS backend to be missing.
Design note for the eventual feature: put the handshake behind a small provider seam so the BC
implementation can later be swapped for `System.Security.Cryptography.MLKem`/`MLDsa` on platforms
where `IsSupported` becomes true, without touching the transport.
