# ML.NET under NativeAOT — hostile, as expected, and hostile *before* NativeAOT even enters the picture

**Date:** 2026-07-05
**Outcome:** 🛑 **NOT VIABLE.** ML.NET fails in **two different ways** depending on how it's run:
under a plain (non-published, JIT) `dotnet run` of a project with `PublishAot=true` set, it fails
immediately with `PlatformNotSupportedException` for `System.Reflection.Emit.DynamicMethod`
*before NativeAOT compilation is even involved* — because `PublishAot=true` also flips the
`System.Reflection.Emit.IsSupported=false` runtime feature switch for **every** run configuration,
not just the published binary. The actual AOT-published, trimmed binary gets further (loads data,
starts training) but then crashes with a schema error, because trimming silently drops the
property-name metadata ML.NET's reflection-based schema building depends on.

**Why this doc exists:** this was the deliberately-expected failure in the batch — ML.NET's core
data-loading path (`LoadFromEnumerable<TRow>`) is built on `System.Reflection.Emit.DynamicMethod`
to generate per-property "peek" delegates at runtime, which is fundamentally incompatible with
NativeAOT's "no runtime codegen" model. This doc exists to prove that precisely, not to guess it.

## TL;DR

- ✅ `Microsoft.ML` **5.0.0** (latest stable) AOT-**publishes** for `win-x64` — with `IL2104`/`IL3053`
  trim/AOT-warning roll-ups against `Microsoft.ML.Data`, `Microsoft.ML.DataView`, and `Microsoft.ML.Core`.
- 🛑 **Failure mode A — plain `dotnet run` (JIT, not published) of the same project:** fails at the
  very first pipeline call, `mlContext.Data.LoadFromEnumerable(data)`, with
  `PlatformNotSupportedException: Dynamic code generation is not supported on this platform.` This
  happens with **no AOT publish involved at all** — `<PublishAot>true</PublishAot>` in the `.csproj`
  makes MSBuild write `System.Reflection.Emit.IsSupported: false` into `runtimeconfig.json` for
  *every* build configuration of that project, so even an ordinary JIT `dotnet run` inherits the
  "no dynamic codegen" feature switch. ML.NET's `GeneratePeek<TOwn,TRow,TValue>` calls
  `new DynamicMethod(...)` unconditionally — no fallback path.
- 🛑 **Failure mode B — the actual published, trimmed NativeAOT binary:** gets further (schema
  loading apparently succeeds via a different code path under full AOT), starts training, then
  crashes in `ColumnConcatenatingEstimator.GetOutputSchema` with
  `System.ArgumentOutOfRangeException: Could not find input column 'WordCount'` — the trimmed
  binary's schema lost the property **names** it needs to wire `Concatenate("Features", "WordCount", "HasQuestionMark")`
  to the actual `TrainingExample` properties. Reproduced identically on two separate runs
  (exit code `-1073740791` / `0xC0000409`, the standard NativeAOT fail-fast code, both times).
- **Conclusion either way: not viable.** Two independent code paths, two independent failure
  modes, same root cause (ML.NET's schema/row-mapping layer depends on unrestricted runtime
  reflection and codegen over the POCO row type).

## The spike

`spike/MlnetGate/MlnetGate.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.ML" Version="5.0.0" />
  </ItemGroup>
</Project>
```

`Program.cs`: 6 in-memory rows (`TrainingExample { WordCount, HasQuestionMark, Label }`), a
`Concatenate` + `SdcaLogisticRegression` binary-classification pipeline (about as small as ML.NET
training gets), `Fit`, then one `PredictionEngine.Predict` call.

**Command (build, no publish — this is what surfaced failure mode A):**
```powershell
dotnet build -c Release
dotnet run -c Release --no-build
```
**Output (verbatim, relevant frames):**
```
System.PlatformNotSupportedException: Dynamic code generation is not supported on this platform.
   at System.Reflection.Emit.AssemblyBuilder.ThrowDynamicCodeNotSupported()
   at System.Reflection.Emit.DynamicMethod.Init(...)
   at Microsoft.ML.ApiUtils.GeneratePeek[TOwn,TRow,TValue](PropertyInfo propertyInfo, OpCode assignmentOpCode)
   ...
   at Microsoft.ML.DataOperationsCatalog.LoadFromEnumerable[TRow](IEnumerable`1 data, SchemaDefinition schemaDefinition)
   at Program.<Main>$(String[] args) in ...\Program.cs:line 22
