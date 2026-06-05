# Edge Vector Search (EVS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Manuals" tab whose offline semantic search runs entirely on-device in **Swift** (ONNX Runtime + Core ML / Apple Neural Engine) against a SQLite vector index compiled offline by a new **.NET 10 publisher** — a deliberate contrast to Phase 3's in-engine NativeAOT ONNX path.

**Architecture:** A **.NET publisher** console reuses the engine's `WordPieceTokenizer` + `OnnxTextEncoder` to embed Markdown maintenance-doc chunks into a SQLite `Chunks` table (float32[384] BLOBs). The **iOS client** embeds the query via a thin Objective-C ONNX Runtime wrapper (`EvsOrtSession`, Core ML EP), reads the index via the SQLite C API, and cosine-ranks with Accelerate/vDSP. EVS bypasses `dni.dylib` entirely, so **no framework rebuild** is required. The whole feature is **gated by Task 1**, an on-device spike proving the ORT+Core ML link works before anything heavy is built.

**Tech Stack:** .NET 10 / C# 14 (reuses `Microsoft.ML.OnnxRuntime` 1.20.1 + `System.Numerics.Tensors`, adds `Microsoft.Data.Sqlite`), the shared in-repo FP32 `all-MiniLM-L6-v2` model, ONNX Runtime iOS C/C++ xcframework (`onnxruntime-c` 1.20.1, Core ML EP), Swift 6 (strict concurrency), SwiftUI, SQLite3, Accelerate.

---

## File Structure

**.NET publisher — `core/DotnetNativeInterop.EdgeIndexPublisher/` (new):**
- `DotnetNativeInterop.EdgeIndexPublisher.csproj` — console; references the Engine; adds `Microsoft.Data.Sqlite`.
- `Frontmatter.cs` — `---`-fenced frontmatter reader (no YAML dependency).
- `MarkdownChunker.cs` — splits a doc body into one chunk per `##` section.
- `IndexWriter.cs` — writes the `Chunks` SQLite table (embedding as float32[384] LE BLOB).
- `Program.cs` — orchestrates: read corpus → parse → chunk → embed (engine encoder) → write `edge-index.db` + `edge-fixtures.json`.
- `corpus/*.md` — ~5 synthetic HVAC / enterprise-IT-hardware maintenance docs.
- `edge-index.db`, `edge-fixtures.json` — generated artifacts, committed (the `.db` un-ignored).

