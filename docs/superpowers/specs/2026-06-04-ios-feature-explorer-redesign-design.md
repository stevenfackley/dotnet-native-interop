# iOS Feature Explorer + per-transport apps (krestel-style) — Design

**Date:** 2026-06-04
**Status:** Implemented — with one change: consolidated to a SINGLE unified app (transport picker +
Compare tab for side-by-side perf) instead of separate apps per transport (user decision, 2026-06-05).
The legacy and FFI-only app targets were removed after the unified app was verified.
**Author:** pairing session

## Context

`DotnetNativeInterop` demonstrates a NativeAOT .NET library loaded in-process by native UIs over several
interop transports (FFI, raw-socket HTTP, SQLite). The current demo *payload* is a C# 14 / .NET 10
language-feature showcase (`FeatureShowcaseModel`), which today streams as **one text blob** over FFI
to a single iOS app.

The user wants the showcase reworked so **each language feature is its own UI component**, styled
after the sibling project `ios-embedded-krestel-poc` (its `Capabilities` → `CapabilityDetail`
pattern), and wants the **other transports built out as separate single-transport apps** (the
"6 standalone apps" direction: FFI/HTTP/SQLite × iOS/Android).

## Goals

- Each feature is a discrete component: a row in a grouped list that drills into a per-feature detail
  page showing its code snippet, live executed result, and timing.
- Match the krestel POC's visual style: `TabView` shell, `List`/`Section`/`LabeledContent`, SF Symbols
  with semantic colors, verdict/version badges, monospaced code, `ContentUnavailableView` empties.
- One app per interop transport, each sharing a single UI core.
- Failures are always visible (no silent blank screens — see lesson from the FFI empty-prompt bug).

## Non-goals (this spec)

- HTTP app, SQLite app, and the Android trio — **named as follow-on phases**, not built here.
- Playground / Diagnostics tabs from the reference (kept out unless requested later).
- Removing the existing streaming path (kept; just unused by the new FFI app UI).

## Scope of THIS spec

The **shared UI core**, the **structured feature contract**, the **engine + FFI ABI changes**, and
the **FFI app** as the first concrete, deployable app (it already runs on the iPad — safe template).

Decomposition / phase order:
1. **(this spec)** engine/ABI structured contract + shared iOS core + **FFI app**.
2. HTTP app (raw-socket SSE/JSON client) — its own spec.
3. SQLite app (broker client) — its own spec.
4. Android trio (Compose, same contract) — its own spec(s).

---

## Architecture

### 1. Structured feature contract (the core change)

Replace the single text stream with structured per-feature data, mirroring krestel's
`CapabilityDescriptor` / `CapabilityResult`:

- **Descriptor** (catalog entry): `{ id, title, version, code, expected }`
  - `id`: stable slug, e.g. `collection-expressions`
  - `version`: C# version label used for grouping, e.g. `C# 14`
  - `code`: the snippet shown in the UI
  - `expected`: the deterministic result string (precomputed once at startup)
- **Result** (one run): `{ id, result, elapsedMs, ok }`
  - `result`: the freshly executed output
  - `elapsedMs`: wall time to execute the feature in-AOT
  - `ok`: `result == expected` (the showcase is deterministic, so this is a live self-check)

The deterministic showcase means `expected == result`; "Run" re-executes to prove it runs live inside
NativeAOT and to surface real timing — the analog of krestel's run-a-probe.

### 2. Engine changes (`DotnetNativeInterop.Engine`)

`LanguageFeatureCatalog` currently computes all results inline into strings. Refactor so each feature
is independently executable (AOT-safe — `Func<string>` + `Stopwatch`, no reflection):

- `sealed record LanguageFeature(string Id, string Title, string Version, string Code, Func<string> Execute)`
- `LanguageFeatureCatalog.Features` — the executable list (internal use).
- `LanguageFeatureCatalog.Descriptors` — `IReadOnlyList<FeatureDescriptor>` where
  `FeatureDescriptor(string Id, string Title, string Version, string Code, string Expected)` and
  `Expected = Execute()` captured once.
- `LanguageFeatureCatalog.Run(string id) -> FeatureRun(string Id, string Result, double ElapsedMs, bool Ok)`
  — finds by id, times `Execute()`, sets `Ok = Result == Expected`. Unknown id → `Ok=false`, result text explains.
- `FeatureShowcaseModel` (streaming) updated to iterate `Descriptors` (Code + Expected). Streaming path
  stays functional for the legacy/other transports.

### 3. FFI ABI changes (`DotnetNativeInterop.NativeBridge`, `abi/dni.h`)

New exports (additive; existing streaming exports untouched):

```c
const char* dni_features_json(void);          /* JSON array of descriptors; caller frees */
const char* dni_feature_run(const char* id);  /* JSON {id,result,elapsedMs,ok}; caller frees */
void        dni_string_free(const char* s);   /* frees strings returned above */
```

- Both getters call `EngineHost.Initialize()` (idempotent) so they work without a separate init call.
- Strings are heap UTF-8 via `NativeText.Allocate`; the Swift caller copies with `String(cString:)`
  then calls `dni_string_free`.
- JSON via a **source-generated `JsonSerializerContext`** for `FeatureDescriptor` / `FeatureRun`
  (AOT-safe; no reflection).
- `abi/dni.h` updated to declare the three new exports under a "Pattern 3 — structured"
  section.

### 4. iOS shared core (`ios/Shared/`)

- **Models** (Codable): `FeatureDescriptor(id,title,version,code,expected)`,
  `FeatureResult(id,result,elapsedMs,ok)`.
