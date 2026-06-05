# OnDeviceLlm

**Native↔managed interop, three ways** — one .NET 10 **NativeAOT** engine compiled to a single
in-process shared library and reached over three transports (FFI, raw-socket HTTP, SQLite). A
side-by-side, runnable comparison of how a Swift/Kotlin UI talks to embedded .NET.

> The .NET code compiles to `ondevicellm.dylib` (iOS) / `libondevicellm.so` (Android) and is loaded
> **directly into the Swift/Kotlin UI process** — no separate backend process, no network, no cloud.
> The demo payload streamed over every transport is a **live C# 14 / .NET 10 language-feature
> showcase** (`FeatureShowcaseModel`) — zero weights, fully deterministic. The pipeline is generic:
> swap the `ILanguageModel` seam (`MockLanguageModel` is the placeholder stub) for a real backend
> such as llama.cpp and every transport and UI works unchanged.

---

## The interop patterns

Three transports are compiled into the library and run on-device; a fourth (gRPC) is kept in the
tree as a reference design but **excluded from the build** — see the note under the table.

| # | Pattern | Transport | Latency | Durable | Binary cost | Verdict |
|---|---------|-----------|:------:|:------:|:----------:|---------|
| 3 | **FFI + callback** | in-process C ABI (zero IPC) | ★★★★★ | — | low | **Production hot path** |
| 1 | **HTTP loopback** | raw `System.Net.Sockets` `127.0.0.1` + SSE | ★★★ | — | low | Best for debugging |
| 4 | **SQLite WAL broker** | shared `.db` table | ★★ | ✓ | low | Durable queue / history |
| 2 | _gRPC over UDS_ | _Kestrel gRPC on a `.sock`_ | — | — | — | **Excluded — no mobile runtime pack** |

> **Why HTTP is raw sockets, not Kestrel, and why gRPC is excluded:** ASP.NET Core (Kestrel + gRPC)
> has no NativeAOT runtime pack for `ios-arm64` / `linux-bionic-arm64`, so it cannot publish into the
> mobile shared library. The HTTP transport is therefore a minimal raw-socket HTTP/1.1 + SSE server
> (`HttpRaw/`), and gRPC (`Grpc/`, `proto/`) is `<Compile Remove>`'d — present for reference only.

**Recommendation:** use **FFI + callback** for the streaming hot path, **SQLite (WAL)** for durable
history, and treat **HTTP** as the debuggable comparison harness — not the production transport for
two pieces of code that share one address space.

Both apps render the selected pattern's feature/limitation list **live** from
[`docs/patterns.json`](docs/patterns.json) and show per-run metrics (time-to-first-token, tokens/sec,
total). The authoritative interop spec is [`docs/INTEROP_CONTRACT.md`](docs/INTEROP_CONTRACT.md).

