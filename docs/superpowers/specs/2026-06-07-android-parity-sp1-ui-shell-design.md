# SP1 — Android UI Parity: Full Tab Shell + Core Trio (Design)

**Date:** 2026-06-07
**Program:** Android 1:1 parity (SP0 native gate ✅ → **SP1 UI shell** → follow-on gates for AI/Manuals/Lab)
**Branch:** `feat/android-parity-sp1-ui-shell`

## Goal

Stand up the full Android app navigation shell with all 8 iOS tabs present, building the five tabs that
the already-proven native engine supports into working Compose UI, and rendering the three tabs that
depend on not-yet-built native gates as honest placeholders. Mirror the iOS app's information
architecture and MVVM structure, in Material3-idiomatic Compose.

## Background / current state

- The iOS app (SwiftUI, complete) is the parity reference: 8 tabs — Dashboard, Features, Lab, AI,
  Compare, Latency, About, Manuals — using MVVM (`View → ViewModel → FeatureService → transport`) with a
  `FeatureService` protocol implemented per transport (FFI / HTTP / SQLCipher) and a transport picker.
- The Android app today is a single `MainActivity → InferenceScreen`: a streaming-inference demo
  (prompt → tokens) over three working **streaming** clients (`FfiClient` / `HttpClient` / `SqliteClient`,
  in `transport/`). There is **no navigation** and **none** of the catalog-based trio. Compose, Material3,
  lifecycle/ViewModel, coroutines, and kotlinx.serialization are already configured; only a navigation
  library is absent (and SP1 deliberately avoids adding one).
- SP0 proved the bionic NativeAOT `libdni.so` loads and round-trips its full C ABI on the arm64 emulator,
  including SQLCipher and llama.cpp. ONNX is **not** wired for Android (csproj ONNX hand-link is iOS-only;
  no `libonnxruntime.so` shipped for bionic), so ONNX-backed features are out of SP1.

## Scope

**In scope (one UI cycle, no native gates):**
- A 9-destination navigation shell (the 8 iOS tabs + the existing Stream/inference screen, kept additively).
- Five **functional** tabs on the proven ABI: Dashboard, Features (+ FeatureDetail), Compare, Latency, About.
- A `FeatureCatalogService` abstraction with FFI / HTTP / SQLCipher implementations (catalog + run), plus
  the `FeaturesViewModel`, `ComparisonViewModel`, `LatencyViewModel`.
- Three **gated placeholder** tabs: AI, Manuals, Lab — a reusable component that states what the tab will
  do and which native gate blocks it.
- Kotlin models mirroring the iOS structs; a shared transport picker; an instrumented emulator smoke test.

**Out of scope (explicit follow-on cycles, surfaced by the placeholders):**
- **ONNX-on-Android native gate** — ship `libonnxruntime.so` for `linux-bionic-arm64` and prove
  `dni_search` + RAG retrieval on the emulator (the e_sqlcipher dynamic-`.so` pattern). Unblocks AI + Manuals.
- **Lab compute exports** — `dni.h` exposes no fractal/raymarcher/SIMD/matmul exports; the Lab tab needs new
  engine C ABI exports + JNI bindings first.
