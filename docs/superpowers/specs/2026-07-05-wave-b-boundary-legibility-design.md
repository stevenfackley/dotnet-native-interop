# Wave B — Boundary Legibility — Design

**Date:** 2026-07-05
**Status:** Design approved (gate-verified inputs). Not yet implemented.
**Author:** orchestrated session (gates: `docs/gpb-nativeaot-findings.md`, `docs/bouncycastle-pqc-findings.md`, `docs/pqc-nativeaot-findings.md`, `docs/meai-nativeaot-findings.md`, `docs/protobuf-nativeaot-findings.md`)
**Branches (proposed):** `feat/wave-b-native` → `feat/wave-b-ios` / `feat/wave-b-android`, stacked on the merged IA collapse.

## Context

The POC's thesis is "make the boundary and its cost visible," yet after the IA collapse the
flagship Boundary section still traces **only the FFI transport** — one of three (soon four)
boundaries. Meanwhile the engine gained `bench-real` and `gclab` showcase commands
(`feat/engine-bench-gclab`) that no client UI displays, the gRPC dead-end remains a
documented exclusion with no structured-RPC successor, and the repo's tracing story is a
hand-rolled per-phase µs strip rather than the first-class `System.Diagnostics` machinery
.NET actually ships.

Four spike gates resolve every open question this wave depends on:

| Gate | Verdict | Consequence here |
|---|---|---|
| protobuf-net | ❌ reflection crash under AOT | dead — do not use |
| **Google.Protobuf + protoc codegen** | ✅ 0 warnings, round-trip + JsonFormatter | 4th transport unblocked |
| OS PQC (`MLKem`/`MLDsa`, .NET 10) | ✅ AOT, ❌ Apple/Android OS backends | cannot ship on mobile via OS |
| **BouncyCastle.Cryptography 2.6.2 PQC** | ✅ 0 warnings, byte-compatible sizes, pure managed | PQ handshake viable on mobile |

## Direction

One wave, four legs, **one native ABI rebuild**:

1. **Boundary × every transport** — the Boundary screen gains the standard transport picker;
   each transport gets an honest swimlane of its actual hops.
2. **In-process tracing** — engine-side `ActivitySource` spans drained over one new export;
   client-side spans merged into a per-request **waterfall** (Analysis gains a `Trace`
   segment). The µs strip grows up into real distributed tracing — inside one process.
3. **4th transport: framed protobuf** — length-prefixed `Google.Protobuf` frames over the
   loopback socket. The gRPC dead-end becomes a solved problem: structured binary RPC with
   no ASP.NET. Completes the spectrum: FFI ≪ binary-RPC < HTTP < SQLCipher.
4. **Trust inspector + PQ channel** — per-transport security posture, told honestly
   (HTTP loopback is *plaintext* and the UI says so), plus an opt-in ML-KEM-768/ML-DSA-65
   handshake + AES-GCM encryption on the binary transport via BouncyCastle behind a
   provider seam.

Plus the **client wiring for `bench-real` and `gclab`** (Latency hub drill-downs, both
platforms) — the engine half already merged in Wave A.

## Native ABI batch (additive — one rebuild)

Three new typed exports. No existing export changes. Everything else rides the existing
`dni_feature_run` command grammar (zero-ABI principle).

| New export | Signature | Serves |
|---|---|---|
| `dni_trace_drain` | `() -> const char*` (JSON, caller frees) | spans accumulated since last drain |
| `dni_pb_start` | `(int32 flags) -> int32` (port, <0 = error) | framed-protobuf loopback server; `flags & 1` = require PQ handshake |
| `dni_pb_stop` | `() -> void` | stop it |

Command-grammar additions (no ABI): `trust~posture` → static per-transport security posture
JSON; `trace~stats` → ring occupancy/drop counters.

### Tracing design

- `ActivitySource("Dni.Engine")` + `Meter("Dni.Engine")`; instrumented: FFI exports, HTTP
  server request lifecycle (accept/parse/execute/stream), SQLite broker (poll/claim/execute/
  respond), pb transport (frame read/decode/execute/encode), RAG pipeline stages.
- Bounded ring buffer listener: **512 spans, drop-oldest**; drops are counted and the drain
  payload reports them (`dropped: N`) — overflow is disclosed, never silent.
- **Clock honesty:** engine spans carry µs offsets from engine boot; each drain response
  carries the engine's `nowUs`, letting the client compute one offset per drain. Cross-side
  alignment is therefore ±(drain round-trip) — the waterfall UI labels engine-side and
  client-side spans distinctly and states the alignment tolerance. No pretend-perfect clocks.
- Client spans (marshal, socket write, decode, UI hop) stay client-side (`os_signpost` /
  `System.nanoTime`), correlated by a `requestId` the client mints and passes in each
  request (pb field / HTTP header / broker column; FFI presets pass it through the existing
  command id).

### Framed protobuf transport

- New `proto/dni_frame.proto` (message-only; the gRPC-era `proto/dni.proto` stays untouched
  as the documented exclusion). `Envelope` oneof mirrors the HTTP server's endpoints:
  features / run / rag / ping / bench. Wire: `[u32 little-endian length][Envelope]`.
- Server: same `System.Net.Sockets` accept-loop pattern as `HttpRaw` (house precedent),
  streaming responses as a sequence of chunk frames.
