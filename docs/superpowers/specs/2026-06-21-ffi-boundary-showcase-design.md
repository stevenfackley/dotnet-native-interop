# FFI Boundary Showcase — Design

**Date:** 2026-06-21
**Status:** Design approved (brainstorm). Not yet implemented.
**Author:** pairing session
**Branch (proposed):** `feat/ffi-boundary-showcase`, stacked on `feat/csharp-feature-catalog`.

## Context

This POC demonstrates a NativeAOT .NET library (`dni`) loaded in-process by native
frontends (SwiftUI / Jetpack Compose) over four interop transports (FFI, HTTP-loopback,
SQLCipher broker, gRPC-excluded). FFI is the production hot path.

A 5-model design council plus a code-grounded review found the FFI **engineering** is
strong (16 typed `[UnmanagedCallersOnly]` exports, function-pointer streaming, UTF-8 heap
marshalling, contained exceptions, a JNI shim with cached `JavaVM*`/`AttachCurrentThread`)
but the FFI **mechanism is invisible** to a user: it is surfaced only as one option in a
3-way transport toggle plus prose in the About tab. No screen makes the boundary itself the
subject — there is nowhere a user *sees* marshalling, the callback thread-hop, memory
ownership, the C-ABI ⇄ Swift/Kotlin binding mapping, error containment, or per-call µs cost.

The owner's stated #1 priority: **demonstrate the FFI method to the fullest possible
extent** — it will be leveraged heavily at work. This spec is the response: a dedicated,
first-class **Boundary** section.

This is **spec 1 of 3** in an approved program. Specs 2 (collapse navigation to 5 top-level
sections) and 3 (visual facelift) follow as their own brainstorm → spec → plan cycles. Per
the additive-expansion rule, the Boundary section is **added alongside** the existing tabs
now; the navigation collapse that folds it into its final home is spec 2.

## Direction

A single **Boundary** screen that **traces one FFI call end-to-end**, with the lifecycle
**swimlane as the hero visual** and a **segmented inspector** below it (Approach A · A1, both
chosen against alternatives during brainstorm).

- **Run** executes the selected preset and populates every inspector panel instantly.
- **Auto-step** walks the call's phases one at a time, animating the relevant lane and
  auto-selecting that phase's inspector segment.
- The off-UI-thread callback hop is the emphasized moment, with a persistent
  `⚠ callback fires off the UI thread` chip.

### Screen layout (top → bottom)

1. **Header** — `Boundary · trace one FFI call`, instrument style, FFI transport dot (emerald).
2. **Preset row** — `echo` · `feature` · `pixels` · `stream` · `throw`.
3. **Swimlane (hero)** — lanes: UI thread · Swift/Kotlin binding · *JNI shim (Android only)* ·
   C ABI · .NET NativeAOT · worker thread. A token animates across lanes per phase. The
   `⚠` off-thread-callback chip sits under the swimlane.
4. **Controls** — `▶ Run` · `⏯ Auto-step` (and `‹ ›` step nav in step mode).
5. **Segmented inspector** — one panel below the swimlane; segments
   `bytes · µs · memory · threads · ABI · err`. In Auto-step it auto-follows the live phase.
6. **One tap down (secondary)** — the full 16-export ABI table and the
   FFI-vs-HTTP-vs-SQLite overhead bench. Not on the main canvas.

The **JNI shim lane is present on Android only** (the extra hop .NET can't avoid to reach
Kotlin) and absent on iOS — same screen, honest per-platform difference.

## Native ABI batch (additive — one rebuild)

Three new typed exports, consistent with the codebase's "typed exports, no
`invoke(name,json)`" ethos. No existing export is modified. Batched so there is exactly one
Mac framework rebuild and one Android `.so` rebuild. AOT-safe JSON via a source-generated
`JsonSerializerContext` (no reflection).

