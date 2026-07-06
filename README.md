# dotnet-native-interop

**One NativeAOT .NET 10 engine, embedded in native iOS & Android apps, reached three ways.** A single
in-process shared library is driven from a Swift / Kotlin user interface over three interop
transports — in-process **FFI**, loopback **HTTP**, and an encrypted **SQLCipher** store — and the app
shows their cost **side by side**.

The payload streamed over every transport is a live **C# 14 / .NET 10 language-feature showcase**
(collection expressions, the `field` keyword, extension blocks, generic math, list patterns, raw
string literals, …), executed for real inside the NativeAOT library. The pipeline is generic: swap one
interface (`ILanguageModel`) for a real backend — e.g. an on-device LLM via llama.cpp — and every
transport and screen works unchanged.

> The .NET code builds to `dni.dylib` (iOS) / `libdni.so` (Android) and is loaded
> **directly into the UI process** — no separate backend process, no network, no cloud.

---

## Acronyms & terms

This project sits on the native↔managed boundary, so it leans on a lot of initialisms. In one place:

| Term | Meaning |
|------|---------|
| **FFI** | Foreign Function Interface — calling C-ABI functions across the native↔managed boundary, in-process |
| **ABI** | Application Binary Interface — the binary calling convention (symbol names, argument layout) |
| **AOT** / **NativeAOT** | Ahead-Of-Time compilation — .NET compiled straight to a native binary, no JIT or runtime needed |
| **JIT** | Just-In-Time compilation — the runtime alternative to AOT (not used here) |
| **HTTP** | HyperText Transfer Protocol |
| **SSE** | Server-Sent Events — one-directional streaming over HTTP |
| **REST** | Representational State Transfer |
| **TCP** | Transmission Control Protocol |
| **JSON** | JavaScript Object Notation |
| **SQL** | Structured Query Language (SQLite = an embedded "SQL" engine, "lite") |
| **SQLCipher** | An encrypted build of SQLite — pages are AES-encrypted at rest via `PRAGMA key` |
| **AES** | Advanced Encryption Standard |
| **WAL** | Write-Ahead Logging — a SQLite journaling mode allowing concurrent reads while writing |
| **IPC** | Inter-Process Communication |
| **UI** | User Interface |
| **gRPC** | gRPC Remote Procedure Calls (**RPC** = Remote Procedure Call) — present but excluded from the build |
| **UDS** | Unix Domain Socket |
| **JNI** | Java Native Interface — the Android C↔Java/Kotlin bridge |
| **NDK** | Native Development Kit (Android's C/C++ toolchain) |
| **RID** | Runtime Identifier — a .NET target triple, e.g. `ios-arm64` |
| **GC** | Garbage Collection |
| **TLS** | Thread-Local Storage (as used below — *not* Transport Layer Security) |
| **SDK** | Software Development Kit |
| **LTS** | Long-Term Support |
| **CI** | Continuous Integration |

---

## The three transports

All three are served by the **same** embedded library, which hosts every transport; they differ only
in how the data crosses to Swift / Kotlin. A fourth (gRPC) is kept in the tree as a reference design
but is **excluded from the build** — see the note under the table.

| Transport | Mechanism | How data crosses | Relative cost |
|-----------|-----------|------------------|:------:|
| **FFI** | in-process C ABI, zero IPC | JSON returned in-memory from a C export | ★ fastest |
| **HTTP** | raw `System.Net.Sockets` server on `127.0.0.1` | JSON over a loopback HTTP/1.1 + SSE request | ★★ |
| **SQLCipher** | encrypted on-disk SQLite (`PRAGMA key`) | written to & read back from a key-encrypted `.db`, returned as JSON | ★★★ slowest |
| _gRPC over UDS_ | _Kestrel gRPC on a `.sock`_ | _(reference only — not compiled)_ | — |

> **Why HTTP is raw sockets, why SQLite is SQLCipher, why gRPC is excluded.** ASP.NET Core (Kestrel +
> gRPC) ships no NativeAOT runtime pack for mobile RIDs, so the HTTP transport is a hand-rolled
> raw-socket HTTP/1.1 + SSE server and gRPC is `<Compile Remove>`'d. For SQLite, the default
> `e_sqlite3` bundle ships **no iOS native library**; `e_sqlcipher` is the only SQLitePCLRaw bundle
> with iOS device + simulator static libs, so it is statically linked into the NativeAOT image — and
> since it is full SQLCipher, the database is encrypted at rest for free.

---

## The app

One unified SwiftUI app (`com.dotnetnativeinterop.unified`) with a transport **picker** — switch FFI / HTTP /
SQLCipher and the same catalog reloads through that transport. Four tabs:

- **Dashboard** — the active transport, "Run all", and aggregate results.
- **Features** — the C# / .NET features grouped by language version; tap one to drill into its code
  snippet, run it live, and see the result + timing (modelled on a capability-explorer UI).
- **Compare** — runs every feature over **all three transports** and charts the client round-trip time
  **side by side** (bars + totals). FFI ≪ HTTP < SQLCipher — the gap is the point.
- **About** — each transport's tradeoffs.

Each feature is its own component: a row that navigates to a detail screen, not one scrolling blob.

---

## Architecture

```
      ┌─────────────────────┐            ┌─────────────────────┐
      │   iOS app (Swift)   │            │ Android app (Kotlin)│
      │  SwiftUI · 3 trans. │            │  Compose (follow-on)│
      └──────────┬──────────┘            └──────────┬──────────┘
                 │      ffi · http · sqlcipher       │
                 ▼                                   ▼
      ┌────────────────────────────────────────────────────────┐
      │  dni  —  NativeAOT shared library (.dylib/.so)  │
      │  C ABI + 3 built transport hosts:                      │
      │    Ffi (JSON) · HttpRaw (sockets/SSE) · SQLCipher (.db)│
      └───────────────────────────┬────────────────────────────┘
                                   ▼
      ┌────────────────────────────────────────────────────────┐
      │  DotnetNativeInterop.Engine  —  pure .NET, AOT-safe            │
      │  LanguageFeatureCatalog: each feature executes live    │
      │  ILanguageModel ← FeatureShowcaseModel (swap-in seam)  │
      └────────────────────────────────────────────────────────┘

  Http/ (Kestrel) and Grpc/ remain in the tree but are excluded from the build.
```

---

## Repository layout

```
DotnetNativeInterop.slnx · global.json · Directory.Build.props
core/
  DotnetNativeInterop.Engine/          pure domain: executable feature catalog, orchestrator, channel session
  DotnetNativeInterop.NativeBridge/    NativeAOT shared library
    abi/dni.h          frozen C ABI (FFI + HTTP + SQLCipher exports)
    Ffi/ HttpRaw/ Sqlite/      built transports (Sqlite uses SQLCipher)
    Http/ Grpc/                excluded from build (no mobile runtime pack) — kept for reference
proto/dni.proto        gRPC contract (excluded build)
ios/
  Shared/                      models, FeatureService protocol, view models, the SwiftUI shell
  Apps/Unified/                the one app target (FFI + HTTP + SQLCipher, picker + Compare)
android/ (io.dotnetnativeinterop)      Compose host + JNI shim (follow-on: not yet unified)
build/                         build-ios-framework.sh · build-android-so.sh
docs/                          INTEROP_CONTRACT.md · patterns.json · superpowers/specs/
.github/workflows/             ci-core · ci-ios · ci-android
```

---

## Build & run

**Managed code** (Engine + the three built transports) builds anywhere with the .NET 10 SDK:

```bash
dotnet build DotnetNativeInterop.slnx -c Release
```

**Native artifacts require a macOS host** (Apple toolchain for iOS; Android NDK is smoothest there too):

```bash
./build/build-ios-framework.sh     # → ios/Frameworks/dni.xcframework
./build/build-android-so.sh        # → android/app/src/main/jniLibs/arm64-v8a/libdni.so
```

Then generate and open the iOS project (`cd ios && xcodegen generate`, then Xcode), or build Android
(`cd android && ./gradlew :app:assembleDebug`). CI mirrors this: `ci-core` (managed, ubuntu + windows),
`ci-ios` / `ci-android` (macOS runners).

---

## Status

- ✅ **Engine + the 3 built transports (FFI, raw-HTTP, SQLCipher) compile and run on device** (iOS & Android).
- ✅ **C# / .NET feature catalog** — 57 demos spanning C# 1–14 (collection expressions, `field` keyword,
  extension blocks, generic math, list patterns, raw string literals, …), all executed live on device.
