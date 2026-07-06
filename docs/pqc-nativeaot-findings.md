# Post-quantum crypto (ML-KEM / ML-DSA) under NativeAOT — what worked, and the platform wall behind it

**Date:** 2026-07-05
**Outcome:** ✅ **NativeAOT gate PASSED** — ML-KEM-768 encapsulate/decapsulate and ML-DSA-65
sign/verify both AOT-compile, link, and round-trip cleanly in a `win-x64` `PublishAot=true` binary,
with **zero** trim/AOT warnings. ⚠️ **But the mobile-RID caveat below is load-bearing, not
boilerplate**: unlike the other four gates, this PASS does **not** de-risk iOS/Android — see
[Mobile-RID caveat](#mobile-rid-caveat-read-this-one).

**Why this doc exists:** gates a future "PQ handshake on the loopback HTTP transport" feature
before any feature work. The question wasn't just "does ILC compile it" — it was also "does the
platform even have a PQC backend," which turned out to be the real gate.

## TL;DR

- ✅ `System.Security.Cryptography.MLKem` and `MLDsa` (new in **.NET 10**, `System.Security.Cryptography.dll`,
  part of the shared framework — no NuGet package needed) both AOT-compile and run under
  `PublishAot=true` on `win-x64`, with no trim/AOT warnings at all.
- ✅ On **this** Windows 11 box (build 10.0.26200), `MLKem.IsSupported` and `MLDsa.IsSupported`
  are both `true` at runtime, via Windows CNG. The round trip produced spec-correct sizes:
  ML-KEM-768 ciphertext 1088 B / shared secret 32 B (FIPS 203); ML-DSA-65 signature 3309 B (FIPS 204).
- ⚠️ **`MLDsa` (and `SlhDsa`/`CompositeMLDsa`) are `[Experimental("SYSLIB5006")]`** in .NET 10 —
  consuming code needs `<NoWarn>SYSLIB5006</NoWarn>` or a `#pragma warning disable`, else it's a
  **compile error**, not a warning (Roslyn treats `[Experimental]` diagnostics as errors by default).
  `MLKem` itself is *not* experimental, but a few of its methods (e.g. `ExportSubjectPublicKeyInfoPem`)
  are; the spike avoided those and used the non-experimental `ExportEncapsulationKey()` instead.
- 🛑 **Microsoft's own cross-platform support matrix lists Apple and Android as ❌ for every ML-KEM
  and ML-DSA parameter set.** Only Windows (CNG) and Linux (OpenSSL 3.5+) have a backend today. This
  is a platform-support gap, not a NativeAOT/trim gap — no amount of ILC or linker work fixes it.

## The spike

`spike/PqcGate/PqcGate.csproj` — no NuGet package; the PQC types live directly in the `net10.0`
shared framework's `System.Security.Cryptography.dll`:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
    <NoWarn>$(NoWarn);SYSLIB5006</NoWarn>
  </PropertyGroup>
</Project>
```

`Program.cs`: checks `MLKem.IsSupported` / `MLDsa.IsSupported`, then does a full ML-KEM-768
encapsulate/decapsulate (as two parties: "alice" holds the decapsulation key, "bob" imports just
the exported encapsulation key — the actual KEM handshake shape) and an ML-DSA-65 sign/verify over
a UTF-8 message, catching and printing any exception by type + message so a `PlatformNotSupportedException`
would show up as a **named, first-class finding** rather than a crash.

**Commands:**
```powershell
$msvc = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Tools\MSVC\14.42.34433"
$sdk  = "C:\Program Files (x86)\Windows Kits\10"; $sdkv = "10.0.22621.0"
$env:PATH    = "$msvc\bin\Hostx64\x64;$env:PATH"
$env:LIB     = "$msvc\lib\x64;$sdk\Lib\$sdkv\um\x64;$sdk\Lib\$sdkv\ucrt\x64"
$env:INCLUDE = "$msvc\include;$sdk\Include\$sdkv\ucrt;$sdk\Include\$sdkv\shared;$sdk\Include\$sdkv\um"
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same toolchain-pothole workaround as `onnx-nativeaot-ios-findings.md` — VS 2026/v18 prerelease
auto-detection can't resolve a usable MSVC toolset on this box.)

**Publish output:** clean — `Determining projects to restore` → `Generating native code` →
published, **zero warnings, zero errors**.

**Run output (`.NET 10.0.301` SDK, `System.Security.Cryptography` from the `net10.0` shared
framework, Windows 11 Pro 10.0.26200):**
```
MLKem.IsSupported = True
MLDsa.IsSupported = True
ML-KEM-768: ciphertext=1088B secret=32B match=True
ML-DSA-65: sig=3309B verified=True
PASS: ML-KEM encapsulate/decapsulate + ML-DSA sign/verify round-tripped
```

Sizes match FIPS 203/204 exactly for these parameter sets (ML-KEM-768: 1088 B ciphertext, 32 B
shared secret; ML-DSA-65: 3309 B signature — see the Windows Insider CNG PQC announcement table),
which is a second independent confirmation the round trip is doing real crypto, not a stub.

## Mobile-RID caveat (read this one)

Every other gate in this batch carries the standard caveat: *"NativeAOT can't compile for
`ios-arm64`/`linux-bionic-arm64` on this Windows box, so a `win-x64` `PublishAot=true` probe is the
accepted first-order signal — same ILC, same trimming/reflection constraints."* That caveat
assumes the only open question is **compilation**. For PQC, it isn't.

Per Microsoft's own [cross-platform cryptography support matrix][xplat-crypto] (as of .NET 10),
**every** ML-KEM and ML-DSA parameter set is:

| Algorithm family | Windows | Linux | **Apple** | **Android** |
|---|---|---|---|---|
| ML-KEM (512/768/1024) | Windows 11 Insiders (Latest) | OpenSSL 3.5.0+ | **❌** | **❌** |
| ML-DSA (44/65/87) | Windows 11 Insiders (Latest) | OpenSSL 3.5.0+ | **❌** | **❌** |

`MLKemOpenSsl`/`MLDsaOpenSsl` (Linux) and `MLKemCng`/`MLDsaCng` (Windows) are the only backing
implementations that exist; there is no Apple (CryptoKit/Security.framework) or Android
(Keystore/Conscrypt) backend in .NET 10 at all. Concretely: **`MLKem.IsSupported` and
`MLDsa.IsSupported` would both return `false` on iOS and Android today**, and every generate/import
call would throw `PlatformNotSupportedException` — not because NativeAOT can't compile it (it
compiles fine, per this gate), but because the OS-level crypto library isn't wired up.

**This inverts the usual gate signal.** For the other four gates, a `win-x64` PASS is a strong
green light for mobile. For PQC, the `win-x64` PASS only proves the **managed API surface and its
AOT-compiled codegen** are sound; it says nothing about mobile viability, because the blocker is a
missing platform backend, not a missing NativeAOT capability. **A "PQ handshake on the loopback
HTTP transport" feature cannot ship on-device on iOS or Android with .NET 10's built-in PQC APIs.**

## Verdict

**PASS (NativeAOT compile/run) — but NOT VIABLE on iOS/Android today (platform backend gap).**
Revisit when either (a) a future .NET release adds an Apple/Android PQC backend, or (b) the
feature is re-scoped to a portable, non-OS-backed PQC library (e.g. a managed or vendored liboqs/
BoringSSL-style implementation) — which would then need its **own** NativeAOT-mobile spike, since
it wouldn't reuse this gate's win-x64 result at all.

[xplat-crypto]: https://learn.microsoft.com/dotnet/standard/security/cross-platform-cryptography#post-quantum-cryptography