### Pattern 3 — FFI + callback
**Features:** zero IPC, direct in-memory calls · lowest latency / highest throughput · fewest moving
parts (no server to bind) · bounded-channel backpressure maps onto the synchronous callback · no extra
OS permissions.
**Limitations:** most complex to build (UTF-8 marshalling, GC lifetimes, function-pointer ABI) ·
callback fires on a .NET background thread (UI hop is the caller's job) · Android needs a C/JNI shim
with `AttachCurrentThread` · a managed crash takes down the host · no process isolation.

### Pattern 1 — HTTP loopback (raw sockets)
**Features:** familiar REST/SSE, debuggable with curl · language-agnostic boundary (URLSession/OkHttp)
· SSE maps naturally onto token streams · loopback avoids the iOS Local Network prompt · a tiny
hand-rolled `System.Net.Sockets` server (no ASP.NET) keeps the AOT binary small.
**Limitations:** TCP + HTTP + JSON overhead to talk to yourself · dynamic-port handshake · iOS
**suspends the listener on backgrounding** (restart on foreground) · one-directional SSE · you own
the HTTP/1.1 parsing a framework would otherwise give you.

### Pattern 4 — SQLite WAL broker
**Features:** durable — requests/tokens survive an app kill · naturally reactive and decoupled · WAL
allows concurrent UI reads while the broker writes · trivial to inspect (just a `.db`) · great as a
job queue / offline outbox.
**Limitations:** polling latency (tokens appear per poll interval) · per-token row INSERT is write
amplification — wrong for hot streaming · no push (battery/wakeup cost) · you own ordering/retention
/vacuum · highest end-to-end latency.

### Pattern 2 — gRPC over UDS _(excluded from the build — reference only)_
> Kept in the tree (`core/.../Grpc/`, `proto/ondevicellm.proto`, generated Swift stubs) to document
> the design, but **not compiled**: ASP.NET Core gRPC has no NativeAOT mobile runtime pack, so it
> cannot ship in the on-device library. The notes below are what it *would* trade off.

**Features:** strongly-typed protobuf contract, server-streaming fits tokens · UDS stays in the
sandbox (no TCP port, no Local Network prompt) · efficient HTTP/2 framing, mature clients · clean
schema evolution.
**Limitations:** serializes protobuf to talk to yourself · pulls the full Kestrel + ASP.NET + gRPC
stack into the lib · UDS path lifecycle/cleanup · most ceremony of any option · **no NativeAOT mobile
runtime pack — the blocker that excludes it here.**

---

## Architecture

```
      ┌─────────────────────┐            ┌─────────────────────┐
      │   iOS app (Swift)   │            │ Android app (Kotlin)│
      │    SwiftUI · FFI    │            │   Compose · FFI     │
      └──────────┬──────────┘            └──────────┬──────────┘
                 │        ffi · http · sqlite        │
                 ▼                                   ▼
      ┌────────────────────────────────────────────────────────┐
      │  ondevicellm  —  NativeAOT shared library (.dylib/.so)  │
      │  OnDeviceLlm.NativeBridge:  C ABI + 3 built hosts      │
      │   Ffi · Http(raw sockets/SSE) · Sqlite  (grpc: cut)    │
      └───────────────────────────┬────────────────────────────┘
                                   ▼
      ┌────────────────────────────────────────────────────────┐
      │  OnDeviceLlm.Engine  —  pure .NET, AOT-safe             │
      │  InferenceOrchestrator · bounded-channel InferenceSession│
      │  ILanguageModel ← FeatureShowcaseModel (active payload) │
      └────────────────────────────────────────────────────────┘

  Http/ (Kestrel) and Grpc/ live in the tree but are excluded from the build.
  MockLanguageModel is the swap-in stub for wiring a real LLM later.
```

All built transports drive **one** shared `EngineHost.Orchestrator`. The bounded
`System.Threading.Channels` channel gives true backpressure — the producer (the model, on a
background thread) blocks when the UI lags rather than dropping tokens.

---

## Repository layout

```
OnDeviceLlm.slnx
global.json · Directory.Build.props
core/
  OnDeviceLlm.Engine/          pure domain (model, orchestrator, channel session)
  OnDeviceLlm.NativeBridge/    NativeAOT shared library
    abi/ondevicellm.h          C ABI (FFI + HTTP exports; gRPC marked excluded)
    Ffi/ HttpRaw/ Sqlite/      built transports
    Http/ Grpc/                excluded from build (no mobile runtime pack) — kept for reference
proto/ondevicellm.proto        gRPC contract (excluded build)
ios/   OnDeviceLlmApp          SwiftUI host (FFI showcase) + client adapters
android/ (io.ondevicellm)      Compose host + JNI shim + client adapters
build/                         build-ios-framework.sh · build-android-so.sh
docs/                          INTEROP_CONTRACT.md · patterns.json
.github/workflows/             ci-core · ci-ios · ci-android
```

---

## Build & run

**Managed code** (Engine + the three built NativeBridge transports) builds anywhere with the .NET 10
SDK:

```bash
dotnet build OnDeviceLlm.slnx -c Release
```

**Native artifacts require a macOS host** (Apple toolchain for iOS; Android NDK is smoothest there too):

```bash
# iOS  → ios/Frameworks/ondevicellm.xcframework
./build/build-ios-framework.sh

# Android → android/app/src/main/jniLibs/arm64-v8a/libondevicellm.so
./build/build-android-so.sh
```

Then open the iOS project (`xcodegen generate` in `ios/`, then Xcode) or build Android
(`cd android && ./gradlew :app:assembleDebug`). CI mirrors this: `ci-core` (managed, ubuntu+windows),
`ci-ios` / `ci-android` (macOS runners).

---

## Status

- ✅ **Engine + the 3 built transports (FFI, raw-HTTP, SQLite) compile** — `dotnet build -c Release`,
  0 warnings / 0 errors, verified on .NET 10.0.300.
- 🚫 **gRPC + legacy Kestrel HTTP are excluded from the build** (`<Compile Remove>` in the csproj):
  ASP.NET Core has no NativeAOT mobile runtime pack. Source kept in `Grpc/` and `Http/` for reference.
- ⏳ **iOS / Android native builds** run on macOS via the `build/` scripts (NativeAOT mobile needs the
  Apple/NDK toolchain — not buildable from Windows).

---

## Threading model

- The model runs on a background `Task`; the bounded channel provides backpressure.
- **iOS:** the FFI callback is a non-capturing `@convention(c)` function; the client hops to `@MainActor`.
- **Android:** the JNI shim caches `JavaVM*` in `JNI_OnLoad`, `AttachCurrentThread` once per .NET
  worker (TLS destructor detaches on exit), and calls back into Kotlin.

---

## Known sharp edges

- **.NET 10 LTS** (supported to Nov 2028). .NET 9 reached end-of-support May 2026.
- **Android NativeAOT is experimental** in .NET 10 (warning XA1040) — enabled via
  `EnablePreviewFeatures` + the `linux-bionic-arm64` RID (`android-arm64` cannot publish a library).
- **iOS:** Apple forbids `dlopen` of a bare `.dylib`; the NativeAOT output is wrapped in a signed
  `.framework`/`.xcframework` and linked at build (handled by the build script).
- **SQLite:** the native `e_sqlite3` must link for each mobile RID (handled in the build scripts).
- **No ASP.NET on mobile:** Kestrel/gRPC have no NativeAOT mobile runtime pack — which is why this
  build excludes both. HTTP is a raw-socket server and the hot path is FFI.

---

## Swapping in a real model

The shipped payload is the C# feature showcase (`FeatureShowcaseModel`). To run a real model instead,
implement `ILanguageModel.GenerateAsync` (e.g. P/Invoke into llama.cpp) and wire it into
`EngineHost.Initialize()` (swap the single `new FeatureShowcaseModel()` line). Every transport, both
apps, and the streaming UI work unchanged.