- ✅ **Unified iOS app** with transport picker + Compare tab + Dashboard + Features + FFI Boundary
  inspection screen, deployed to a physical iPad.
- ✅ **Unified Android app (full 1:1 parity)** — complete Compose port. UI shell with transport picker,
  Dashboard, Features, Compare tabs. **AI tab** (ONNX semantic search + extractive RAG over manuals).
  **Edge Vector Search tab** (on-device offline doc indexing & retrieval, Kotlin ORT). **Lab tab**
  (Fractal explorer, raymarcher, benchmarks — pure-Kotlin visual compute). **FFI Boundary screen**
  with JNI instrumentation (echo, throw, traced streaming). **Latency hub** (Distribution, Comparison,
  Jitter, Payload, Telemetry tabs) — measure all three transports in parallel.
- ✅ **On-device AI** — **ONNX semantic search** runs on both iOS & Android (CoreML on iOS, NNAPI + CPU
  fallback on Android). **llama.cpp RAG generation** on iOS (**Llama-3.2-1B-Instruct Q4**, streamed
  over all three transports); Android RAG currently extractive (GGUF bundling pending). Retrieved
  context vs. generated response compared head-to-head with **Apple Foundation Models** on iOS.
- ✅ **FFI Boundary showcase screens** — both iOS & Android display in-process C↔managed boundary
  instrumentation (function calls, exceptions, streaming traces, JNI thread attachment on Android).
- 🚫 **gRPC + legacy Kestrel HTTP are excluded from the build** (no NativeAOT mobile runtime pack); the
  source is kept under `Grpc/` and `Http/` for reference.

---

## Threading model

- The engine runs work on a background `Task`; a bounded `System.Threading.Channels` channel provides
  backpressure (the producer blocks when the UI lags rather than dropping output).
- **iOS:** the FFI callback is a non-capturing `@convention(c)` function; the client hops to `@MainActor`.
- **Android:** the JNI shim caches `JavaVM*` in `JNI_OnLoad`, calls `AttachCurrentThread` once per .NET
  worker (a Thread-Local Storage / TLS destructor detaches on exit), then posts back to Kotlin.

---

## Swapping in a real model

The shipped payload is the C# feature showcase (`FeatureShowcaseModel`). To run a real model instead,
implement `ILanguageModel.GenerateAsync` (for example, P/Invoke into llama.cpp) and wire it into
`EngineHost.Initialize()`. Every transport, the app, and the streaming UI work unchanged.

---

## License

[MIT](LICENSE).