```
This is the single most useful finding in this doc: **`PublishAot=true` changes ordinary `dotnet
build`/`dotnet run` behavior for that project**, not just `dotnet publish`. Anyone iterating on
AOT-sensitive code with `dotnet run` for a fast inner loop needs to know a feature that "only shows
up in Release/publish" might actually show up immediately.

**Command (the real gate — NativeAOT publish):**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset workaround as the sibling gates.)

**Publish output (verbatim, trimmed):**
```
  Generating native code
...Microsoft.ML.Data.dll : warning IL2104: Assembly 'Microsoft.ML.Data' produced trim warnings.
...Microsoft.ML.Data.dll : warning IL3053: Assembly 'Microsoft.ML.Data' produced AOT analysis warnings.
...Microsoft.ML.DataView.dll : warning IL3053: Assembly 'Microsoft.ML.DataView' produced AOT analysis warnings.
...Microsoft.ML.Core.dll : warning IL3053: Assembly 'Microsoft.ML.Core' produced AOT analysis warnings.
...Microsoft.ML.Core.dll : warning IL2104: Assembly 'Microsoft.ML.Core' produced trim warnings.
...Microsoft.ML.DataView.dll : warning IL2104: Assembly 'Microsoft.ML.DataView' produced trim warnings.
  MlnetGate -> ...\publish\
```
Exit code **0** — publish itself succeeds despite the warnings.

**Run output of the published binary (verbatim, reproduced twice, exit code `-1073740791`):**
```
Training SDCA logistic regression on 6 in-memory rows...
Unhandled exception. System.ArgumentOutOfRangeException: Could not find input column 'WordCount' (Parameter 'inputSchema')
   at Microsoft.ML.Transforms.ColumnConcatenatingEstimator.CheckInputsAndMakeColumn(SchemaShape, String, String[]) + 0x4d7
   at Microsoft.ML.Transforms.ColumnConcatenatingEstimator.GetOutputSchema(SchemaShape) + 0xb5
   at Microsoft.ML.Data.EstimatorChain`1.GetOutputSchema(SchemaShape) + 0x22
   at Microsoft.ML.Data.EstimatorChain`1.Fit(IDataView) + 0x37
   at Program.<Main>$(String[] args) + 0x233
```
Note this is a **different** failure than mode A: `LoadFromEnumerable` itself didn't throw this
time (whatever code path NativeAOT's reflection stack takes here didn't hit the `DynamicMethod`
wall the same way the JIT run did), but the resulting `IDataView`'s schema is missing the column
**names** the pipeline needs — consistent with trimming removing property-name metadata that
ML.NET's reflection-based schema builder needs but that nothing in the trimmed app statically
references.

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). Both failure modes here are fundamental to ML.NET's
architecture (runtime `DynamicMethod` codegen; reflection-based schema/row mapping), not anything
Windows- or toolchain-specific, so there's no reason to expect iOS or Android to behave better —
if anything, iOS's stricter no-JIT enforcement would hit failure mode A even more certainly.

## Verdict

**NOT VIABLE / not worth chasing further.** This was the expected outcome for this gate, and the
spike confirms it precisely rather than by assumption: ML.NET's row-mapping and schema-building
layers are reflection/codegen-heavy in a way that has no AOT-safe fallback path in the default API
surface used here.

**Fallback:** hand-rolled math (the classifier used here — logistic regression over 2 features —
is a handful of multiply-adds and a sigmoid; not worth a heavyweight ML framework for this scale
of on-device model) or, if a real trained model is needed, export to **ONNX** and run it through
`Microsoft.ML.OnnxRuntime`, which this repo has already gated and proven AOT-safe end-to-end on iOS
(`onnx-nativeaot-ios-findings.md`). ONNX Runtime is the existing, working on-device inference path
for anything beyond simple hand-rolled math — there is no reason to introduce ML.NET as a second,
AOT-hostile inference stack alongside it.