- **`FeatureService` protocol:**
  `func descriptors() async throws -> [FeatureDescriptor]` and
  `func run(_ id: String) async throws -> FeatureResult`.
  This is the single seam each transport app implements.
- **`FeaturesViewModel`** (`@MainActor ObservableObject`): `descriptors`, `results: [String:FeatureResult]`,
  `running: Set<String>`, `grouped` (by `version`, first-appearance order), `load()`, `run(id)`,
  `errorMessage`. Mirrors `CapabilitiesViewModel`.
- **Views:** `RootTabView`, `DashboardView`, `FeaturesView` (grouped `List` + `DisclosureGroup`),
  `FeatureRow`, `FeatureDetailView`, `AboutView`, plus reusable `VersionBadge` and `StatusBadge`
  (ok/failed/running with SF Symbol + color).

### 5. App target structure

```
ios/
  Shared/                 models, FeatureService, FeaturesViewModel, all views, badges
  Apps/
    FFI/                  FFIApp.swift (@main), FFIFeatureService.swift, Info.plist, BridgingHeader.h
    HTTP/                 (phase 2)
    SQLite/               (phase 3)
  Frameworks/             dni.xcframework (built on Mac)
  project.yml             one project, target DotnetNativeInteropFFI = Shared + Apps/FFI
```

- `DotnetNativeInteropFFI` target: bundle id `com.dotnetnativeinterop.ffi`, display name "C# Features · FFI".
- `FFIFeatureService` calls the three new FFI exports on a background `Task` and decodes JSON.
- The existing `ios/DotnetNativeInteropApp/` is migrated into this structure (its working FFI bits become
  `FFIFeatureService`; the streaming-specific views are replaced by the new shared shell).

### 6. The shell (krestel mapping)

- **DashboardView** ← `DashboardView`: transport identity ("FFI · in-process C ABI"), a "Run all"
  button, count of features and aggregate elapsed once run. Status line if init/load fails.
- **FeaturesView** ← `CapabilitiesView`: `NavigationStack` + `List`; `DisclosureGroup` per C# version
  (expanded by default); each `FeatureRow` shows status icon + title + monospaced id; tap →
  `FeatureDetailView`. Toolbar: Refresh + filter (ok/failed) menu.
- **FeatureDetailView** ← `CapabilityDetailView`: `Section`s — Overview (`code` in a monospaced block,
  `version`), Run ("Run" / "Run again" button), Result (`result` text, `elapsedMs`, `StatusBadge`).
- **AboutView**: this transport's summary + features/limitations from `docs/patterns.json` (bundled).

### 7. Migration & file disposition (no git here — deletions are permanent)

- **Add** the new `ios/Shared/` and `ios/Apps/FFI/` files; repoint `project.yml` to the
  `DotnetNativeInteropFFI` target.
- **Superseded** by the new shell: the old single-target views/VMs
  (`DotnetNativeInteropApp/Views/{ContentView,TokenStreamView,MetricsRow,PatternInfoPanel}.swift`,
  `ViewModels/InferenceController.swift`, the streaming `Clients/*`). These are **kept in place until
  the new FFI app is verified on the iPad**, then removed **only with explicit user OK** (no git =
  no undo). Reusable bits (e.g. `FfiClient` marshalling, `PatternCatalog` loader) are folded into the
  new core rather than rewritten.
- Bundle id moves to `com.dotnetnativeinterop.ffi` → installs alongside the current app (distinct apps per
  transport is intended).

## Data flow

`FeaturesView.task` → `viewModel.load()` → `FFIFeatureService.descriptors()` →
`dni_features_json()` (background) → decode → `descriptors` (UI groups by version).
Tap Run → `viewModel.run(id)` → `dni_feature_run(id)` (background) → decode →
`results[id]` → row + detail update. All transport work off the main actor; UI state on `@MainActor`.

## Error handling

- Service throws on null pointer / decode failure / unknown id; `FeaturesViewModel.errorMessage`
  surfaces it in a visible `Section`/banner (never a blank).
- `FeatureResult.ok == false` renders a red `StatusBadge` + the actual vs expected — visible, not swallowed.
- `ContentUnavailableView` when the catalog is empty.

## Testing & verification

- **Engine (Windows, no device):** throwaway console project in `%TEMP%` referencing
  `DotnetNativeInterop.Engine.csproj` — call `Descriptors` and `Run(id)`, assert non-empty + `Ok` true + a
  plausible `elapsedMs`. Delete after. (No probes committed to the repo.)
- **Managed build:** `dotnet build DotnetNativeInterop.slnx -c Release` → 0 errors.
- **iOS (Mac mini):** the FFI ABI changed, so the framework **must** be rebuilt:
  `bash build/build-ios-framework.sh`, then build/sign/install `DotnetNativeInteropFFI` to the iPad and
  confirm: Features list grouped by version, detail page with code + live result + elapsed, Run-again,
  Dashboard Run-all, About tab. (Native build/deploy is the real test — can't be done from Windows.)

## Follow-on phases (named, not built here)

- **HTTP app:** `HTTPFeatureService` over the raw-socket server; add `GET /features` (descriptor JSON)
  and `GET /feature/{id}/run` (result JSON) to `HttpRaw/`. New target `DotnetNativeInteropHTTP`.
- **SQLite app:** `SQLiteFeatureService` over the broker; descriptor + per-feature result tables.
- **Android trio:** Compose mirror of the shared core against the same contract.
