# Facelift — Elevate "Precision Instrument" to Portfolio-Grade (Spec 3 of 3) — Design

**Date:** 2026-06-21
**Status:** Design approved (council rec P3, owner "approve all"). Not yet implemented.
**Author:** pairing session (council-synthesized)

## Context

The "Precision Instrument" system (dark, cyan accent, mono numerals, shared hex on both platforms) is competent and consistent but **not yet portfolio-grade**: the council found the palette is "doing too much work while the layout isn't doing enough," type is monospaced everywhere, states (loading/empty/error) are under-designed, and the data-viz reads as ornament rather than evidence. This spec is a **refinement within the existing system** — no new aesthetic, no per-platform divergence (parity rule).

**Spec 3 of 3** (1 = FFI Boundary, 2 = IA collapse). Touches `Instrument`/`InstrumentComponents` (iOS) + `Theme.kt`/`InstrumentComponents.kt` (Android) and the screens, on both platforms identically.

## Direction: keep the instrument, edit it

### 1. Typography — mixed, not mono-everything (the loudest finding)
- **Monospace** stays for: numerals, code, hex, ABI fragments, panel labels (the instrument identity).
- **Sans (SF Pro / system on iOS; the Material sans on Android)** for body + paragraph copy — currently monospaced, which hurts readability.
- Tabular/lining numerals everywhere data updates (no horizontal jitter on µs/ms readouts).
- Stronger size+weight hierarchy: one page title; the dominant metric large (e.g. 32pt), its label small (≈11pt) and muted.

### 2. Color discipline
- **Cyan (`#22D3EE`) = interaction / selection / in-flight only** — not decoration.
- Transport colors (FFI emerald / HTTP amber / SQLite rose) → legends, selected pills, chart series, the Boundary swimlane — not fills everywhere.

### 3. Hierarchy & density
- One primary action per screen; one dominant card above the fold; stop giving every panel equal weight.
- Asymmetric density: feature/list rows compact (~56dp, icon+title+chip+timing); data screens (latency, telemetry) get room (histograms ≥240dp tall — don't squash the tail).

### 4. States (currently under-designed)
- **Loading** → layout-matched skeletons (or a cyan "instrument coming online" scan line), not bare spinners.
- **Empty** → designed empty states naming the active filter ("NO FEATURES MATCHED — clear filters").
- **Error** → inline, near the failed control, with the **actual** message (already have `ErrorBanner`; apply consistently; never a blank screen).

### 5. Data-viz as evidence (not ornament)
- **Log-scale toggle** on transport comparison charts — FFI is ~100–400× faster than HTTP/SQLite; a linear bar makes FFI look "free" and hides the magnitude that *is* the story.
- median / p95 / max on latency (box-and-whisker or a KDE overlay on the histogram).
- Annotate outliers + GC events; commit to one unit (`114µs`, not `0.114ms`); transport-colored series always (never generic gray in a transport-context chart).
- Motion at high-impact moments only: transport-switch animates the metric values (count up/down); the Boundary callback hop is the signature (already in spec 1).

## Scope / non-goals

- Refinement only — no new color system, no new fonts beyond adding a sans body face, no layout rewrites beyond hierarchy/density tuning.
- No behavior/feature/transport changes.
- Applies to **both platforms identically** (shared tokens stay byte-for-byte equal); the Boundary screen (spec 1) already follows these — this spec brings the rest up to the same bar.

## Verification

- Visual diff per screen on iOS sim + Android emulator; screenshot the high-traffic screens (Boundary, Catalog, Analysis/latency) before/after (visual-parity rule).
- Confirm: body copy is sans + numerals mono+tabular; cyan only on interactive/live elements; every async surface has skeleton/empty/error; the transport chart has a working log-scale toggle and reads correctly when FFI ≪ HTTP/SQLite.
- Token hex remains identical across `Instrument.swift` ↔ `Theme.kt`.

## Decomposition

Implementation plan to follow (writing-plans), likely: tokens/type additions (sans body, tabular numerals) → component refresh (`StatCell`, skeletons, `ErrorBanner` usage) → per-screen hierarchy/density pass → chart upgrades (log scale, percentiles) → per-platform screenshot verify. Sequence after spec 2 (IA) so the facelift lands on the final 5-section structure.
