# Google.Protobuf with protoc codegen under NativeAOT — clean pass, and smaller than the broken alternative

**Date:** 2026-07-05
**Outcome:** ✅ **Works, cleanly.** `Google.Protobuf` with **real build-time protoc codegen**
(`Grpc.Tools` `<Protobuf>` item, messages only) AOT-publishes with **zero** trim/AOT warnings and
round-trips the exact message shape that crashed protobuf-net — including the
reflection-descriptor-driven `JsonFormatter` path, which also works.

**Why this doc exists:** `docs/protobuf-nativeaot-findings.md` killed protobuf-net for the
"framed binary RPC without ASP.NET" transport idea and named `Google.Protobuf` + protoc codegen as
the fallback. This gate proves the fallback instead of assuming it. Bonus deliverable: published
exe sizes across the batch, as a rough proxy for what each dependency adds.

## TL;DR

- ✅ `Google.Protobuf` **3.35.1** + `Grpc.Tools` **2.81.1** (both latest stable;
  `Grpc.Tools` is `PrivateAssets=all`, build-time only — it ships `protoc` and the MSBuild glue,
  nothing at runtime). protoc ran as part of `dotnet publish`; no pre-generated .cs committed.
- ✅ Zero `IL2xxx`/`IL3xxx` warnings — contrast with protobuf-net's `IL2104`/`IL3053` roll-ups.
  protoc-generated message classes are fully static: parsing/writing goes through generated
  `MessageParser<T>` code, not runtime reflection over attributes.
- ✅ `JsonFormatter.Default.Format(...)` — which walks the generated `MessageDescriptor`
  (protobuf "reflection", but over codegen'd descriptor objects, not System.Reflection) — also
  works under AOT. Probed deliberately since it's the one path that smelled reflection-ish.
- ✅ Binary size: **+1.9 MB** over the no-dependency baseline — and **~2.1 MB smaller** than the
  protobuf-net binary that doesn't even run.

## The spike

`spike/GpbGate/GpbGate.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Google.Protobuf" Version="3.35.1" />
    <PackageReference Include="Grpc.Tools" Version="2.81.1" PrivateAssets="all" />
  </ItemGroup>
  <ItemGroup>
    <Protobuf Include="Protos\inference.proto" GrpcServices="None" />
  </ItemGroup>
</Project>
```

`Protos/inference.proto` is a **trimmed spike copy** resembling the repo's real `proto/dni.proto`
(`InferRequest`'s prompt/max_tokens/temperature — the original file was **not** modified) merged
with the timings/token-list shape that killed protobuf-net: `InferenceRequest { prompt, max_tokens,
temperature, repeated token_ids }`, `InferenceResponse { text, LatencyBreakdown timings }`.
`GrpcServices="None"` generates message classes only — this gate is about the serializer;
gRPC-over-UDS (Pattern 2) already has its own transport and is out of scope here.

`Program.cs`: round-trips both messages via `ToByteArray()` / `Parser.ParseFrom()`, compares with
protoc's generated value equality, then formats the response through `JsonFormatter.Default`
inside a try/catch so a descriptor-path failure would be a named finding rather than a crash.

**Commands:**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset environment workaround as the rest of the batch — see
`onnx-nativeaot-ios-findings.md` "Toolchain potholes".)

**Publish output:** clean — restore → `Generating native code` → publish. **Zero warnings.**
(protoc codegen runs silently inside the build; the generated `Inference.cs` lands in `obj/`.)

**Run output (verbatim):**
```
Serialized InferenceRequest: 69 bytes
Round-trip request: equal=True tokens=9
Round-trip response: equal=True timings.total=642.9
JsonFormatter: { "text": "12 ft-lb in a star pattern.", "timings": { "firstTokenMs": 118.4, "totalMs": 642.9, "tokenCount": 9 } }
PASS: Google.Protobuf codegen round-tripped request + response (JsonFormatter also OK)
```

## Published exe sizes across the batch (rough dependency-cost proxy)

All `win-x64`, `Release`, `PublishAot=true`, same SDK (10.0.301), same day, sizes of the single
published `.exe`:

| Gate | Dependency | Exe size | Δ vs baseline |
|---|---|---:|---:|
| PqcGate | none (shared framework only) | 1,031,168 B (0.98 MB) | — (baseline) |
| MeaiGate | Microsoft.Extensions.AI.Abstractions 10.7.0 | 1,369,600 B (1.31 MB) | +0.32 MB |
| BcPqcGate | BouncyCastle.Cryptography 2.6.2 | 1,831,424 B (1.75 MB) | +0.76 MB |
| **GpbGate** | **Google.Protobuf 3.35.1 (protoc codegen)** | **2,950,656 B (2.81 MB)** | **+1.83 MB** |
| ProtobufGate | protobuf-net 3.2.56 (crashes at runtime) | 5,027,328 B (4.79 MB) | +3.81 MB |

Read: Google.Protobuf costs ~1.9 MB of binary for a **working** AOT protobuf stack; protobuf-net's
reflection machinery costs ~3.8 MB for one that doesn't run. (Caveats: single-sample, message-only
workload; `InvariantGlobalization=true` everywhere; exe-only — no other files in the publish dir
matter for these gates.)

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). Google.Protobuf's generated code is fully static
managed C# with no platform-conditional behavior relevant here, so the result should transfer.
One mobile-specific note: `Grpc.Tools` runs `protoc` on the **build host** (it ships binaries for
Windows/Linux/macOS incl. arm64) — codegen happens wherever the publish runs (this box or the Mac
mini), never on-device, so protoc's own platform support is a build-host question, not an app one.

## Verdict

**PASS — the fallback is proven; the framed-binary-RPC transport idea is unblocked.** Use
`Google.Protobuf` + `Grpc.Tools` codegen (messages only, `GrpcServices="None"`) if/when the 4th
transport is built; do not revisit protobuf-net. The spike's `Protos/inference.proto` shows the
pattern; a real transport would instead reference shared messages evolved from `proto/dni.proto`
(one schema, gRPC and framed transports both generated from it).
