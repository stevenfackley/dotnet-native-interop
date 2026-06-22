# Navigation IA — Collapse to 5 Sections (Spec 2 of 3) — Design

**Date:** 2026-06-21
**Status:** Design approved (council rec P2, owner "approve all"). Not yet implemented.
**Author:** pairing session (council-synthesized)

## Context

Both apps grew to **8 tabs (iOS) / 9 (Android)** — past what a tab bar / nav bar holds (iOS HIG ≤5, Material 3 = 3–5), and the destinations mix axes: *workload* (Features, AI, Manuals, Lab), *measurement* (Dashboard, Compare, Latency), and *docs* (About). The 5-model council was unanimous: collapse to **5 top-level sections** organized by one mental model, merging the overlaps. The FFI **Boundary** tab (specs/plans of 2026-06-21) added a 9th/10th destination — this spec gives it (and everything else) a permanent home.

This is **spec 2 of 3** (1 = FFI Boundary, done; 3 = facelift). It is a **re-grouping, not a removal** — every existing screen maps into the new structure (additive-expansion rule).

## Direction: 5 sections, identical on both platforms

| # | Section | Absorbs (existing) | Notes |
|---|---------|--------------------|-------|
| 1 | **Boundary** | the new FFI Boundary screen + the Android-only **Stream** tab (→ its "streaming callback" preset) | The hero / default landing. Kills the iOS↔Android Stream parity gap. |
| 2 | **Catalog** | Features | The 57-demo catalog, made **searchable + filterable** (by C# version · status ok/fail · area · elapsed). |
| 3 | **Lab** | Lab | Visual compute (Mandelbrot / raymarcher / SIMD). Distinct "pixels computed in C#" story; kept first-class. |
| 4 | **Search** | AI + Manuals | One screen, segmented: **Engine (FFI)** semantic search vs **On-device (ONNX, no engine)** EVS — the difference surfaced as an explicit, labelled note, not hidden behind two look-alike tabs. |
| 5 | **Analysis** | Dashboard + Compare + Latency + telemetry | The neutral performance-evidence area: aggregate run/pass stats (was Dashboard), transport comparison (Compare), latency distributions/jitter/payload (Latency), live engine telemetry. |

**Architecture/About** is **demoted from a top-level tab to a secondary surface** — an `ⓘ` toolbar item / a card reachable from Boundary's ABI panel and Analysis (architecture diagram, why-NativeAOT, transport tradeoffs, live runtime facts). Rationale: reviewers hit it once; it doesn't earn a permanent slot. (Owner-accepted resolution of the council's one split; revisit if you'd rather keep About as the 5th tab and fold Lab under Catalog.)

## Resolutions of the two overlaps

- **AI-search vs Manuals-search** → merged into **Search** with a segmented control; each mode carries a one-line engine/source label ("Uses the .NET engine over FFI" vs "Runs entirely on-device via ONNX — no engine"). The architectural difference is *interesting*; lean into it instead of hiding it behind identical UIs.
- **Dashboard / Compare / Latency** → merged into **Analysis** (overview → comparison → latency evidence). They were one story (run the catalog, look at timing) told three ways.

## Platform mapping

- **iOS** `RootTabView.swift`: `TabView` → 5 `.tabItem`s in the order above; About moves to a toolbar `ⓘ`/sheet. The Boundary tab (already added first by Plan B) stays first; the other 7 current tabs collapse per the table.
- **Android** `ui/AppShell.kt`: the `Tab` enum → 5 entries (same order); the `Stream` entry folds into Boundary; About → a top-bar action. The nav rail/bar shows 5.
- New container screens: `Search` (hosts the existing AI + EVS views via a segmented control), `Analysis` (hosts Dashboard/Compare/Latency as sub-views). The existing view bodies are **reused**, not rewritten.

## Catalog search/filter (the one net-new behavior)

Features → Catalog gains: a search field (iOS `.searchable`; Android `SearchBar`), filter chips (`C# 1–6 / 7–10 / 11–14`, `✓ pass`, `✗ fail`), and sort (name / version / elapsed). 57 items is past scan-only.

## Non-goals

- No removal of any working screen, transport, demo, or engine behavior — re-grouping only.
- No visual restyle (that's spec 3); this is structure + navigation.
- No change to the FFI/transport code paths.

## Verification

- iOS: build + on-device/sim walk — 5 tabs, every prior screen reachable in its new home, Search segmented control switches engines, Catalog search/filter works, About reachable via `ⓘ`.
- Android: same on the emulator; nav bar shows 5; Stream gone (folded into Boundary).
- Screenshot both shells (visual-parity rule). Parity: identical section set + order on both platforms.

## Decomposition

Implementation plan to follow (writing-plans). Likely sequence: container screens (Search, Analysis) reusing existing bodies → repoint RootTabView/AppShell to 5 → demote About → add Catalog search/filter → per-platform verify.