| New export | Signature | Serves | Returns (JSON, caller frees via `dni_string_free`) |
|---|---|---|---|
| `dni_ffi_echo` | `(const char* utf8, int32 len) -> const char*` | `echo`, `pixels`, byte inspector | `{ bytesHex, ptrIn, ptrOut, len, decoded, managedThreadId, executeUs }` |
| `dni_ffi_throw` | `() -> const char*` | `throw` (error containment) | `{ caught:true, type, message, status }` — managed exception caught at the boundary, status returned, **no crash** |
| `dni_ffi_stream_start` | `(const char* prompt, int32 max, dni_trace_cb cb, void* ud) -> int64` | `stream` (thread-hop) | streams via the **extended** callback below |

Extended streaming callback (new type — does **not** alter the production `dni_session_start`
callback):

```c
typedef void (*dni_trace_cb)(void* ud, int32 idx, const char* text,
                             int32 isFinal, int64 managedThreadId, int64 elapsedUs);
```

**Reused unchanged:** `dni_feature_run` (the `feature` preset — already returns
`{result, elapsedMs, ok}`), `dni_engine_stats` (memory panel), `dni_string_free`,
`dni_session_cancel` / `dni_session_free` (stop the `stream` preset).

`abi/dni.h` gains a "Pattern 3 — boundary instrumentation (additive)" section declaring the
three exports and `dni_trace_cb`. The frozen production surface is untouched.

### Two honesty properties (shown in-UI as teaching, not hidden)

1. **Who measures which µs phase.** Marshal-in, marshal-out, and `free` happen on the native
   side (Swift/Kotlin), so those phases are **frontend-measured** (`os_signpost` /
   `System.nanoTime`). Managed execution is **native-measured** (`Stopwatch` inside the
   export → `executeUs`). **Boundary-cross cost = frontend round-trip − native executeUs.**
   The µs strip labels which side clocked each phase.
2. **The leak demo needs no native export.** The caller owns the free (`dni_string_free`), so
   "simulate missing free" is the **frontend skipping the free** while an outstanding-bytes
   counter climbs (cross-checked against `dni_engine_stats`). Reset frees. This demonstrates
   the real ownership contract rather than a synthetic native leak.

## Presets & data flow

- **echo / pixels** → `dni_ffi_echo` → JSON(bytes, ptrs, `managedThreadId`, `executeUs`);
  frontend brackets the call with signposts → marshal / cross / free µs + live hex byte
  inspector. `pixels` sends a large (image-sized) buffer through `dni_ffi_echo` so
  large-buffer marshalling and ownership stay on the instrumented path (thread-id + µs).
- **feature** → reuse `dni_feature_run(id)` → `elapsedMs`, `ok`; frontend times the round-trip.
- **stream** → `dni_ffi_stream_start(prompt, max, traceCb, ud)` → extended callback per token
  carrying `managedThreadId` + `elapsedUs`; the swimlane animates the worker→UI hop live.
  iOS hops to `MainActor`; Android's JNI shim `AttachCurrentThread` → posts to the main
  `Handler`/`Looper`. Stop via `dni_session_cancel` + `dni_session_free`.
- **throw** → `dni_ffi_throw` → containment panel (`caught`, `type`, `message`, `status`); no
  crash.
- **leak toggle** → frontend skips `dni_string_free`; outstanding-bytes counter climbs; reset
  frees.

## Frontend structure (parity — same shape both platforms)

### iOS — `ios/Shared/Boundary/`
- `BoundaryView.swift` — the A1 screen.
- `BoundaryViewModel.swift` — `@MainActor ObservableObject`: selected preset, run/step state,
  phase timings, trace result, segmented selection, leak toggle, outstanding bytes, error.
- `BoundaryService.swift` (protocol) + `FFIBoundaryService.swift` — calls the three new
  exports + reuses `dni_feature_run` / `dni_engine_stats`; frontend µs via `os_signpost`;
  thread capture via `Thread.current` / `pthread_threadid_np`.
- `SwimlaneView.swift` — Canvas / `TimelineView` token animation; JNI lane hidden.
- Inspector subviews: `BytesInspector` · `TimingStrip` · `MemoryLedger` · `ThreadsPanel` ·
  `AbiMapPanel` · `ErrorContainmentPanel`.
- The bridging header already includes `dni.h`, so the new exports are visible automatically.
- Reuses the existing Instrument theme + components.