**iOS edge client — `ios/Shared/EdgeSearch/` (new):**
- `EvsOrt.h` / `EvsOrt.m` — Objective-C wrapper over the ORT C API (Core ML EP, mean-pool + L2-normalize → 384-d).
- `WordPieceTokenizer.swift` — Swift port of the engine tokenizer (must match C# ids).
- `EdgeModels.swift` — `EdgeChunk`, `EdgeSearchHit`, `EdgeSearchError`.
- `EdgeSearchEngine.swift` — ORT embed + SQLite C-API read + vDSP cosine (`@unchecked Sendable`).
- `EdgeSearchViewModel.swift` — `@MainActor`; query, filters, hits; lazy off-main load.
- `EdgeSearchView.swift` — search bar, facet filters, ranked results.
- `EdgeSearchHubView.swift` — the "Manuals" tab root.

**iOS tests — `ios/Tests/` (new unit-test target):**
- `EdgeSearchSpikeTests.swift` — Task 1 linkage gate (ORT + Core ML one inference).
- `EdgeSearchAgreementTests.swift` — Task 9 tokenizer parity + ranking + vector delta.
- `TestsBridging.h` — imports `EvsOrt.h` for the test target.

**iOS (modified, additive):**
- `ios/project.yml` — add `onnxruntime.xcframework` dep, bundle `edge-index.db` + `edge-fixtures.json`, add the unit-test target + scheme.
- `ios/Shared/RootTabView.swift` — add the "Manuals" tab (8th).
- `ios/ScreenshotTests/ScreenshotTests.swift` — capture the new tab.
- `ios/Frameworks/onnxruntime.xcframework` — vendored via Git LFS.
- `.gitignore` — un-ignore `onnxruntime.xcframework` + `edge-index.db`.
- `.gitattributes` — LFS rule for the xcframework binary.

**Docs / solution:**
- `DotnetNativeInterop.slnx` — add the publisher project.
- `docs/onnx-coreml-edge-findings.md` — spike outcome (success writeup, or failure proof if the gate fails).

---

### Task 1: GATE — vendor ONNX Runtime + prove Core ML inference on device

> **This is a hard gate.** If any step fails, **STOP**, jump to Task 14 (write `docs/onnx-coreml-edge-findings.md` with the exact error), gate the "Manuals" tab to a graceful "unavailable" card, and report BLOCKED-BY-GATE. Steps 1–6 run on the **Mac mini** (`ssh steve-mac-mini`; see `docs/ios-build-deploy-runbook.md`). The model is already bundled by Phase 3 — this task needs only the framework, the wrapper, and a test target.

**Files:**
- Create: `ios/Frameworks/onnxruntime.xcframework` (vendored)
- Create: `ios/Shared/EdgeSearch/EvsOrt.h`, `ios/Shared/EdgeSearch/EvsOrt.m`
- Create: `ios/Tests/TestsBridging.h`, `ios/Tests/EdgeSearchSpikeTests.swift`
- Modify: `ios/project.yml`, `.gitignore`, `.gitattributes`

- [ ] **Step 1: Fetch the prebuilt ORT C/C++ xcframework (full build — includes Core ML EP)**

On the Mac (CocoaPods only used to *download* the artifact; we do not integrate Pods):
```bash
command -v pod >/dev/null || sudo gem install cocoapods
mkdir -p /tmp/ort-fetch && cd /tmp/ort-fetch
cat > Podfile <<'EOF'
platform :ios, '17.0'
target 'x' do
  pod 'onnxruntime-c', '1.20.1'   # FULL build (Core ML EP). NOT onnxruntime-mobile-c.
end
EOF
pod install >/dev/null 2>&1 || pod install
XCF="$(find Pods -maxdepth 4 -name 'onnxruntime.xcframework' -type d | head -1)"
echo "found: $XCF"
ls "$XCF"
```
Expected: `$XCF` resolves; `ls` shows `Info.plist` and one or more slice dirs (e.g. `ios-arm64`, `ios-arm64_x86_64-simulator`), each containing `onnxruntime.framework/` with an `onnxruntime` binary + `Headers/`.

- [ ] **Step 2: Place it in the repo and confirm the header import path + Core ML factory symbol**

```bash
cd ~/<repo>        # the synced feat/edge-vector-search checkout on the Mac
cp -R "$XCF" ios/Frameworks/onnxruntime.xcframework
# Confirm the two headers the wrapper needs exist (note their relative path under Headers/):
find ios/Frameworks/onnxruntime.xcframework -name 'onnxruntime_c_api.h' -o -name 'coreml_provider_factory.h'
# Confirm the Core ML append symbol is present in the binary:
SLICE="$(find ios/Frameworks/onnxruntime.xcframework -name onnxruntime -path '*ios-arm64*' ! -path '*simulator*' | head -1)"
nm -gU "$SLICE" | grep -i CoreML | head
```
Expected: both headers found; `nm` lists `OrtSessionOptionsAppendExecutionProvider_CoreML`. If the headers import as `<onnxruntime/onnxruntime_c_api.h>` confirm by checking the framework `Modules/module.modulemap`; adjust the `#import` in Step 3 to match what the modulemap exposes (commonly `#import <onnxruntime/onnxruntime_c_api.h>`).

- [ ] **Step 3: Write the Objective-C ORT wrapper**

Create `ios/Shared/EdgeSearch/EvsOrt.h`:
```objc
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Minimal Objective-C wrapper over the ONNX Runtime C API with the Core ML execution provider, exposing
/// a single all-MiniLM embed call. Lives in-tree (not a pod) so the app needs only the vendored
/// onnxruntime.xcframework. Mean-pools over the attention mask and L2-normalizes — identical math to the
/// engine's C# OnnxTextEncoder, so query/corpus vectors are comparable across runtimes.
@interface EvsOrtSession : NSObject

/// Creates a session for `modelPath` with the Core ML EP enabled. Returns nil + `error` on failure.
- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error;

/// Embeds the tokenized input (length `length`) into `out` (caller supplies 384 floats). NO on failure.
- (BOOL)embedInputIds:(const int64_t *)ids
        attentionMask:(const int64_t *)mask
               length:(NSInteger)length
                  out:(float *)out
                error:(NSError **)error;
@end

NS_ASSUME_NONNULL_END
```

Create `ios/Shared/EdgeSearch/EvsOrt.m` (adjust the two `#import` lines if Step 2 showed a different path):
```objc
#import "EvsOrt.h"
#import <onnxruntime/onnxruntime_c_api.h>
#import <onnxruntime/coreml_provider_factory.h>
#import <math.h>

@implementation EvsOrtSession {
    const OrtApi *_ort;
    OrtEnv *_env;
    OrtSession *_session;
    OrtMemoryInfo *_mem;
    NSString *_outputName;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath error:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    _ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);

    if ([self fail:_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "evs", &_env) error:error]) return nil;

    OrtSessionOptions *opts = NULL;
    if ([self fail:_ort->CreateSessionOptions(&opts) error:error]) return nil;
    // flags 0 = default Core ML behaviour (ANE/GPU/CPU as available).
    if ([self fail:OrtSessionOptionsAppendExecutionProvider_CoreML(opts, 0) error:error]) {
        _ort->ReleaseSessionOptions(opts); return nil;
    }
    OrtStatus *st = _ort->CreateSession(_env, modelPath.UTF8String, opts, &_session);
    _ort->ReleaseSessionOptions(opts);
    if ([self fail:st error:error]) return nil;

    if ([self fail:_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &_mem) error:error]) return nil;

    OrtAllocator *alloc = NULL;
    _ort->GetAllocatorWithDefaultOptions(&alloc);
    char *outName = NULL;
    if ([self fail:_ort->SessionGetOutputName(_session, 0, alloc, &outName) error:error]) return nil;
    _outputName = [NSString stringWithUTF8String:outName];
    alloc->Free(alloc, outName);
    return self;
}

- (BOOL)embedInputIds:(const int64_t *)ids attentionMask:(const int64_t *)mask
               length:(NSInteger)length out:(float *)out error:(NSError **)error {
    int64_t shape[2] = {1, (int64_t)length};
    size_t bytes = sizeof(int64_t) * (size_t)length;
    int64_t *types = (int64_t *)calloc((size_t)length, sizeof(int64_t)); // token_type_ids = 0
    OrtValue *idsVal = NULL, *maskVal = NULL, *typeVal = NULL, *outVal = NULL;

    BOOL bad = [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, (void *)ids, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &idsVal) error:error]
        || [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, (void *)mask, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &maskVal) error:error]
        || [self fail:_ort->CreateTensorWithDataAsOrtValue(_mem, types, bytes, shape, 2,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &typeVal) error:error];
    if (bad) { free(types); return NO; }

    const char *inNames[3] = {"input_ids", "attention_mask", "token_type_ids"};
    const OrtValue *inVals[3] = {idsVal, maskVal, typeVal};
    const char *outNames[1] = {_outputName.UTF8String};
    OrtStatus *st = _ort->Run(_session, NULL, inNames, inVals, 3, outNames, 1, &outVal);
    _ort->ReleaseValue(idsVal); _ort->ReleaseValue(maskVal); _ort->ReleaseValue(typeVal); free(types);
    if ([self fail:st error:error]) return NO;

    float *hidden = NULL; // [1, length, 384]
    if ([self fail:_ort->GetTensorMutableData(outVal, (void **)&hidden) error:error]) {
        _ort->ReleaseValue(outVal); return NO;
    }
    const NSInteger dim = 384;
    for (NSInteger d = 0; d < dim; d++) out[d] = 0.0f;
    int64_t count = 0;
    for (NSInteger t = 0; t < length; t++) {
        if (mask[t] == 0) continue;
        count++;
        const float *row = hidden + (t * dim);
        for (NSInteger d = 0; d < dim; d++) out[d] += row[d];
    }
    if (count > 0) for (NSInteger d = 0; d < dim; d++) out[d] /= (float)count;
    float norm = 0.0f; for (NSInteger d = 0; d < dim; d++) norm += out[d] * out[d];
    norm = sqrtf(norm);
    if (norm > 0) for (NSInteger d = 0; d < dim; d++) out[d] /= norm;

    _ort->ReleaseValue(outVal);
    return YES;
}

// Returns YES (and fills *error) when `status` is a real error; releases it. NULL status → NO.
- (BOOL)fail:(OrtStatus *)status error:(NSError **)error {
    if (status == NULL) return NO;
    if (error) *error = [NSError errorWithDomain:@"EvsOrt" code:1
        userInfo:@{NSLocalizedDescriptionKey: @(_ort->GetErrorMessage(status))}];
    _ort->ReleaseStatus(status);
    return YES;
}
@end
```

- [ ] **Step 4: Un-ignore the framework + index, and LFS-track the binary**

In `.gitignore`, append at the end (after the existing ignores so these negations win):
```gitignore

## EVS: commit the vendored ONNX Runtime xcframework + the prebuilt edge index
!ios/Frameworks/onnxruntime.xcframework/
!ios/Frameworks/onnxruntime.xcframework/**
!core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db
```
In `.gitattributes`, append (the binary slices are large; headers/plists stay text). The Mach-O binaries are named `onnxruntime`; if Step 2 showed static `.a` instead, add `*.a` too:
```gitattributes
ios/Frameworks/onnxruntime.xcframework/**/onnxruntime filter=lfs diff=lfs merge=lfs -text
```
Then:
```bash
git add .gitattributes .gitignore
git add ios/Frameworks/onnxruntime.xcframework
git lfs ls-files | grep onnxruntime | head   # expect the binary slices listed as LFS
git diff --cached --stat | tail -3
```
Expected: `git lfs ls-files` lists the `onnxruntime` binaries; the headers/Info.plist are added as normal (small) files.

- [ ] **Step 5: Add the unit-test target + the linkage spike test**

Create `ios/Tests/TestsBridging.h`:
```objc
#import "../Shared/EdgeSearch/EvsOrt.h"
```

Create `ios/Tests/EdgeSearchSpikeTests.swift`:
```swift
import XCTest

/// GATE (Task 1): proves the vendored ONNX Runtime xcframework links and the Core ML EP runs one
/// inference of the bundled all-MiniLM model on device/simulator. Uses hardcoded ids ([CLS] hello world
/// [SEP]); the real tokenizer/agreement checks come later (EdgeSearchAgreementTests).
final class EdgeSearchSpikeTests: XCTestCase {
    func testOrtCoreMLLinksAndRunsOneInference() throws {
        let modelPath = try XCTUnwrap(
            Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx"),
            "model.onnx must be bundled (Phase 3 assets)")
        let session = try EvsOrtSession(modelPath: modelPath)

        var out = [Float](repeating: 0, count: 384)
        let ids: [Int64] = [101, 7592, 2088, 102]      // [CLS] hello world [SEP]
        let mask: [Int64] = [1, 1, 1, 1]
        try session.embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &out)

        let norm = sqrt(out.reduce(0) { $0 + $1 * $1 })
        XCTAssertEqual(norm, 1.0, accuracy: 1e-3, "embedding should be L2-normalized")
        XCTAssertTrue(out.contains { $0 != 0 }, "embedding must be non-zero")
    }
}
```

In `ios/project.yml`, add the framework dependency to the `DotnetNativeInteropUnified` target's `dependencies:` list (alongside `dni.xcframework`):
```yaml
      - framework: Frameworks/onnxruntime.xcframework
        embed: true
        codeSign: true
```
Add the unit-test target under `targets:`:
```yaml
  DotnetNativeInteropTests:
    type: bundle.unit-test
    platform: iOS
    deploymentTarget: "17.0"
    sources:
      - path: Tests
    dependencies:
      - target: DotnetNativeInteropUnified
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.dotnetnativeinterop.tests
        GENERATE_INFOPLIST_FILE: YES
        SWIFT_OBJC_BRIDGING_HEADER: Tests/TestsBridging.h
        TEST_HOST: "$(BUILT_PRODUCTS_DIR)/DotnetNativeInteropUnified.app/DotnetNativeInteropUnified"
        BUNDLE_LOADER: "$(TEST_HOST)"
```
And add it to the `DotnetNativeInteropUnified` scheme's test targets:
```yaml
    test:
      targets:
        - DotnetNativeInteropScreenshots
        - DotnetNativeInteropTests
```

- [ ] **Step 6: Build + run the gate on the Simulator**

```bash
cd ios && xcodegen generate
xcodebuild test \
  -project DotnetNativeInteropApp.xcodeproj \
  -scheme DotnetNativeInteropUnified \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
  -only-testing:DotnetNativeInteropTests/EdgeSearchSpikeTests \
  CODE_SIGNING_ALLOWED=NO
```
Expected: `Test Suite 'EdgeSearchSpikeTests' passed`. (The Core ML EP runs on the simulator's CPU/GPU; on-device ANE is exercised in Task 15.)

- [ ] **Step 7: GATE decision**

- **PASS →** commit and proceed to Task 2:
```bash
git add ios/Shared/EdgeSearch/EvsOrt.h ios/Shared/EdgeSearch/EvsOrt.m \
        ios/Tests/TestsBridging.h ios/Tests/EdgeSearchSpikeTests.swift ios/project.yml
git commit -m "feat: vendor ONNX Runtime xcframework + prove Core ML inference (EVS gate)"
```
- **FAIL** (won't link, Core ML symbol missing, EP init error, or inference crash) → **STOP.** Capture the exact `xcodebuild`/runtime error verbatim, go to **Task 14** and write the failure-proof findings doc, gate the tab off, and report BLOCKED-BY-GATE.

---

### Task 2: Publisher project + frontmatter parser (.NET, Windows)

> Tasks 2–5 run on the Windows dev box (where .NET + the model live) and are TDD'd with a throwaway probe console at `%TEMP%\dni-evs-probe`, mirroring the Phase 3 pattern.

**Files:**
- Create: `core/DotnetNativeInterop.EdgeIndexPublisher/DotnetNativeInterop.EdgeIndexPublisher.csproj`
- Create: `core/DotnetNativeInterop.EdgeIndexPublisher/Frontmatter.cs`
- Modify: `DotnetNativeInterop.slnx`
- Probe: `%TEMP%\dni-evs-probe\Program.cs`

- [ ] **Step 1: Create the publisher project + add it to the solution**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/DotnetNativeInterop.EdgeIndexPublisher.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">

  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <RootNamespace>DotnetNativeInterop.EdgeIndexPublisher</RootNamespace>
    <AssemblyName>DotnetNativeInterop.EdgeIndexPublisher</AssemblyName>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <!-- A build-time desktop tool, not the AOT engine: no IsAotCompatible constraint. -->
  </PropertyGroup>

  <ItemGroup>
    <ProjectReference Include="../DotnetNativeInterop.Engine/DotnetNativeInterop.Engine.csproj" />
  </ItemGroup>

  <ItemGroup>
    <PackageReference Include="Microsoft.Data.Sqlite" Version="10.0.0" />
  </ItemGroup>

</Project>
```

In `DotnetNativeInterop.slnx`, add the project inside the existing `/core/` folder (after the NativeBridge line):
```xml
    <Project Path="core/DotnetNativeInterop.EdgeIndexPublisher/DotnetNativeInterop.EdgeIndexPublisher.csproj" />
```

- [ ] **Step 2: Write the failing probe**

Create `%TEMP%\dni-evs-probe\dni-evs-probe.csproj`:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
  </PropertyGroup>
  <ItemGroup>
    <ProjectReference Include="C:\Users\steve\projects\dotnet-ios-android-poc-native-frontend\core\DotnetNativeInterop.EdgeIndexPublisher\DotnetNativeInterop.EdgeIndexPublisher.csproj" />
  </ItemGroup>
</Project>
```
Create `%TEMP%\dni-evs-probe\Program.cs`:
```csharp
using DotnetNativeInterop.EdgeIndexPublisher;

var md = """
---
document_id: hvac-001
title: Rooftop Unit Won't Start
error_codes: [E101, E102]
tools_required:
  - multimeter
  - 5/16 nut driver
---

## Symptom
The compressor does not energize on a call for cooling.

## Fix
Check the contactor coil.
""";

var (front, body) = Frontmatter.Parse(md);
if (front.DocumentId != "hvac-001") throw new Exception($"FAIL doc id: {front.DocumentId}");
if (front.Title != "Rooftop Unit Won't Start") throw new Exception($"FAIL title: {front.Title}");
if (front.ErrorCodes is not ["E101", "E102"]) throw new Exception($"FAIL codes: {string.Join(',', front.ErrorCodes)}");
if (front.ToolsRequired is not ["multimeter", "5/16 nut driver"]) throw new Exception($"FAIL tools: {string.Join('|', front.ToolsRequired)}");
if (!body.TrimStart().StartsWith("## Symptom")) throw new Exception($"FAIL body: {body[..20]}");
Console.WriteLine("PASS: frontmatter parsed (inline list + block list)");
```
Run: `dotnet run --project "$env:TEMP\dni-evs-probe"` → FAIL (`Frontmatter` not found).

- [ ] **Step 3: Write the implementation**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/Frontmatter.cs`:
```csharp
namespace DotnetNativeInterop.EdgeIndexPublisher;

/// Promoted frontmatter fields for one maintenance doc.
public sealed record DocFrontmatter(string DocumentId, string Title, string[] ErrorCodes, string[] ToolsRequired);

/// Minimal `---`-fenced frontmatter reader. Supports `key: value`, inline lists `key: [a, b]`, and
/// block lists (`key:` then `  - item` lines). No external YAML dependency. Unknown keys are ignored.
public static class Frontmatter
{
    public static (DocFrontmatter Front, string Body) Parse(string markdown)
    {
        var text = markdown.Replace("\r\n", "\n").TrimStart('\n');
        if (!text.StartsWith("---\n"))
        {
            return (new DocFrontmatter("", "", [], []), markdown);
        }

        var end = text.IndexOf("\n---", 4, StringComparison.Ordinal);
        if (end < 0)
        {
            return (new DocFrontmatter("", "", [], []), markdown);
        }

        var header = text[4..end];
        var body = text[(end + 4)..].TrimStart('\n');

        string id = "", title = "";
        string[] codes = [], tools = [];
        var lines = header.Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            if (line.Length == 0 || line[0] is ' ' or '-')
            {
                continue; // block-list continuation handled when its key is seen
            }

            var colon = line.IndexOf(':');
            if (colon < 0)
            {
                continue;
            }

            var key = line[..colon].Trim();
            var value = line[(colon + 1)..].Trim();
            switch (key)
            {
                case "document_id": id = Unquote(value); break;
                case "title": title = Unquote(value); break;
                case "error_codes": codes = ReadList(value, lines, i); break;
                case "tools_required": tools = ReadList(value, lines, i); break;
            }
        }

        return (new DocFrontmatter(id, title, codes, tools), body);
    }

    // Inline (`[a, b]`) or block list (`  - item` on the following lines).
    private static string[] ReadList(string inlineValue, string[] lines, int keyIndex)
    {
        if (inlineValue.StartsWith('[') && inlineValue.EndsWith(']'))
        {
            return inlineValue[1..^1].Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Select(Unquote).ToArray();
        }

        var items = new List<string>();
        for (var j = keyIndex + 1; j < lines.Length; j++)
        {
            var t = lines[j].TrimStart();
            if (!t.StartsWith("- "))
            {
                break;
            }

            items.Add(Unquote(t[2..].Trim()));
        }

        return items.ToArray();
    }

    private static string Unquote(string s) =>
        s.Length >= 2 && (s[0] == '"' && s[^1] == '"' || s[0] == '\'' && s[^1] == '\'') ? s[1..^1] : s;
}
```
Run: `dotnet run --project "$env:TEMP\dni-evs-probe"` → Expected `PASS: frontmatter parsed (inline list + block list)`.

- [ ] **Step 4: Commit**

```powershell
git add core/DotnetNativeInterop.EdgeIndexPublisher/DotnetNativeInterop.EdgeIndexPublisher.csproj `
        core/DotnetNativeInterop.EdgeIndexPublisher/Frontmatter.cs DotnetNativeInterop.slnx
git commit -m "feat: add EVS publisher project + frontmatter parser"
```

---

### Task 3: Markdown `##` chunker (.NET, Windows)

**Files:**
- Create: `core/DotnetNativeInterop.EdgeIndexPublisher/MarkdownChunker.cs`
- Probe: `%TEMP%\dni-evs-probe\Program.cs`

- [ ] **Step 1: Write the failing probe**

Overwrite `%TEMP%\dni-evs-probe\Program.cs`:
```csharp
using DotnetNativeInterop.EdgeIndexPublisher;

var body = """
Intro text before any heading is ignored.

## Symptom
Compressor does not energize.
More symptom detail.

## Fix
Replace the contactor.
### Sub-step
Torque to spec.
""";

var chunks = MarkdownChunker.Chunk(body);
if (chunks.Count != 2) throw new Exception($"FAIL count: {chunks.Count}");
if (chunks[0].SectionTitle != "Symptom") throw new Exception($"FAIL title0: {chunks[0].SectionTitle}");
if (!chunks[0].Body.Contains("does not energize")) throw new Exception("FAIL body0");
if (!chunks[1].Body.Contains("Torque to spec")) throw new Exception("FAIL: ### should stay within its ##");
Console.WriteLine($"PASS: {chunks.Count} chunks, titles=[{string.Join(", ", chunks.Select(c => c.SectionTitle))}]");
```
Run: `dotnet run --project "$env:TEMP\dni-evs-probe"` → FAIL (`MarkdownChunker` not found).

- [ ] **Step 2: Write the implementation**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/MarkdownChunker.cs`:
```csharp
namespace DotnetNativeInterop.EdgeIndexPublisher;

/// One indexed section: the `##` heading text and the body beneath it (including deeper `###` content).
public sealed record DocChunk(string SectionTitle, string Body);

/// Splits a Markdown body into one chunk per `##` section. Text before the first `##` is dropped; `###`
/// and deeper headings stay within their enclosing `##` chunk.
public static class MarkdownChunker
{
    public static IReadOnlyList<DocChunk> Chunk(string body)
    {
        var chunks = new List<DocChunk>();
        string? title = null;
        var sb = new System.Text.StringBuilder();

        void Flush()
        {
            if (title is not null)
            {
                chunks.Add(new DocChunk(title, sb.ToString().Trim()));
            }

            sb.Clear();
        }

        foreach (var raw in body.Replace("\r\n", "\n").Split('\n'))
        {
            var line = raw;
            if (line.StartsWith("## ") && !line.StartsWith("### "))
            {
                Flush();
                title = line[3..].Trim();
            }
            else if (title is not null)
            {
                sb.Append(line).Append('\n');
            }
        }

        Flush();
        return chunks;
    }
}
```
Run: `dotnet run --project "$env:TEMP\dni-evs-probe"` → Expected `PASS: 2 chunks, titles=[Symptom, Fix]`.

- [ ] **Step 3: Commit**

```powershell
git add core/DotnetNativeInterop.EdgeIndexPublisher/MarkdownChunker.cs
git commit -m "feat: add EVS markdown ## chunker"
```

---

### Task 4: Sample corpus (.NET, Windows)

**Files:** Create five `.md` files under `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/`.

- [ ] **Step 1: Create the corpus directory + docs**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/hvac-rooftop.md`:
```markdown
---
document_id: hvac-001
title: Rooftop Unit Won't Start
error_codes: [E101, E102]
tools_required: [multimeter, "5/16 nut driver"]
---

## Symptom
On a call for cooling the compressor does not energize and the rooftop unit stays idle. The contactor may not be pulling in, or the unit may be in a lockout after repeated faults.

## Diagnosis
Measure 24V across the contactor coil during a cooling call. No voltage points at the thermostat or control board; voltage present but no pull-in points at a failed contactor coil.

## Fix
Replace the contactor if the coil is open. Reset the lockout by cycling power. Verify the compressor draws within nameplate amperage after restart.
```

Create `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/hvac-airflow.md`:
```markdown
---
document_id: hvac-002
title: Weak Airflow and Frozen Coil
error_codes: [E205]
tools_required: [flashlight, fin comb]
---

## Symptom
Reduced airflow at the registers and ice forming on the evaporator coil. Low airflow starves the coil and drops it below freezing.

## Diagnosis
Inspect the air filter and blower wheel. A clogged filter or dirty wheel restricts airflow; bent fins reduce coil exchange.

## Fix
Replace the filter, clean the blower wheel, and straighten coil fins with a fin comb. Let the coil thaw fully before restarting.
```

Create `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/it-server-psu.md`:
```markdown
---
document_id: it-001
title: Server Power Supply Failure
error_codes: [PSU-FAIL, AMBER-LED]
tools_required: [esd strap, Phillips #2]
---

## Symptom
The server does not power on and the power-supply LED is amber rather than green. A redundant unit may have failed while the second carries the load.

## Diagnosis
Check the LED state and the BMC event log. Amber typically indicates an input or internal fault; reseat the unit and confirm the input feed.

## Fix
Replace the failed power supply with a matching model, wearing an ESD strap. Confirm both units report green and the BMC clears the alert.
```

Create `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/it-overheat.md`:
```markdown
---
document_id: it-002
title: Server Thermal Shutdown
error_codes: [THERM-TRIP]
tools_required: [esd strap, thermal paste]
---

## Symptom
The server shuts down under load and the BMC logs a thermal trip. CPU temperature crossed the critical threshold and firmware forced a shutdown to protect the hardware.

## Diagnosis
Verify fan operation and airflow path. Failed fans, dust buildup, or dried thermal paste raise CPU temperature beyond limits.

## Fix
Replace failed fans, clear dust from intakes and heatsinks, and reapply thermal paste to the CPU. Confirm temperatures stay nominal under a stress test.
```

Create `core/DotnetNativeInterop.EdgeIndexPublisher/corpus/it-network.md`:
```markdown
---
document_id: it-003
title: Network Port Link Down
error_codes: [LINK-DOWN]
tools_required: [cable tester, optical cleaner]
---

## Symptom
A switch port shows link down and the attached server loses connectivity. The port LED is dark or flapping between up and down states.

## Diagnosis
Test the cable and check the transceiver. A bad patch cable, dirty fiber end-face, or unseated SFP causes an unstable or absent link.

## Fix
Replace the cable or clean the fiber end-face, reseat the transceiver, and confirm the port comes up at the negotiated speed without flapping.
```

- [ ] **Step 2: Commit**

```powershell
git add core/DotnetNativeInterop.EdgeIndexPublisher/corpus/
git commit -m "feat: add EVS sample maintenance corpus (HVAC + IT hardware)"
```

---

### Task 5: SQLite writer + pipeline; build & commit the index (.NET, Windows)

**Files:**
- Create: `core/DotnetNativeInterop.EdgeIndexPublisher/IndexWriter.cs`, `core/DotnetNativeInterop.EdgeIndexPublisher/Program.cs`
- Generated: `core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db`, `…/edge-fixtures.json`

- [ ] **Step 1: Write the SQLite index writer**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/IndexWriter.cs`:
```csharp
using Microsoft.Data.Sqlite;

namespace DotnetNativeInterop.EdgeIndexPublisher;

/// One row to index: identifiers, display text, the 384-d embedding, and the filter metadata.
public sealed record IndexRow(
    string ChunkId, string DocumentId, string SectionTitle, string ContentText,
    float[] Embedding, string[] ErrorCodes, string[] ToolsRequired);

/// Writes the `Chunks` table. Embedding is stored as a raw little-endian float32[384] BLOB; metadata is
/// JSON ({ "error_codes":[…], "tools_required":[…] }) for the client's filter facets.
public static class IndexWriter
{
    public static void Write(string dbPath, IReadOnlyList<IndexRow> rows)
    {
        if (File.Exists(dbPath))
        {
            File.Delete(dbPath);
        }

        using var conn = new SqliteConnection($"Data Source={dbPath}");
        conn.Open();

        using (var create = conn.CreateCommand())
        {
            create.CommandText =
                """
                CREATE TABLE Chunks (
                  ChunkId       TEXT PRIMARY KEY,
                  DocumentId    TEXT NOT NULL,
                  SectionTitle  TEXT NOT NULL,
                  ContentText   TEXT NOT NULL,
                  Embedding     BLOB NOT NULL,
                  Metadata      TEXT NOT NULL
                );
                """;
            create.ExecuteNonQuery();
        }

        using var tx = conn.BeginTransaction();
        foreach (var row in rows)
        {
            var blob = new byte[row.Embedding.Length * sizeof(float)];
            Buffer.BlockCopy(row.Embedding, 0, blob, 0, blob.Length);
            var meta = System.Text.Json.JsonSerializer.Serialize(new
            {
                error_codes = row.ErrorCodes,
                tools_required = row.ToolsRequired,
            });

            using var cmd = conn.CreateCommand();
            cmd.CommandText =
                "INSERT INTO Chunks (ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata) " +
                "VALUES ($id, $doc, $title, $text, $emb, $meta)";
            cmd.Parameters.AddWithValue("$id", row.ChunkId);
            cmd.Parameters.AddWithValue("$doc", row.DocumentId);
            cmd.Parameters.AddWithValue("$title", row.SectionTitle);
            cmd.Parameters.AddWithValue("$text", row.ContentText);
            cmd.Parameters.AddWithValue("$emb", blob);
            cmd.Parameters.AddWithValue("$meta", meta);
            cmd.ExecuteNonQuery();
        }

        tx.Commit();
    }
}
```

- [ ] **Step 2: Write the pipeline entry point**

Create `core/DotnetNativeInterop.EdgeIndexPublisher/Program.cs`:
```csharp
using System.Text.Json;
using DotnetNativeInterop.Engine;
using DotnetNativeInterop.EdgeIndexPublisher;

// Defaults assume `dotnet run` from the repo root. Override via args: [corpusDir] [outputDb] [assetsDir].
var corpusDir = args.Length > 0 ? args[0]
    : Path.Combine("core", "DotnetNativeInterop.EdgeIndexPublisher", "corpus");
var outputDb = args.Length > 1 ? args[1]
    : Path.Combine("core", "DotnetNativeInterop.EdgeIndexPublisher", "edge-index.db");
var assetsDir = args.Length > 2 ? args[2]
    : Path.Combine("core", "DotnetNativeInterop.Engine", "Ai", "assets");

var vocabPath = Path.Combine(assetsDir, "vocab.txt");
var modelPath = Path.Combine(assetsDir, "model.onnx");
Console.WriteLine($"corpus={corpusDir}  out={outputDb}  model={modelPath}");

var tokenizer = new WordPieceTokenizer(File.ReadAllLines(vocabPath));
using var encoder = new OnnxTextEncoder(modelPath, tokenizer);

var rows = new List<IndexRow>();
foreach (var md in Directory.EnumerateFiles(corpusDir, "*.md", SearchOption.AllDirectories).OrderBy(p => p))
{
    var (front, body) = Frontmatter.Parse(File.ReadAllText(md));
    var chunks = MarkdownChunker.Chunk(body);
    for (var i = 0; i < chunks.Count; i++)
    {
        var c = chunks[i];
        var embedded = encoder.Encode($"{c.SectionTitle}. {c.Body}");
        rows.Add(new IndexRow(
            ChunkId: $"{front.DocumentId}#{i}",
            DocumentId: front.DocumentId,
            SectionTitle: c.SectionTitle,
            ContentText: c.Body,
            Embedding: embedded,
            ErrorCodes: front.ErrorCodes,
            ToolsRequired: front.ToolsRequired));
    }
}

IndexWriter.Write(outputDb, rows);
Console.WriteLine($"Wrote {rows.Count} chunks to {outputDb}");

// Fixtures for the on-device cross-runtime agreement test (Task 9).
const string sampleQuery = "compressor won't start";
var (ids, _) = tokenizer.Encode(sampleQuery);
var queryVec = encoder.Encode(sampleQuery);
var ranked = rows
    .Select(r => (r.ChunkId, Score: System.Numerics.Tensors.TensorPrimitives.CosineSimilarity(queryVec, r.Embedding)))
    .OrderByDescending(x => x.Score).First();
var fixturesPath = Path.Combine(Path.GetDirectoryName(outputDb)!, "edge-fixtures.json");
File.WriteAllText(fixturesPath, JsonSerializer.Serialize(new
{
    query = sampleQuery,
    ids = ids,
    queryVector = queryVec,
    expectedTopChunkId = ranked.ChunkId,
}, new JsonSerializerOptions { WriteIndented = false }));
Console.WriteLine($"Wrote fixtures ({ranked.ChunkId} top) to {fixturesPath}");
```

- [ ] **Step 3: Build the solution (verifies the new project compiles)**

Run: `dotnet build DotnetNativeInterop.slnx -c Release`
Expected: `Build succeeded` (the 2 pre-existing CS1668 env warnings aside). If the engine's `model.onnx` is an LFS pointer locally, ensure `git lfs pull` has materialized it first.

- [ ] **Step 4: Run the publisher to generate the index + fixtures**

Run from the repo root: `dotnet run --project core/DotnetNativeInterop.EdgeIndexPublisher -c Release`
Expected: `Wrote 15 chunks to …\edge-index.db` (5 docs × 3 sections) and `Wrote fixtures (hvac-001#0 top) to …\edge-fixtures.json`. Confirm the DB:
```powershell
(Get-Item core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db).Length  # > 0
```

- [ ] **Step 5: Commit the writer, pipeline, and generated artifacts**

`edge-index.db` is normally gitignored by `*.db`; Task 1 added the negation, so it is committable now.
```powershell
git add core/DotnetNativeInterop.EdgeIndexPublisher/IndexWriter.cs `
        core/DotnetNativeInterop.EdgeIndexPublisher/Program.cs `
        core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db `
        core/DotnetNativeInterop.EdgeIndexPublisher/edge-fixtures.json
git commit -m "feat: add EVS SQLite index writer + pipeline; build sample index"
```

---

### Task 6: Swift WordPiece tokenizer port (iOS)

**Files:** Create `ios/Shared/EdgeSearch/WordPieceTokenizer.swift`.

- [ ] **Step 1: Write the port**

Create `ios/Shared/EdgeSearch/WordPieceTokenizer.swift`:
```swift
import Foundation

/// Swift port of the engine's BERT WordPiece tokenizer (uncased). MUST produce ids identical to the C#
/// `WordPieceTokenizer` so the Swift query vector is comparable to the publisher's corpus vectors.
/// Verified on device by EdgeSearchAgreementTests.
struct WordPieceTokenizer {
    private let vocab: [String: Int]
    let clsId: Int
    let sepId: Int
    private let unkId: Int
    private let padId: Int

    init(vocabLines: [String]) {
        var v = [String: Int](minimumCapacity: vocabLines.count)
        for (i, line) in vocabLines.enumerated() where !line.isEmpty { v[line] = i }
        vocab = v
        clsId = v["[CLS]"] ?? 101
        sepId = v["[SEP]"] ?? 102
        unkId = v["[UNK]"] ?? 100
        padId = v["[PAD]"] ?? 0
    }

    /// Encodes into padded ids + mask of length `maxLen` (default 64), matching the engine.
    func encode(_ text: String, maxLen: Int = 64) -> (ids: [Int64], mask: [Int64]) {
        var pieces: [Int] = [clsId]
        for word in basicTokenize(text) {
            for piece in wordPiece(word) {
                if pieces.count >= maxLen - 1 { break }
                pieces.append(piece)
            }
        }
        pieces.append(sepId)

        var ids = [Int64](repeating: Int64(padId), count: maxLen)
        var mask = [Int64](repeating: 0, count: maxLen)
        for i in 0..<maxLen where i < pieces.count {
            ids[i] = Int64(pieces[i])
            mask[i] = 1
        }
        return (ids, mask)
    }

    // Lower-case, NFD, drop combining marks, split on whitespace, peel punctuation/symbols into own tokens.
    private func basicTokenize(_ text: String) -> [String] {
        let normalized = text.lowercased().decomposedStringWithCanonicalMapping
        var words: [String] = []
        var sb = String.UnicodeScalarView()
        func flush() { if !sb.isEmpty { words.append(String(sb)); sb = String.UnicodeScalarView() } }

        for scalar in normalized.unicodeScalars {
            let cat = scalar.properties.generalCategory
            if cat == .nonspacingMark { continue }
            if scalar.properties.isWhitespace {
                flush()
            } else if Self.isPunctuationOrSymbol(cat) {
                flush()
                words.append(String(scalar))
            } else {
                sb.append(scalar)
            }
        }
        flush()
        return words
    }

    // Mirrors C# char.IsPunctuation || char.IsSymbol.
    private static func isPunctuationOrSymbol(_ c: Unicode.GeneralCategory) -> Bool {
        switch c {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation,
             .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
            return true
        default:
            return false
        }
    }

    // Greedy longest-match WordPiece; unknown words → [UNK].
    private func wordPiece(_ word: String) -> [Int] {
        let chars = Array(word.unicodeScalars)
        var start = 0
        var output: [Int] = []
        while start < chars.count {
            var end = chars.count
            var matched = -1
            while start < end {
                let sub = (start > 0 ? "##" : "") + String(String.UnicodeScalarView(chars[start..<end]))
                if let id = vocab[sub] { matched = id; break }
                end -= 1
            }
            if matched < 0 { return [unkId] }
            output.append(matched)
            start = end
        }
        return output
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/EdgeSearch/WordPieceTokenizer.swift
git commit -m "feat: add Swift WordPiece tokenizer port (matches the engine)"
```

---

### Task 7: Edge models (iOS)

**Files:** Create `ios/Shared/EdgeSearch/EdgeModels.swift`.

- [ ] **Step 1: Write the models**

Create `ios/Shared/EdgeSearch/EdgeModels.swift`:
```swift
import Foundation

/// One indexed maintenance-doc section from the edge index.
struct EdgeChunk: Identifiable, Sendable {
    let id: String            // ChunkId
    let documentId: String
    let sectionTitle: String
    let contentText: String
    let errorCodes: [String]
    let toolsRequired: [String]

    /// Decodes the `Metadata` JSON column: { "error_codes":[…], "tools_required":[…] }.
    struct Metadata {
        let errorCodes: [String]
        let toolsRequired: [String]
        init(json: String) {
            struct Raw: Decodable { let error_codes: [String]?; let tools_required: [String]? }
            let raw = try? JSONDecoder().decode(Raw.self, from: Data(json.utf8))
            errorCodes = raw?.error_codes ?? []
            toolsRequired = raw?.tools_required ?? []
        }
    }
}

/// A ranked hit: the chunk and its cosine score (0…1).
struct EdgeSearchHit: Identifiable, Sendable {
    let chunk: EdgeChunk
    let score: Float
    var id: String { chunk.id }
}

/// Errors surfaced by the edge engine; rendered as graceful "unavailable"/inline cards.
enum EdgeSearchError: LocalizedError {
    case assetMissing(String)
    case dbOpenFailed
    case inferenceFailed

    var errorDescription: String? {
        switch self {
        case .assetMissing(let f): return "Missing bundled asset: \(f)."
        case .dbOpenFailed: return "Couldn't open the compiled search index."
        case .inferenceFailed: return "On-device embedding failed (Core ML)."
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/EdgeSearch/EdgeModels.swift
git commit -m "feat: add EVS edge models (chunk, hit, error)"
```

---

### Task 8: Edge search engine — ORT embed + SQLite + vDSP (iOS)

**Files:** Create `ios/Shared/EdgeSearch/EdgeSearchEngine.swift`.

- [ ] **Step 1: Write the engine**

Create `ios/Shared/EdgeSearch/EdgeSearchEngine.swift`:
```swift
import Foundation
import SQLite3
import Accelerate

/// Fully on-device edge search: embeds the query with ONNX Runtime + Core ML (via EvsOrtSession) and
/// cosine-ranks it against the prebuilt SQLite vector index. No engine/FFI, no network.
///
/// `@unchecked Sendable`: the ORT session isn't thread-safe, but the view model serializes calls (one
/// in-flight search, guarded by its `searching` flag), so it's safe to hop actors via Task.detached.
final class EdgeSearchEngine: @unchecked Sendable {
    private let ort: EvsOrtSession
    private let tokenizer: WordPieceTokenizer
    private let indexed: [(chunk: EdgeChunk, embedding: [Float])]

    var allErrorCodes: [String] { Array(Set(indexed.flatMap { $0.chunk.errorCodes })) }
    var allTools: [String] { Array(Set(indexed.flatMap { $0.chunk.toolsRequired })) }

    /// Loads the bundled model (Core ML EP), vocab, and the compiled index. Throws on any missing asset.
    init() throws {
        guard let modelPath = Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx") else {
            throw EdgeSearchError.assetMissing("model.onnx")
        }
        guard let vocabPath = Bundle.main.path(forResource: "vocab", ofType: "txt", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "vocab", ofType: "txt") else {
            throw EdgeSearchError.assetMissing("vocab.txt")
        }
        guard let dbPath = Bundle.main.path(forResource: "edge-index", ofType: "db") else {
            throw EdgeSearchError.assetMissing("edge-index.db")
        }
        let vocabLines = try String(contentsOfFile: vocabPath, encoding: .utf8)
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        tokenizer = WordPieceTokenizer(vocabLines: vocabLines)
        ort = try EvsOrtSession(modelPath: modelPath)
        indexed = try EdgeSearchEngine.loadIndex(dbPath: dbPath)
    }

    /// Embeds `query`, returns chunks with cosine (== dot, vectors are normalized) ≥ `minScore`,
    /// sorted desc, capped at `topK`.
    func search(_ query: String, minScore: Float = 0.70, topK: Int = 20) throws -> [EdgeSearchHit] {
        let (ids, mask) = tokenizer.encode(query)
        var q = [Float](repeating: 0, count: 384)
        try ort.embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &q)

        var hits: [EdgeSearchHit] = []
        for item in indexed {
            var score: Float = 0
            vDSP_dotpr(q, 1, item.embedding, 1, &score, 384)
            if score >= minScore { hits.append(EdgeSearchHit(chunk: item.chunk, score: score)) }
        }
        hits.sort { $0.score > $1.score }
        return Array(hits.prefix(topK))
    }

    // Reads chunks + embeddings via the SQLite C API.
    private static func loadIndex(dbPath: String) throws -> [(chunk: EdgeChunk, embedding: [Float])] {
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK, let db else {
            throw EdgeSearchError.dbOpenFailed
        }
        defer { sqlite3_close(db) }

        var stmt: OpaquePointer?
        let sql = "SELECT ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata FROM Chunks"
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { throw EdgeSearchError.dbOpenFailed }
        defer { sqlite3_finalize(stmt) }

        var result: [(chunk: EdgeChunk, embedding: [Float])] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            func text(_ col: Int32) -> String { sqlite3_column_text(stmt, col).map { String(cString: $0) } ?? "" }
            let byteLen = Int(sqlite3_column_bytes(stmt, 4))
            var embedding = [Float](repeating: 0, count: 384)
            if let blob = sqlite3_column_blob(stmt, 4), byteLen == 384 * MemoryLayout<Float>.size {
                _ = embedding.withUnsafeMutableBytes { memcpy($0.baseAddress, blob, byteLen) }
            }
            let meta = EdgeChunk.Metadata(json: text(5))
            result.append((
                chunk: EdgeChunk(id: text(0), documentId: text(1), sectionTitle: text(2),
                                 contentText: text(3), errorCodes: meta.errorCodes,
                                 toolsRequired: meta.toolsRequired),
                embedding: embedding))
        }
        return result
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/EdgeSearch/EdgeSearchEngine.swift
git commit -m "feat: add EVS edge search engine (ORT + SQLite + vDSP)"
```

---

### Task 9: Cross-runtime agreement test (the second on-device validation)

> Confirms the Swift tokenizer + Core-ML embedding agree with the .NET publisher: tokenizer ids match, the query ranks the expected chunk first, and the query vector is close to the publisher's. Runs on the Simulator (Mac). Requires Tasks 5–8 + the index/fixtures bundled — so do **Task 12 Step 1** (bundle `edge-index.db` + `edge-fixtures.json`) before running this, then return here.

**Files:** Create `ios/Tests/EdgeSearchAgreementTests.swift`.

- [ ] **Step 1: Write the test**

Create `ios/Tests/EdgeSearchAgreementTests.swift`:
```swift
import XCTest
@testable import DotnetNativeInteropUnified

/// Validates Swift⇄.NET parity using the publisher's `edge-fixtures.json` and the bundled `edge-index.db`.
final class EdgeSearchAgreementTests: XCTestCase {
    private struct Fixtures: Decodable {
        let query: String
        let ids: [Int64]
        let queryVector: [Float]
        let expectedTopChunkId: String
    }

    private func loadFixtures() throws -> Fixtures {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "edge-fixtures", ofType: "json"))
        return try JSONDecoder().decode(Fixtures.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
    }

    func testTokenizerMatchesEngineIds() throws {
        let fx = try loadFixtures()
        let vocabPath = try XCTUnwrap(
            Bundle.main.path(forResource: "vocab", ofType: "txt", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "vocab", ofType: "txt"))
        let lines = try String(contentsOfFile: vocabPath, encoding: .utf8)
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let (ids, _) = WordPieceTokenizer(vocabLines: lines).encode(fx.query)
        XCTAssertEqual(ids, fx.ids, "Swift tokenizer ids must equal the C# tokenizer ids")
    }

    func testQueryRanksExpectedChunkFirstAndVectorAgrees() throws {
        let fx = try loadFixtures()
        let engine = try EdgeSearchEngine()
        let hits = try engine.search(fx.query, minScore: 0.0, topK: 5)
        XCTAssertEqual(hits.first?.chunk.id, fx.expectedTopChunkId, "ranking must match the publisher")

        // Vector agreement: re-embed the query and compare to the publisher's vector (max abs delta).
        let vocabPath = try XCTUnwrap(
            Bundle.main.path(forResource: "vocab", ofType: "txt", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "vocab", ofType: "txt"))
        let modelPath = try XCTUnwrap(
            Bundle.main.path(forResource: "model", ofType: "onnx", inDirectory: "assets")
            ?? Bundle.main.path(forResource: "model", ofType: "onnx"))
        let lines = try String(contentsOfFile: vocabPath, encoding: .utf8)
            .split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let (ids, mask) = WordPieceTokenizer(vocabLines: lines).encode(fx.query)
        var v = [Float](repeating: 0, count: 384)
        try EvsOrtSession(modelPath: modelPath).embed(inputIds: ids, attentionMask: mask, length: ids.count, out: &v)
        let maxDelta = zip(v, fx.queryVector).map { abs($0 - $1) }.max() ?? 1
        print("EVS vector max abs delta (Swift/CoreML vs .NET/CPU) = \(maxDelta)")
        XCTAssertLessThan(maxDelta, 0.05, "Core ML embedding should closely match the publisher's")
    }
}
```

- [ ] **Step 2: Run on the Simulator**

```bash
cd ios && xcodegen generate
xcodebuild test \
  -project DotnetNativeInteropApp.xcodeproj \
  -scheme DotnetNativeInteropUnified \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
  -only-testing:DotnetNativeInteropTests/EdgeSearchAgreementTests \
  CODE_SIGNING_ALLOWED=NO
```
Expected: both tests pass; the console prints the vector delta. If `testTokenizerMatchesEngineIds` fails, diff the ids — a punctuation/whitespace category mismatch in the Swift port is the usual cause; fix `WordPieceTokenizer.swift` to match C#. If the delta exceeds 0.05 but ranking still passes, note it in the findings doc (Task 14) and relax the threshold with a comment — small cross-runtime float drift is acceptable.

- [ ] **Step 3: Commit**

```bash
git add ios/Tests/EdgeSearchAgreementTests.swift
git commit -m "test: add EVS Swift/.NET cross-runtime agreement tests"
```

---

### Task 10: Edge search view model (iOS)

**Files:** Create `ios/Shared/EdgeSearch/EdgeSearchViewModel.swift`.

- [ ] **Step 1: Write the view model**

Create `ios/Shared/EdgeSearch/EdgeSearchViewModel.swift`:
```swift
import Foundation

/// Backs the Manuals screen: query, metadata filters, ranked hits, busy/error state. Loads the engine
/// lazily off the main actor (the ONNX model + index) on first appearance, so app startup isn't blocked.
@MainActor
final class EdgeSearchViewModel: ObservableObject {
    @Published var query = ""
    @Published var hits: [EdgeSearchHit] = []
    @Published var activeErrorCodes: Set<String> = []
    @Published var activeTools: Set<String> = []
    @Published var searching = false
    @Published var errorMessage: String?
    @Published private(set) var unavailable: String?
    @Published private(set) var allErrorCodes: [String] = []
    @Published private(set) var allTools: [String] = []

    private var engine: EdgeSearchEngine?

    /// Builds the engine once, off-main. Sets `unavailable` (→ graceful card) on failure.
    func prepare() async {
        guard engine == nil, unavailable == nil else { return }
        do {
            let e = try await Task.detached(priority: .userInitiated) { try EdgeSearchEngine() }.value
            engine = e
            allErrorCodes = e.allErrorCodes.sorted()
            allTools = e.allTools.sorted()
        } catch {
            unavailable = error.localizedDescription
        }
    }

    func run() async {
        guard let engine else { return }
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { hits = []; return }
        searching = true
        defer { searching = false }
        do {
            let raw = try await Task.detached(priority: .userInitiated) { try engine.search(q) }.value
            hits = raw.filter { hit in
                (activeErrorCodes.isEmpty || !activeErrorCodes.isDisjoint(with: hit.chunk.errorCodes))
                    && (activeTools.isEmpty || !activeTools.isDisjoint(with: hit.chunk.toolsRequired))
            }
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add ios/Shared/EdgeSearch/EdgeSearchViewModel.swift
git commit -m "feat: add EVS view model (lazy load, filters, threshold)"
```

---

### Task 11: Edge search view + hub (iOS)

**Files:** Create `ios/Shared/EdgeSearch/EdgeSearchView.swift`, `ios/Shared/EdgeSearch/EdgeSearchHubView.swift`.

- [ ] **Step 1: Write the search view**

Create `ios/Shared/EdgeSearch/EdgeSearchView.swift`:
```swift
import SwiftUI

/// On-device "Manuals" search: the query is embedded by ONNX Runtime + Core ML (Apple Neural Engine)
/// and cosine-ranked against a SQLite vector index the .NET publisher compiled offline. No network.
struct EdgeSearchView: View {
    @StateObject private var model = EdgeSearchViewModel()

    var body: some View {
        List {
            if let reason = model.unavailable {
                Section {
                    ContentUnavailableView("Edge search engine unavailable",
                                           systemImage: "wrench.adjustable",
                                           description: Text(reason))
                }
            } else {
                Section {
                    HStack {
                        TextField("Search manuals… e.g. \u{201C}compressor won\u{2019}t start\u{201D}", text: $model.query)
                            .textInputAutocapitalization(.never)
                            .onSubmit { Task { await model.run() } }
                        Button {
                            Task { await model.run() }
                        } label: {
                            if model.searching { ProgressView() } else { Image(systemName: "magnifyingglass") }
                        }
                        .disabled(model.searching)
                    }
                    Text("Embedded on-device by ONNX Runtime + Core ML (Apple Neural Engine), then cosine-"
                         + "ranked against a SQLite index the .NET publisher compiled offline. No network.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                if !model.allErrorCodes.isEmpty || !model.allTools.isEmpty {
                    Section("Filters") {
                        if !model.allErrorCodes.isEmpty {
                            FacetRow(title: "Error codes", all: model.allErrorCodes, active: $model.activeErrorCodes)
                        }
                        if !model.allTools.isEmpty {
                            FacetRow(title: "Tools", all: model.allTools, active: $model.activeTools)
                        }
                    }
                }

                if let error = model.errorMessage {
                    Section { Text(error).foregroundStyle(.red) }
                }

                if !model.hits.isEmpty {
                    Section("Results") {
                        ForEach(model.hits) { EdgeHitRow(hit: $0) }
                    }
                } else if !model.query.isEmpty && !model.searching {
                    Section { Text("No matches \u{2265} 70% similarity.").foregroundStyle(.secondary) }
                }
            }
        }
        .navigationTitle("Edge Vector Search")
        .task { await model.prepare() }
    }
}

/// A horizontally-scrolling set of toggle chips for one metadata facet.
private struct FacetRow: View {
    let title: String
    let all: [String]
    @Binding var active: Set<String>

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(all, id: \.self) { tag in
                        let on = active.contains(tag)
                        Button(tag) { if on { active.remove(tag) } else { active.insert(tag) } }
                            .buttonStyle(.bordered)
                            .tint(on ? .blue : .gray)
                    }
                }
            }
        }
    }
}

/// One ranked result: section title, snippet, a similarity bar, and any error codes.
private struct EdgeHitRow: View {
    let hit: EdgeSearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(hit.chunk.sectionTitle).font(.headline)
            Text(hit.chunk.contentText).font(.subheadline).foregroundStyle(.secondary).lineLimit(4)
            HStack {
                ProgressView(value: Double(max(0, min(1, hit.score)))).tint(.blue)
                Text(String(format: "%.0f%%", hit.score * 100)).font(.caption2.monospacedDigit())
            }
            if !hit.chunk.errorCodes.isEmpty {
                Text("Codes: " + hit.chunk.errorCodes.joined(separator: ", "))
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}
```

- [ ] **Step 2: Write the hub (tab root)**

Create `ios/Shared/EdgeSearch/EdgeSearchHubView.swift`:
```swift
import SwiftUI

/// The "Manuals" tab root — offline edge search driven entirely Swift-side (ONNX + Core ML), a contrast
/// to the AI tab's in-engine NativeAOT ONNX search.
struct EdgeSearchHubView: View {
    var body: some View {
        NavigationStack { EdgeSearchView() }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add ios/Shared/EdgeSearch/EdgeSearchView.swift ios/Shared/EdgeSearch/EdgeSearchHubView.swift
git commit -m "feat: add EVS search UI (search bar, facet filters, results)"
```

---

### Task 12: App wiring — bundle the index + add the tab (iOS)

**Files:** Modify `ios/project.yml`, `ios/Shared/RootTabView.swift`.

- [ ] **Step 1: Bundle the index + fixtures into the app** (do this before Task 9's run)

In `ios/project.yml`, under the `DotnetNativeInteropUnified` target's `sources:` list, add the two artifacts as bundled resources (alongside the existing `Ai/assets` folder reference):
```yaml
      - path: ../core/DotnetNativeInterop.EdgeIndexPublisher/edge-index.db
        buildPhase: resources
      - path: ../core/DotnetNativeInterop.EdgeIndexPublisher/edge-fixtures.json
        buildPhase: resources
```

- [ ] **Step 2: Add the "Manuals" tab**

In `ios/Shared/RootTabView.swift`, add the tab inside the `TabView` (after the `AboutView` tab — making it the 8th):
```swift
            EdgeSearchHubView()
                .tabItem { Label("Manuals", systemImage: "wrench.and.screwdriver") }
```
No new stored property or `UnifiedApp` change is needed: `EdgeSearchHubView` owns its view model, which builds the engine lazily on first appearance — so the heavy ONNX/index load happens only when the tab is first opened, not at launch.

- [ ] **Step 3: Regenerate + build the app target to verify it compiles**

```bash
cd ios && xcodegen generate
xcodebuild build \
  -project DotnetNativeInteropApp.xcodeproj \
  -scheme DotnetNativeInteropUnified \
  -destination 'platform=iOS Simulator,name=iPad Pro 13-inch (M5)' \
  CODE_SIGNING_ALLOWED=NO
```
Expected: `BUILD SUCCEEDED`. (If the linker can't find ONNX symbols, confirm Task 1's framework dependency + `FRAMEWORK_SEARCH_PATHS`; if `edge-index.db` isn't found at runtime, confirm the resource paths above.)

- [ ] **Step 4: Commit**

```bash
git add ios/project.yml ios/Shared/RootTabView.swift
git commit -m "feat: bundle the edge index + add the Manuals tab"
```

> After this task, return to **Task 9** and run the agreement tests if you deferred them.

---

### Task 13: Screenshot capture for the new tab (iOS)

**Files:** Modify `ios/ScreenshotTests/ScreenshotTests.swift`.

- [ ] **Step 1: Add the capture step + helper**

In `ios/ScreenshotTests/ScreenshotTests.swift`, append to the end of `testCaptureEveryScreen()` (after the AI captures):
```swift
        // --- Manuals (EVS: Swift-side ONNX + Core ML, prebuilt SQLite index) ---
        tapTab("Manuals")
        sleep(2)
        captureEdgeSearch(as: "19-edge-search")
```
And add this helper method to the class (next to `captureSemanticSearch`):
```swift
    /// Enter Manuals, type a maintenance query, submit (loads the ONNX model + index, embeds, ranks),
    /// wait for results, screenshot.
    private func captureEdgeSearch(as name: String) {
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 6) {
            field.tap()
            field.typeText("compressor won't start\n")
        }
        // First query is cold: lazy-loads the ~90 MB model + SQLite index, then embeds via Core ML.
        sleep(15)
        shot(name)
    }
```

- [ ] **Step 2: Commit**

```bash
git add ios/ScreenshotTests/ScreenshotTests.swift
git commit -m "test: capture the Manuals (edge search) screen"
```

---

### Task 14: Findings doc (the spike outcome — written either way)

**Files:** Create `docs/onnx-coreml-edge-findings.md`.

- [ ] **Step 1: Write the findings**

Create `docs/onnx-coreml-edge-findings.md` capturing the real, captured outcome (no paraphrasing):
- **If the gate PASSED:** title it "Running ONNX Runtime + Core ML on iOS for edge search — what worked." Record the exact `onnxruntime-c` version, how the xcframework was vendored (LFS), the header import path + the Core ML append symbol confirmed in Task 1 Step 2, the EvsOrt wrapper approach, and the measured Swift/.NET vector max-abs delta from Task 9. Contrast it explicitly with Phase 3's in-engine NativeAOT path (`docs/onnx-nativeaot-ios-findings.md`).
- **If the gate FAILED:** title it "ONNX Runtime + Core ML on iOS for edge search — where it breaks (open problem)." Paste the exact `xcodebuild`/runtime error verbatim, the diagnosis (link step, missing Core ML symbol, EP init, or inference), what was tried, and what a fix would likely require. End by inviting community solutions. Per Task 1, the tab is gated to the "unavailable" card and the publisher/scaffolding remain in the repo as documented proof.

- [ ] **Step 2: Commit**

```bash
git add docs/onnx-coreml-edge-findings.md
git commit -m "docs: record ONNX + Core ML edge-search spike findings"
```

---

### Task 15: On-device verification (Mac mini + iPad)

> EVS adds no C ABI export and doesn't touch `dni.dylib`, so **no framework rebuild** — reuse the existing `dni.xcframework`. This task just builds, signs, installs, and checks behavior on a real device (where the **ANE** is actually exercised). Follow `docs/ios-build-deploy-runbook.md` / the `~/run-lab-build.sh` pattern (keychain unlock → `xcodegen generate` → signed `xcodebuild` → `xcrun devicectl device install`).

**Files:** none (build + verification).

- [ ] **Step 1: Sync the branch to the Mac + ensure LFS assets are present**

```bash
ssh steve-mac-mini "zsh -lc 'rm -rf ~/dni-evs-build && mkdir -p ~/dni-evs-build'"
git archive --format=tar HEAD | ssh steve-mac-mini "tar -xf - -C /Users/steve/dni-evs-build"
```
`git archive` does not expand LFS pointers — copy the real `onnxruntime.xcframework` binaries + `model.onnx` over separately (or `git lfs pull` + `rsync` the working tree) so the build bundles real binaries, not pointers.

- [ ] **Step 2: Generate, build (signed), install on the iPad**

On the Mac, in the synced dir, follow the runbook to: unlock the keychain, `cd ios && xcodegen generate`, `xcodebuild` the `DotnetNativeInteropUnified` scheme for the device with `DEVELOPMENT_TEAM`, and `xcrun devicectl device install`.
- If the ONNX iOS link fails on device (vs simulator), capture the linker error → it goes in `docs/onnx-coreml-edge-findings.md` and the gate-fail path (Task 1 Step 7) applies.

- [ ] **Step 3: Verify on device**

- [ ] The **Manuals** tab is present (8 tabs; it may sit behind the iPad overflow "More" control).
- [ ] **Search:** "compressor won't start" surfaces the HVAC rooftop *Symptom/Diagnosis* sections near the top with sensible similarity %s; "server won't power on" surfaces the PSU doc; "overheating" surfaces the thermal-shutdown doc.
- [ ] **Threshold:** an unrelated query (e.g. "banana bread recipe") yields the "No matches ≥ 70% similarity" state.
- [ ] **Filters:** toggling an error-code / tool chip narrows the results.
- [ ] **No regressions:** Dashboard, Features, Lab, AI, Compare, Latency, About all still work.

- [ ] **Step 4: Re-capture screenshots (optional) + final commit**

Optionally run `bash build/capture-screenshots.sh` to refresh the gallery with the Manuals screen, then commit any updated screenshots.

---

## Self-Review

**Spec coverage:** publisher (reuse engine encoder) → Tasks 2–5; SQLite schema → Task 5; sample corpus → Task 4; `onnxruntime.xcframework` via LFS + `.gitignore`/`.gitattributes` → Task 1; Swift tokenizer port → Task 6; `EdgeSearchEngine` (ORT + Core ML EP + SQLite C-API + vDSP) → Task 8; models/VM/views/hub → Tasks 7, 10, 11; "Manuals" tab + bundled index → Task 12; the **spike gate** (linkage) → Task 1, (cross-runtime agreement) → Task 9; 0.70 threshold + metadata filters → Tasks 8, 10, 11; graceful-unavailable degrade → Tasks 10, 11 + Task 1/14 fail path; findings doc (either outcome) → Task 14; screenshot capture → Task 13; on-device acceptance (ANE) → Task 15. ✅

**Placeholder scan:** no "TBD"/"handle edge cases"-style gaps — every code step has complete code. The only intentionally deferred specifics (exact ORT header import path, Core ML symbol, LFS binary name) are explicit Task 1 confirmations against the downloaded artifact, mirroring Phase 3's "use the names the spike confirmed."

**Type consistency:** `IndexRow`/`Chunks` columns ↔ Swift `EdgeChunk` fields + `Metadata`(`error_codes`/`tools_required`) ↔ publisher's Metadata JSON are aligned. `EvsOrtSession.embedInputIds:attentionMask:length:out:error:` ↔ Swift `embed(inputIds:attentionMask:length:out:)` (throws). `WordPieceTokenizer.encode(_:maxLen:)` returns `(ids, mask)` in both C# and Swift. `EdgeSearchEngine.search(_:minScore:topK:)`, `allErrorCodes`/`allTools`, and the VM's `prepare()`/`run()` are consistent across tasks. Embedding is float32[384] LE on both sides (arm64 LE; `Buffer.BlockCopy` ↔ `memcpy`). ✅

**Scope:** one cohesive plan — a thin .NET publisher (reusing the engine), one Swift module + Obj-C wrapper, a unit-test target, additive app wiring, gated by the Task 1 spike. No engine/ABI change, so no framework rebuild. ✅

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-05-edge-vector-search.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — a fresh subagent per task with review between tasks. **Note: Task 1 is a hard gate** — its on-device result decides whether the Swift ONNX/Core ML path proceeds or branches to the findings-doc fallback. Tasks 1, 6–15 are Mac/iOS; Tasks 2–5 are Windows/.NET.

**2. Inline Execution** — execute in this session with checkpoints (sensible here, since the spike gate and the Windows↔Mac handoffs want human-aware checks).

**Which approach?**