- Apple Chat (the AI tab's Apple Foundation Models column) — no Android equivalent; permanently omitted.

## Navigation shell

Material3 `ModalNavigationDrawer` + a single `selectedTab` state driving a `when(tab) { … }` content area
inside a `Scaffold` with a top app bar (hamburger). State-based, **no `navigation-compose` dependency** —
a bottom `NavigationBar` caps at ~5 destinations and we have 9, and a drawer is the idiomatic Material3
pattern for many top-level destinations. The drawer lists all 9 with icons; gated tabs show a subtle
"gated" affordance. `AppShell.kt` owns the drawer + state; `MainActivity` sets content to `AppShell`.

## Architecture — MVVM mirroring iOS

```
DashboardScreen ─┐
FeaturesScreen ──┼─> FeaturesViewModel ──> FeatureCatalogService ──> {Ffi|Http|Sqlite}FeatureService ──> NativeBridge / OkHttp
CompareScreen ───┘    ComparisonViewModel ─┘
LatencyScreen ─────> LatencyViewModel ────> NativeBridge.nativeEngineStats + client timing
```

- **`FeatureCatalogService`** (interface) — `suspend fun descriptors(): List<FeatureDescriptor>`,
  `suspend fun run(id: String): FeatureResult`. A **new** abstraction, independent of the existing
  streaming `InferenceClient`; the streaming clients in `transport/` are untouched.
  - `FfiFeatureService` → `NativeBridge.nativeFeaturesJson()` / `nativeFeatureRun(id)`.
  - `SqliteFeatureService` → `NativeBridge.nativeSqliteFeatures()` / `nativeSqliteRun(id)`.
  - `HttpFeatureService` → `NativeBridge.nativeHttpStart()` for the loopback port, then OkHttp
    `GET /features` and `GET /feature/run/{id}` (the endpoints the iOS `HTTPFeatureService` already uses).
- **ViewModels** (`androidx.lifecycle.ViewModel`, coroutine scopes): `FeaturesViewModel` holds the selected
  `TransportKind`, the catalog, per-feature `RunStatus`/`FeatureResult`, and a run-all action (shared by
  Dashboard + Features); `ComparisonViewModel` runs the whole catalog over all three transports and records
  client-side round-trip times; `LatencyViewModel` polls `nativeEngineStats` and times repeated runs for a
  distribution/jitter view.

## The 9 destinations

| Tab | Status | Build |
|---|---|---|
| Dashboard | functional | transport picker, "Run all", aggregate stats (count / ran / passing / total elapsed) via `FeaturesViewModel` |
| Features | functional | catalog grouped by version, filter-to-failed, row → `FeatureDetailScreen` (code, expected, live run, result, timing) |
| Compare | functional | run catalog over FFI/HTTP/SQLCipher; per-transport latency bars + totals via `ComparisonViewModel` |
| Latency | functional | `nativeEngineStats` telemetry card + a run-timing distribution/jitter view via `LatencyViewModel` |
| About | functional | transport info from `patterns.json` (already loaded today) + live engine-stats snapshot |
| AI | **gated** | `GatedTabScreen` — needs the ONNX-on-Android gate (Semantic Search + RAG retrieval); Apple Chat omitted |
| Manuals | **gated** | `GatedTabScreen` — needs the ONNX-on-Android gate (Edge Vector Search) |
| Lab | **gated** | `GatedTabScreen` — needs new Lab compute exports in the C ABI + JNI |
| Stream | kept | the existing `InferenceScreen`, moved into the shell unchanged (additive) |

`GatedTabScreen(title, summary, blockingGate, gateDocPath)` renders the tab's intent and the precise
blocking dependency — an honest placeholder that documents the gate, not a fake feature.

## Models + data

Kotlin data classes parsed with kotlinx.serialization (engine emits camelCase JSON), in `model/Models.kt`:
- `FeatureDescriptor(id, title, version, code, expected)`
- `FeatureResult(id, result, elapsedMs, ok)`
- `EngineStats(gcGen0, gcGen1, gcGen2, heapBytes, committedBytes, allocatedBytes, gcPauseMs, threadCount, processorCount, uptimeMs)`
- `enum RunStatus { Idle, Running, Ok, Failed }`
- `enum TransportKind { Ffi, Http, Sqlite }` with display names

A shared `TransportPicker` composable (Material3 `SegmentedButton`) used by Dashboard / Features / Compare.

## Error handling

- Every `NativeBridge` string export can return `null`/blank on failure; services map that to a typed error
  surfaced as a `RunStatus.Failed` row or a non-blocking error banner (mirrors the existing `InferenceScreen`
  error banner). No crashes on a failed transport — the UI degrades to an error state per feature/transport.
- HTTP service: if `nativeHttpStart()` fails or the loopback is unreachable, the HTTP transport shows an
  error state; FFI/SQLite remain usable (independent transports).
- Gated tabs never call unavailable native paths, so they cannot crash.

## File structure

New, under `android/app/src/main/kotlin/io/dotnetnativeinterop/`:
- `ui/AppShell.kt` — drawer + scaffold + tab state
- `ui/tabs/{DashboardScreen,FeaturesScreen,FeatureDetailScreen,CompareScreen,LatencyScreen,AboutScreen,GatedTabScreen}.kt`
- `ui/components/TransportPicker.kt` (+ small shared composables as needed)
- `feature/{FeatureCatalogService,FfiFeatureService,HttpFeatureService,SqliteFeatureService}.kt`
- `feature/{FeaturesViewModel,ComparisonViewModel,LatencyViewModel}.kt`
- `model/Models.kt`

Modified: `MainActivity.kt` (`InferenceScreen` → `AppShell`). Untouched: `transport/`, `data/`,
`ui/InferenceScreen.kt`, `ui/InferenceViewModel.kt`, `ui/Theme.kt`.

## Testing / proof

- **Instrumented emulator test** (arm64 AVD on the Mac mini, the SP0 proof surface): assert the catalog
  loads and a feature runs over **each** transport (FFI, SQLCipher, and HTTP via the loopback), and that
  `AppShell` composes all 9 destinations without crashing (Compose UI test / `createAndroidComposeRule`).
- **CI** (`ci-android`, green): the build + `assembleDebug` APK check covers compilation of the new UI.
- Manual: drawer navigation across all tabs; transport picker switching; run-all on Dashboard; Compare bars;
  gated tabs show their placeholder.

## Decisions honored

- **Additive** — the existing inference screen, the three streaming clients, the Room/data layer, and the
  theme are all kept and untouched; SP1 adds the shell and the catalog stack alongside them.
- **Document gates** — the AI/Manuals/Lab placeholders state their blocking native gate explicitly rather
  than shipping broken functionality.
- **Material3-idiomatic** — native Android look (drawer, segmented buttons, Material cards), same
  information architecture as iOS, not a pixel copy of SwiftUI.
