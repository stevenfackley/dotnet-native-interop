# DotnetNativeInterop — Interop Contract

Single source of truth for the native↔managed transports. **Three transports are built** (FFI,
raw-socket HTTP, SQLite); **gRPC is excluded from the build** (no NativeAOT mobile runtime pack) and
documented below for reference only. Every built piece of code binds to the names/shapes/paths here.

## Architecture

- **`DotnetNativeInterop.Engine`** — pure domain: `ILanguageModel`, `MockLanguageModel` (offline,
  deterministic), `InferenceOrchestrator`, `InferenceSession` (bounded-channel backpressure),
  `InferenceRequest`, `InferenceToken`. No OS or transport dependencies.
- **`DotnetNativeInterop.NativeBridge`** — NativeAOT shared library `dni` (`.dylib`/`.so`). Hosts
  the built transports (FFI, raw-HTTP, SQLite; gRPC excluded) and the C ABI. All drive one shared
  `EngineHost.Orchestrator`.
- **`ios/`** Swift (SwiftUI) host, **`android/`** Kotlin (Compose) host. Each selects a transport
  at runtime and renders the same streaming token UI.

## Shared managed API — already written, treat as READ-ONLY

- `EngineHost.Initialize()`, `EngineHost.Orchestrator`, `EngineHost.IsInitialized`.
- `SessionRegistry.Add(InferenceSession) -> long`, `TryGet(long, out InferenceSession)`,
  `RemoveAsync(long) -> ValueTask<bool>`.
- `InferenceSession.Start(orchestrator, request, capacity = 64, ct)` → `.Reader`
  (`ChannelReader<InferenceToken>`), `.Cancel()`, `DisposeAsync()`.
- `InferenceRequest(string Prompt, int MaxTokens = 256, float Temperature = 0.8f)`.
- `InferenceToken(int Index, string Text, bool IsFinal)` — `.Text` empty on the final marker.
- `NativeStatus.{Ok=0, NotInitialized=-1, InvalidArgument=-2, UnknownSession=-3, AlreadyRunning=-4, Internal=-5}`.
- `NativeText.Read(nint)`, `NativeText.Allocate(string) -> nint`, `NativeText.Free(nint)`.

## C ABI (Pattern 3 — FFI)

Frozen in `core/DotnetNativeInterop.NativeBridge/abi/dni.h`. Exports use
`[UnmanagedCallersOnly(EntryPoint = "...")]` with the exact C names:

| C export | Meaning |
|----------|---------|
| `int32 dni_initialize()` | Calls `EngineHost.Initialize()`. 0 / negative status. |
| `void dni_shutdown()` | Disposes the FFI-owned sessions (those in `FfiState.AllocatedIds`; HTTP/broker servers + their sessions are stopped by their own exports), then resets `EngineHost`'s cached orchestrators (so the next `dni_initialize` re-resolves the RAG model — e.g. after a GGUF is downloaded post-launch). |
| `int64 dni_session_start(prompt, max_tokens, temperature, cb, user_data)` | Starts an `InferenceSession`; drains the channel on a background task and invokes `cb` per token. Returns session id (>0) or negative status. |
| `int32 dni_session_cancel(id)` | `session.Cancel()`. |
| `int32 dni_session_free(id)` | `SessionRegistry.RemoveAsync(id)`. |

Callback: `void (*)(void* user_data, int32 index, const char* text_utf8, int32 is_final)`.
Fired on a **.NET background thread**; `text` valid only during the call (copy it).

## Pattern 1 — HTTP loopback (raw sockets, `HttpRaw/`)

- Exports: `dni_http_start() -> port`, `dni_http_stop()`.
- A minimal **`System.Net.Sockets`** HTTP/1.1 server (no ASP.NET — no mobile runtime pack) binds
  `127.0.0.1:0` and returns the OS-assigned port.
- Any request streams the showcase as SSE; response `text/event-stream`, each event:
  `data: {"index":N,"text":"…","final":false}\n\n`; terminal `…"text":"","final":true…`.
- JSON is escaped by hand (AOT-safe — no reflection serializer). Loopback ⇒ **no iOS Local Network
  prompt**. iOS kills the listener on suspend — the host must restart it on foreground.

## Pattern 2 — gRPC over UDS — EXCLUDED FROM BUILD (reference only)

> `Grpc/` and `proto/dni.proto` are `<Compile Remove>`'d: ASP.NET Core gRPC has no NativeAOT
> mobile runtime pack, so `dni_grpc_start/stop` are **not exported** by the shipped library.
> The design below is retained as documentation, not a contract the build honours.

- Contract: `proto/dni.proto`, namespace `DotnetNativeInterop.NativeBridge.Grpc`, service
  `Inference.Infer` (server-streaming `InferToken`).
- Intended exports: `dni_grpc_start(socket_path)`, `dni_grpc_stop()`.
- Kestrel `options.ListenUnixSocket(socket_path)` with HTTP/2; host owns the `.sock` path in its
  sandbox (iOS `NSTemporaryDirectory`/app group; Android `cacheDir`). Delete a stale socket on start.

## Pattern 4 — SQLite WAL broker

- Exports: `dni_broker_start(db_path)`, `dni_broker_stop()`.
- `Microsoft.Data.Sqlite`, `PRAGMA journal_mode=WAL`. Schema:

