# iOS Parity Work-Order

**Date:** 2026-07-06
**Purpose:** A single authoritative checklist for the iOS work that mirrors the Android-first features
built this session. Android was built first (iOS is Mac-gated — Swift can't compile on the Windows
dev box); every visual/behavioral decision was made spec-driven so iOS mirrors 1:1. This is the
work order for the Mac session (task #10). Each item links its source PR/spec for detail rather than
duplicating it.

## Why this exists
Parity is a hard repo rule (identical hex tokens, identical section structure, identical IA both
platforms). This session shipped a large batch of Android-first work; the parity decisions are
scattered across PR bodies + `MEMORY.md`. This consolidates them so the Mac session executes without
re-deriving them.

---

## 0. Mac-session prerequisites (do first)
1. **Reachability** — the Mac (`192.168.0.239`, home-LAN-only, not on Tailscale) must be reachable.
   Durable fix: join it to the tailnet. See `MEMORY.md` → native-build-host-macmini.
2. **LFS bytes** — device/sim builds need real LFS bytes (model.onnx, onnxruntime.xcframework, the
   GGUF). `git archive` exports LFS as pointer stubs — ship the smudged bytes from the Windows tree
   (tar the working tree) or `git lfs pull` on the Mac. See the runbook.
3. **`.xcodeproj` generation + commit** (a standing ask): `ios/project.yml` is complete + glob-based, so
   `xcodegen generate` produces a full project. Generate it, build it in the simulator to PROVE it
   opens/builds (needs the Mac-built `dni.xcframework` + committed `onnxruntime.xcframework`), then
   COMMIT `DotnetNativeInteropApp.xcodeproj` (gitignore only excludes `xcuserdata`, so committing is
   allowed). This is task #10 item 1.
4. **Android runtime unblock (task #12)** — while on a build host, also publish a fresh Wave-B
   `linux-bionic-arm64 libdni.so` (with `dni_pb_start/stop`, `dni_trace_drain`, `dni_agent_session_start`)
   so the merged Android work (Wave B Plan C, facelift, neural-RAG, Foreman UI) can finally be RUN +
   emulator-screenshotted. Everything Android is compile/unit-verified only until this exists.

## New shared design tokens iOS must add (exact hex)
| Token | Hex | Source | Used for |
|---|---|---|---|
| `transportBinary` | `#A78BFA` (violet) | Wave B Plan C (#43) | the 4th (framed-protobuf) transport's color |
| `Instrument.agent` | `#F472B6` (pink) | Foreman UI | `agent.*` trace-waterfall spans |

The base "Precision Instrument" palette is otherwise unchanged. The facelift added **no** new hex token
(behavioral conventions only — see §3).

---

## 1. Wave B iOS (Plan B) — Boundary×transports + Trace + Trust + PQ binary transport
Mirror of the merged Android Plan C (#43). **Gate first** (own spike, findings either way):
- **SwiftProtobuf via SPM** — the project's first SPM dependency; prove it compiles into the xcodegen
  project. Fallback: hand-generate Swift structs from the (small) proto.
- **CryptoKit ML-KEM availability** on the iOS 26 SDK — if present, use it for the PQ handshake; else
  the Trust inspector shows `PQ unavailable on this OS — classical channel` (honest disclosure, same
  policy as FoundationModels). BouncyCastle is the .NET/Android provider; iOS uses CryptoKit-or-classical.

**The byte-exact wire contract iOS must match** (proven .NET↔Kotlin↔Java this session):
1. `proto/dni_frame.proto` field numbers / oneof tags (SwiftProtobuf codegen).
2. Framing `[u32 little-endian length][payload]`, 16 MiB cap, length covers `ciphertext‖tag` when encrypted.
3. Handshake: offer→reply, plaintext frames before cipher attach; `HandshakeOffer` incl. `session_id` (field 7).
4. Signature over `kem_public_key ‖ session_id` (ML-DSA-65); verify before encapsulation; failure fatal.
5. HKDF-SHA256: salt = `ciphertext ‖ session_id`, info labels `"dni-pb c2s v1"` / `"dni-pb s2c v1"`, 32-byte
   keys, **direction: client-send = c2s key = server-recv key** (a swap = total failure).
6. AES-256-GCM: nonce = 12-byte LE frame counter (low 8 bytes, high 4 zero), per-direction keys+counters
   from 0, tag 16 bytes appended (`ct‖tag`), no AAD.
7. Parameter sets: `ml_kem_768` / `ml_dsa_65` (FIPS-final identifiers, not round-3 kyber/dilithium names).

**Also:** engine span category prefixes `pb.` / `http.` / `sqlite.` / `broker.` / `ffi.` / `rag.` (drive
coloring + Boundary filtering); `trust~posture` camelCase JSON (`transport/inProcess/encrypted/wire/detail`,
`binaryPqChannel`); the `X-Dni-Request-Id` HTTP header for trace correlation; `patterns.json` "binary" entry;
`TransportKind` order **Ffi · Binary · Http · Sqlite** (entries-driven loops render in that order).
Ref: PR #43 body + `MEMORY.md` → project-wave-program (the 8-item contract).

## 2. Facelift iOS — latency data-viz as evidence
Mirror of the merged Android facelift (#51). **No new hex token** — behavioral/format conventions only,
which iOS must implement IDENTICALLY (they'd silently diverge otherwise):
1. **Unit rule** `formatLatencyMs`: `< 1 ms → "%.1f µs"`, `≥ 1 ms → "%.2f ms"`, boundary at exactly 1.0 → ms.
   **Pin en-US locale** (a comma-decimal device would break parity + the hardcoded-string tests).
2. **Percentile overlays** on the distribution histogram: P50 dashed @ `textSecondary`, P95 dashed @ `warn`.
   (Known visual issue to resolve on-device: `warn` == `transportHttp` `#FBBF24`, so P95 sits on same-colored
   bars on the HTTP histogram — a design call.)
3. **Hero metric**: exactly one emphasized stat cell = headline + semibold, used ONCE (Distribution's median).
4. **Transport-colored series** on CDF / jitter / payload charts (canonical transport colors, never generic).
5. **Outlier annotation**: strict `> p99` (nearest-rank, needs ≥5 samples; effectively empty for n ≤ ~100 —
   the real caller uses n=400), dashed `warn` "P99+" + count caption.
6. **GC annotation**: heap point-to-point drop `> 10%` of peak → dashed `warn` "GC"; heap = `textPrimary`,
   committed = `textTertiary` (no accent). Colors **name-mapped** (heap by name), not positional.
7. Chart min-height **240dp** equivalent.
Pin the exact strict-comparison + nearest-rank-percentile semantics so iOS doesn't diverge to `≥`.
Ref: PR #51 body.

## 3. Foreman iOS UI (Plan D) — the agent, made visible
Mirror of the merged Android Foreman UI. iOS consumes the same `dni_agent_session_start` export:
- **Status-fragment contract (from `abi/dni.h`):** the final answer fragment before the empty `is_final=1`
  marker is the STATUS fragment, identified STRUCTURALLY by `text[0] == 0x01` (SOH) — **detect on the 0x01
  byte, NOT the readable `"dni.agent.status"` tag** (an LLM answering ABOUT the marker could stream that tag
  text). After the 0x01: the tag, then JSON `{"stopReason":"Answered"|"StepCapReached"|"Error","toolSteps":N,"backend":"…"}`.
- **Honesty (hard):** Answered / StepCapReached / Error must be visibly distinct — never present a capped/errored
  turn as a clean answer. The **backend badge** must reflect the wire `backend` value (e.g. "scripted routing"
  on the router brain), not a hardcode.
- **Tool-call strip** reuses the Trace waterfall, AND now renders the real tool call: `dni_trace_drain`
  spans for `agent.tool.<name>` carry two additive tags — `dni.agent.tool_args` (the JSON args the call was
  invoked with, bounded to <= 256 chars) and `dni.agent.tool_result` (the JSON result, bounded to <= 512
  chars) — both truncated with a visible `"…(truncated)"` suffix past their cap, never silently. A
  failed/unknown tool call still tags `tool_result` with its JSON error (never blank) — the strip must show
  the failure honestly. Android renders `name(args) -> result` per tool span (see
  `agent.AgentModels.formatToolCall` / `ForemanScreen`'s `ToolCallDetail` rows) — iOS should mirror the
  same two tag keys, the same two caps, and the same `name(args) -> result` format. (Previously disclosed
  as a limitation — "name+timing only" — that disclosure is now stale on Android and should not be ported.)
- **Default landing tab stays Boundary** (the thesis centerpiece). Android added Foreman as a top-level tab
  but the default-tab change was REVERTED — iOS should add Foreman as a tab, default remains Boundary.
- Token: `Instrument.agent = #F472B6`.

## 4. Neural RAG iOS — mostly already done
iOS **already bundles** the GGUF (`Llama-3.2-1B-Instruct-Q4_K_M.gguf`, LFS, under `Ai/assets/`), so iOS is
already neural via `EngineHost.BuildRagModel` — **no download-on-first-run needed on iOS**. The only parity
action: ensure the iOS-bundled GGUF shares the **same provenance** as the Android download source
(`huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF` Q4_K_M) so both platforms generate byte-identically.
Verify/re-bundle from that exact file if the current iOS GGUF's origin is unknown.

## 5. Other Mac-session iOS items
- **#36 verification** — the merged iOS IA collapse (5 sections) was merged UNCOMPILED (Mac was unreachable).
  Build it in the simulator + run `DotnetNativeInteropScreenshots`; the segmented-control taps + About sheet
  are the risk points.
- **iOS side of anything else Android-first** that lands after this doc (check the merged-PR list).

## Cross-cutting device gates (also unblock Android)
- **llama.cpp GBNF grammar sampling in the shim** (Foreman Plan B, device) — today the llama path PROMPTS
  the grammar (not sampler-enforced); real grammar-constrained tool-calling needs `dni_llama_generate` to gain
  a grammar param. This is a device gate that benefits BOTH platforms (the honest badge flips to
  "grammar-constrained" only after it lands). Until then, Foreman's llama path is honestly labeled "prompted".
- **NNAPI (Android) / ANE (iOS) INT8 acceleration** — the INT8 quant gate (#45) proved CPU rankings identical;
  Core ML/ANE INT8 agreement is unverified (Mac). Wiring INT8 into the shared EVS index is a coordinated
  both-platform change with an iOS-Core-ML verification tail — do it on the Mac, not piecemeal.