- Clients: Android `protobuf-kotlin-lite` (returns to Gradle **with a live consumer** this
  time), iOS SwiftProtobuf via SPM. **Client codegen is Plan-gated** (below) per house DNA.

### PQ secure channel (opt-in, binary transport only)

- Handshake: server sends ML-KEM-768 public key signed with ML-DSA-65 (both keys per-boot);
  client verifies, encapsulates; HKDF-SHA256 → AES-256-GCM per-frame encryption (nonce =
  frame counter). Engine side: BouncyCastle behind an `IPqcProvider` seam so the OS
  `MLKem`/`MLDsa` can be swapped in if Apple/Android backends ever ship (per the findings
  doc's recommendation).
- Client PQ availability differs — and the UI says so honestly:
  - **Android:** BouncyCastle Java (first-class). Expected to pass its gate.
  - **iOS:** CryptoKit ML-KEM where the OS provides it (iOS 26+ class availability to be
    proven by gate), else the Trust inspector shows `PQ unavailable on this OS — classical
    channel` with the version-gated comment naming the fallback. No fake green locks.
- **Trust inspector**: a `trust` segment in Boundary's inspector + a posture card in About:
  FFI = in-process, no wire, process trust boundary; HTTP = **plaintext loopback**
  (deliberate, disclosed); SQLCipher = AES-256 at rest, key custody shown; Binary+PQ = live
  negotiated params (KEM, sig alg, cipher, key sizes, handshake µs).

## Boundary × transports — swimlanes

Transport picker joins the preset row. Lanes per transport (Android inserts its JNI-shim
lane where JNI is genuinely on the path — FFI only):

- **FFI** — unchanged (existing screen).
- **HTTP** — UI thread · URLSession/OkHttp · loopback socket · raw parser (.NET) · engine ·
  SSE back. Server-side phases from engine spans; client phases from signposts.
- **SQLCipher** — UI thread · client SQLite lib · encrypted DB file · broker poll (.NET) ·
  engine · response rows. The ~50 ms poll cadence is drawn to scale — the latency is the
  lesson.
- **Binary** — UI thread · codegen stubs · framed socket (± PQ handshake lane on first
  connect) · pb decode (.NET) · engine.
- Preset availability is per-transport and honest: `throw` is FFI-only (others surface
  errors as protocol-level error payloads — shown as such, not faked); `pixels` capped on
  SQLCipher (row-size reality, disclosed).

## Latency/Analysis wiring for Wave A engine commands

- **Latency hub** gains two drill-downs (both platforms): **Real payload** — `bench-real`
  kinds × all transports, log-scale toggle (the facelift spec's data-viz-as-evidence item,
  landed here because this chart is its reason to exist); **GC Lab** — preset picker,
  heap/committed timeline, pause + per-gen counters, with the `collection mode` disclosure
  rendered as a first-class chip (forced ≠ organic, per the honesty stat the engine emits).
- **Analysis** gains a **Trace** segment: per-request waterfall (engine + client spans),
  request picker fed by recent Boundary/Compare runs.
- **Compare** gains the 4th transport series automatically via the existing
  `FeatureCatalogService` seam + a `PbFeatureService` per platform.

## Plans (implementation order)

- **Plan A — native** (`feat/wave-b-native`, Windows-verifiable): tracing source + ring +
  drain export; pb transport server; secure channel + `IPqcProvider` (BC); `trust~`/`trace~`
  commands; `abi/dni.h` batch section; console harness proving all of it over loopback.
- **Plan B — iOS** (`feat/wave-b-ios`): **gates first** — (1) SwiftProtobuf via SPM compiles
  into the xcodegen project; (2) CryptoKit ML-KEM availability on the iOS 26 SDK. Findings
  docs either way. Then Boundary transports, Trace waterfall, Trust inspector, Latency
  drill-downs.
- **Plan C — Android** (`feat/wave-b-android`): protobuf-kotlin-lite + BC deps return; JNI
  bindings for the 3 exports; then the same four UI legs as iOS.

## Non-goals

- Facelift typography/color/density (spec 3 of the 2026-06-21 program owns it; only the
  log-scale toggle lands here, attached to its chart).
- Function-calling agent, Android GGUF/neural RAG, INT8 quant, multi-turn RAG (Wave C).
- `[LoggerMessage]` log ring (future; spans only in this wave).
- Metal/NNAPI acceleration; any change to the frozen production FFI exports.

## Risks / decisions

- **Ring size 512 / drop-oldest** — decided; overflow disclosed in-payload.
- **Clock alignment tolerance** — decided: per-drain offset, labeled tolerance; no NTP-style
  cleverness.
- **SwiftProtobuf SPM** is the first SPM dependency in the project — gate proves it before
  Plan B commits; fallback is generating Swift structs by hand from the (small) proto.
- **BC on Android** adds ~a few MB — measured and recorded in the findings doc, shown in the
  size ledger later (Wave C).
- **PQ iOS availability** may gate to "Android-only PQ, iOS classical + disclosure" — that
  asymmetry is acceptable and displayed honestly (same policy as FoundationModels).
- The pb transport reuses the HttpRaw accept-loop *pattern*, not its code — no shared
  abstraction until a third socket transport exists (rule of three).