```sql
CREATE TABLE IF NOT EXISTS requests (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  prompt       TEXT    NOT NULL,
  max_tokens   INTEGER NOT NULL DEFAULT 256,
  temperature  REAL    NOT NULL DEFAULT 0.8,
  status       TEXT    NOT NULL DEFAULT 'pending', -- pending|running|done|error|canceled
  created_utc  TEXT    NOT NULL
);
CREATE TABLE IF NOT EXISTS response_tokens (
  request_id  INTEGER NOT NULL,
  idx         INTEGER NOT NULL,
  text        TEXT    NOT NULL,
  is_final    INTEGER NOT NULL DEFAULT 0,
  created_utc TEXT    NOT NULL,
  PRIMARY KEY (request_id, idx)
);
```

- Broker polls `requests WHERE status='pending'` (~50 ms), marks `running`, streams into
  `response_tokens`, marks `done`. Host inserts a request, then polls
  `response_tokens WHERE request_id=? AND idx > ? ORDER BY idx`.
- Tradeoff to document: poll latency + per-token row write-amplification. Great for **durability**
  (survives app kill, full transcript), not the lowest-latency path. WAL lets the UI read while the
  broker writes.

## Onboard AI — semantic search

Ranks documents by cosine similarity to free-text queries.

| Transport | Surface | Shape |
|-----------|---------|-------|
| FFI | `dni_search(query, corpus)` | JSON `[{text,score}]`, top-K ranked by similarity |
| SQLCipher | `dni_sqlite_features()`, `dni_sqlite_run(id)` | Pattern 4 catalog (via broker) |

Corpus options: `"features"`, `"facts"`, `"manuals"`.

### Ask the Manuals — RAG (Phase 5)

Grounded generation over the bundled `manuals` corpus. Retrieval is in-engine (same encoder as
`dni_search`); generation is the managed extractive generator (Plan A) — swappable for llama.cpp
(Plan B) behind `ILanguageModel` with no ABI change.

| Transport | Surface | Shape |
|-----------|---------|-------|
| FFI | `dni_rag_session_start(query, max_tokens, temperature, cb, user_data)` | streams fragments via `dni_token_cb`; cancel/free via `dni_session_cancel` / `dni_session_free` |
| raw-HTTP | `GET /rag?q=<query>` (on `dni_http_start`'s port) | `text/event-stream` of `data: {index,text,final}` |
| SQLCipher | `dni_sqlite_rag(query)` | JSON `{"answer":"…"}`, round-tripped through a key-encrypted db (non-streaming) |

The shared "Sources" shown in the UI come from `dni_search(query, "manuals")`.

## Native library naming

Loadable name **`dni`**. Android `System.loadLibrary("dni")` ⇒ `libdni.so`.
iOS links/embeds `dni.framework`. csproj `AssemblyName=dni` ⇒ NativeAOT emits
`dni.{dylib,so}`; build scripts add the `lib` prefix (Android) and wrap the framework (iOS).

## Build (macOS host)

- iOS device: `dotnet publish -r ios-arm64`; simulator: `iossimulator-arm64`. Props already set:
  `PublishAot`, `NativeLib=Shared`, `PublishAotUsingRuntimePack`. Package `.dylib` → `.framework` →
  `.xcframework` (`build/build-ios-framework.sh`).
- Android: `dotnet publish -r linux-bionic-arm64 -p:DisableUnsupportedError=true`. NativeAOT for
  Android is **experimental** in .NET 10 (warning XA1040). Copy →
  `android/app/src/main/jniLibs/arm64-v8a/libdni.so` (`build/build-android-so.sh`).
- Sharp edge: `Microsoft.Data.Sqlite` native (`e_sqlite3`) must link for the mobile RID — on iOS
  prefer system `libsqlite3`; on Android bundle/locate the `e_sqlite3` `.so`.

## Threading model

- Producer (model) runs on a background `Task`; the bounded channel provides backpressure.
- FFI callback fires on a .NET background thread → Swift hops to `@MainActor`; Android's C shim
  `AttachCurrentThread` **once per worker thread**, then posts to a `Handler`/`Looper`.

## Pattern catalog & required UI

Canonical features/limitations live in `docs/patterns.json` (version 1) — the single source of
truth. Both apps MUST:

- bundle `patterns.json` as an app resource and render, for the selected transport, a panel showing
  its **summary, features (✓), and limitations (⚠)**;
- show **live per-run metrics**: time-to-first-token (ms), tokens/sec, total time, and transport id,
  so the latency cost of HTTP/SQLite vs FFI is *visible*, not just described.

Transport selector ids used everywhere: `ffi`, `http`, `grpc`, `sqlite`.

## File ownership (parallel build — disjoint, no collisions)

| Area | Directory |
|------|-----------|
| Pattern 1 HTTP (.NET) | `core/DotnetNativeInterop.NativeBridge/Http/` |
| Pattern 2 gRPC (.NET) | `core/DotnetNativeInterop.NativeBridge/Grpc/` |
| Pattern 3 FFI (.NET) | `core/DotnetNativeInterop.NativeBridge/Ffi/` |
| Pattern 4 SQLite (.NET) | `core/DotnetNativeInterop.NativeBridge/Sqlite/` |
| iOS host + 4 clients | `ios/` |
| Android host + 4 clients | `android/` |
| Build scripts + CI | `build/`, `.github/workflows/` |

**Read-only / frozen:** everything in `DotnetNativeInterop.Engine/`, plus
`DotnetNativeInterop.NativeBridge/{EngineHost,SessionRegistry,NativeInterop}.cs`, the `.csproj`,
`proto/dni.proto`, and `abi/dni.h`.
