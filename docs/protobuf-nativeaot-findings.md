# protobuf-net under NativeAOT — publishes clean, crashes on first use

**Date:** 2026-07-05
**Outcome:** ⚠️ **Publish PASSED (with warnings). Run FAILED** — the exact same binary that ILC
happily compiled crashes with an unhandled `ArgumentException` the instant protobuf-net's
attribute-based runtime model tries to serialize anything.

**Why this doc exists:** gates a possible 4th "framed binary RPC" transport (no ASP.NET) alongside
the repo's existing JSON/gRPC-ish/whatever transports, using a message shape resembling the
engine's inference request/response (prompt string, token list, timings).

## TL;DR

- ✅ `protobuf-net` **3.2.56** (latest stable) AOT-**publishes** for `win-x64` `PublishAot=true`.
- ⚠️ Publish emits `IL2104`/`IL3053` ("produced trim warnings" / "produced AOT analysis warnings")
  against **both** `protobuf-net.dll` and `protobuf-net.Core.dll` — the package ships no
  `IsTrimmable`/AOT annotations of its own.
- 🛑 **The published binary crashes on the very first `Serializer.Serialize` call**, with a
  deterministic `System.ArgumentException: Value does not fall within the expected range.` deep
  inside protobuf-net's reflection-based attribute inspection (`AttributeMap.ReflectionAttributeMap.TryGet`
  → `RuntimePropertyInfo.GetValue`). This reproduced identically on two separate runs.
- **Root cause shape:** protobuf-net's default (`[ProtoContract]`/`[ProtoMember]`-attribute-driven)
  model is built **lazily at first use** by reflecting over the POCO's properties and their custom
  attributes. Under full NativeAOT trimming, that reflection path breaks — not with a clean
  "unsupported" message, but with an internal metadata-lookup exception, because the reflection
  metadata it needs wasn't preserved and there's no static/source-generated fallback in play here.

## The spike

`spike/ProtobufGate/ProtobufGate.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="protobuf-net" Version="3.2.56" />
  </ItemGroup>
</Project>
```

`Program.cs` defines two `[ProtoContract]` POCOs shaped like the engine's real inference traffic
(`InferenceRequest { Prompt, TokenIds, RequestedAt }`, `InferenceResponse { Text, Timings }` with a
nested `LatencyBreakdown { FirstTokenMs, TotalMs, TokenCount }`), then round-trips both through
`Serializer.Serialize`/`Serializer.Deserialize<T>` over a `MemoryStream`.

**Command:**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset environment workaround as the other gates in this batch — VS 2026/v18
prerelease auto-detection can't resolve a usable toolset here.)

**Publish output (verbatim, trimmed to the relevant lines):**
```
  ProtobufGate -> ...\ProtobufGate.dll
  Generating native code
D:\...\protobuf-net.core\3.2.56\lib\net8.0\protobuf-net.Core.dll : warning IL2104: Assembly 'protobuf-net.Core' produced trim warnings. For more information see https://aka.ms/il2104
D:\...\protobuf-net.core\3.2.56\lib\net8.0\protobuf-net.Core.dll : warning IL3053: Assembly 'protobuf-net.Core' produced AOT analysis warnings.
D:\...\protobuf-net\3.2.56\lib\net8.0\protobuf-net.dll : warning IL2104: Assembly 'protobuf-net' produced trim warnings. For more information see https://aka.ms/il2104
D:\...\protobuf-net\3.2.56\lib\net8.0\protobuf-net.dll : warning IL3053: Assembly 'protobuf-net' produced AOT analysis warnings.
  ProtobufGate -> ...\publish\
```
Exit code **0** — publish succeeds. Note protobuf-net ships a `net8.0` TFM lib (no `net10.0`-specific
build), and these are the *rolled-up* per-assembly summary warnings (`IL2104`/`IL3053`); ILC didn't
surface the individual member-level `IL2026`/`IL3050` diagnostics for this package the way EF Core's
did (see `efcore-nativeaot-findings.md`) — the package apparently doesn't annotate the specific
`RequiresUnreferencedCode`/`RequiresDynamicCode` call sites, it just triggers the assembly-wide roll-up.

**Run output (verbatim, reproduced on two separate runs, exit code `-1073740791` = `0xC0000409`,
the standard NativeAOT unhandled-exception fail-fast code):**
```
Unhandled exception. System.ArgumentException: Value does not fall within the expected range.
   at System.Reflection.Runtime.PropertyInfos.RuntimePropertyInfo.GetValue(Object, BindingFlags, Binder, Object[], CultureInfo) + 0xba
   at ProtoBuf.Meta.AttributeMap.ReflectionAttributeMap.TryGet(String, Boolean, Object&) + 0xe0
   at ProtoBuf.Meta.MetaType.ApplyDefaultBehaviourImpl(CompatibilityLevel) + 0x540
   at ProtoBuf.Meta.MetaType.ApplyDefaultBehaviour(CompatibilityLevel) + 0x3c
   at ProtoBuf.Meta.RuntimeTypeModel.FindOrAddAuto(Type, Boolean, Boolean, Boolean, CompatibilityLevel) + 0x169
   at ProtoBuf.Meta.RuntimeTypeModel.<GetServicesSlow>g__GetServicesImpl|88_0(RuntimeTypeModel, Type, CompatibilityLevel) + 0x6c
   at ProtoBuf.Meta.RuntimeTypeModel.GetServicesSlow(Type, CompatibilityLevel) + 0xb9
   at ProtoBuf.Meta.RuntimeTypeModel.GetSerializer[T]() + 0x87
   at ProtoBuf.Meta.TypeModel.TryGetSerializer[T](TypeModel) + 0x41
   at ProtoBuf.Meta.TypeModel.SerializeImpl[T](ProtoWriter.State&, T) + 0x59
   at Program.<Main>$(String[] args) + 0x179
```
This is the very first `Serializer.Serialize(buffer, request)` call — the crash happens before a
single byte is written. `ApplyDefaultBehaviourImpl` is protobuf-net inspecting `InferenceRequest`'s
`[ProtoMember]`-decorated properties (and their attributes' own properties, like `DefaultValue`) via
reflection to build the runtime schema; that reflection call is what throws.

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). Nothing about this failure is Windows-specific — the
crash is in fully-portable managed reflection code, so there is no reason to expect a different
outcome on `ios-arm64` or `linux-bionic-arm64`; if anything, iOS's *stricter* JIT-disabled
environment would surface the same reflection gap even faster.

## Verdict

**NOT VIABLE as used here** (default attribute-driven `RuntimeTypeModel`, no precompiled serializer).
protobuf-net supports an ahead-of-time "compiled serializer" path (`TypeModel.Compile()` /
`Serializer.PrepareSerializer` schema-first codegen, or its "protogen" `.proto`→partial-class tool)
that's designed to sidestep exactly this reflection-at-first-use pattern, but that's a materially
different, heavier integration (schema files, a build-time codegen step) than the naive
attribute-based usage gated here, and wasn't attempted — the naive path already gives an
unambiguous, reproducible answer.

**Fallback for a framed binary RPC transport:** `System.Text.Json` source generation
(`JsonSerializerContext`, already proven AOT-safe in `docs/nativeaot-mobile-caveats.md`) over a
simple length-prefixed frame, or Google's official `Google.Protobuf` with `protoc`-generated
partial classes (fully static codegen, no runtime reflection, the standard AOT-safe way to use
protobuf in .NET) if wire-format compatibility with other protobuf consumers is actually required.