### Android — `android/app/src/main/kotlin/io/dotnetnativeinterop/boundary/`
- `BoundaryScreen.kt` · `BoundaryViewModel.kt` · `BoundaryService.kt` + `FfiBoundaryService.kt`.
- `SwimlaneCanvas.kt` — Compose `Canvas` animation; **JNI lane visible**.
- Inspector composables mirroring iOS.
- `transport/NativeBridge.kt` — `+3 external fun` (`nativeFfiEcho`, `nativeFfiThrow`,
  `nativeFfiStreamStart`) + an `FfiTraceListener` interface for the extended callback.
- `cpp/jni_bridge.c` — register the three new natives; bridge the extended trace callback
  (`AttachCurrentThread`, marshal `threadId` + `elapsedUs`, post to main).

## Error handling (failures always visible)

- Null pointer / JSON decode failure → `ErrorBanner` carrying operation + transport context,
  never a blank screen.
- The `throw` preset is itself an error demo — rendered green-as-contained, not as a fault.
- `stream` stall (no `isFinal` within a timeout) → visible "stream stalled" + cancel.
- Every async surface has skeleton-load / empty / error states.

## Version-gated comments (program standard)

Inline comments at the call site naming + explaining the older-version alternative for every
version-gated API. Landing here:
- `[UnmanagedCallersOnly]` (.NET 5+), `delegate* unmanaged<>` function pointers (C# 9+).
- Swift `@convention(c)` closures, `@_silgen_name`; older toolchains → bridging-header decl.
- Kotlin `RegisterNatives`.
- **.NET 10 NativeAOT-Android is experimental (warning XA1040) → comment the Mono + JNI
  fallback at the library-load site.**
- Any SwiftUI/Compose APIs introduced by the screen (e.g. `ContentUnavailableView` iOS 17+ →
  custom empty view; `@Observable` iOS 17+ → `ObservableObject`/`@Published`).

This is a code-review gate for the implementation plan.

## Threading model

Producer/streaming runs on a background `Task`; the extended callback fires on a .NET
background thread. iOS hops to `@MainActor`; Android's C shim `AttachCurrentThread` once per
worker thread, then posts to a `Handler`/`Looper`. The swimlane visualizes exactly this hop.

## Non-goals (this spec)

- No navigation collapse (spec 2) and no global facelift (spec 3).
- No removal or rewrite of existing tabs, transports, ABI exports, or the .NET core.
- No new transport. FFI remains the subject; HTTP/SQLite appear only in the secondary
  overhead bench, framed as overhead-vs-FFI.
- No light theme.

## Verification

- **Managed (Windows, no device):** unit-test the underlying managed methods behind the new
  exports — `echo` round-trips bytes, `throw` is caught and reported, the stream path yields
  tokens with a `managedThreadId`. `dotnet build DotnetNativeInterop.slnx -c Release` → 0
  errors; confirm AOT-safe source-gen JSON (no reflection).
- **iOS (Mac mini):** ABI changed → rebuild the framework
  (`build/build-ios-framework.sh`, backgrounded per the build-iteration rule), sign/install,
  walk all 5 presets + Auto-step + the thread-hop + leak counter + error containment.
- **Android:** rebuild `libdni.so` (`build/build-android-so.sh`) + `jni_bridge.c`;
  `gradlew test` + `compileDebugKotlin`; instrumented walk on the `Tablet_API35` emulator or a
  device.
- **Screenshot on device/emulator before calling the visual work done** (visual-parity rule).

## File ownership (additive — no collisions)

| Area | Directory / file |
|------|------------------|
| New exports (.NET) | `core/DotnetNativeInterop.NativeBridge/Ffi/Exports.Boundary.cs` (new) |
| ABI header | `core/DotnetNativeInterop.NativeBridge/abi/dni.h` (additive section) |
| iOS screen | `ios/Shared/Boundary/` (new) |
| Android screen | `…/io/dotnetnativeinterop/boundary/` (new) |
| Android bindings | `transport/NativeBridge.kt`, `cpp/jni_bridge.c` (additive) |
| Boundary as a new top-level section | iOS `RootTabView.swift`, Android `ui/AppShell.kt` (add one destination; collapse is spec 2) |
