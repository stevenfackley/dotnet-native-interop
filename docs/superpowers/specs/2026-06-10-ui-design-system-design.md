# Precision Instrument design system + error-hardening (iOS & Android) — Design

**Date:** 2026-06-10
**Status:** Implemented (branch `feat/ui-design-system`, stacked on `feat/android-lab-tab`).
iOS: signed device build SUCCEEDED on the Mac mini (2026-06-11, `~/dni-rag-build`); iPad install
pending (device unreachable at build time). Android: compileDebugKotlin + unit tests green;
`assembleDebug` needs the cross-built `libdni.so` (device/CI path), instrumented suite needs a device.
**Author:** pairing session

## Context

Both apps work but look like default-styled samples: no design tokens, hardcoded colors/spacing,
generic error strings, several crash-capable force-unwraps, and silently swallowed render failures.
User wants a distinctive, portfolio-grade UI plus production-quality error checking — **identical
design language on both platforms** (parity over per-platform UX).

## Direction: "Precision Instrument"

Dark-only, lab-equipment aesthetic. The content (benchmarks, telemetry, latency histograms) *is*
instrumentation; the chrome should look like the instrument.

### Shared token palette (same hex on both platforms)

| Token          | Hex       | Use |
|----------------|-----------|-----|
| bg0            | `#0B0E11` | App background |
| bg1            | `#12161B` | Cards / surfaces |
| bg2            | `#1A2026` | Raised surfaces, code blocks |
| hairline       | `#2A323B` | Rules, borders |
| textPrimary    | `#E8EDF2` | Body |
| textSecondary  | `#8B98A5` | Captions, labels |
| textTertiary   | `#5C6873` | Hints |
| accent         | `#22D3EE` | Signal cyan — interactive, running |
| ok             | `#34D399` | Pass |
| fail           | `#F87171` | Fail / error |
| warn           | `#FBBF24` | Warning |
| transport FFI  | `#34D399` | emerald |
| transport HTTP | `#FBBF24` | amber |
| transport SQLite | `#FB7185` | rose |

Spacing scale 4/8/12/16/24/32. Radii: 4 (chips), 10 (cards), 14 (canvas frames).
Numerals monospaced everywhere data is shown.

- iOS: `ios/Shared/Theme/Instrument.swift` (tokens) + `InstrumentComponents.swift`
  (card, section header, error banner, stat cell). Root forces `.preferredColorScheme(.dark)`,
  tint = accent.
- Android: `ui/Theme.kt` rewritten — always-dark custom `ColorScheme`, **dynamic color off**
  (it would override the committed palette), extended tokens via `LocalInstrumentColors`
  CompositionLocal, `Spacing` object, `ErrorBanner` composable.

## Error-hardening (both platforms)

- No force-unwraps in app code (iOS `FeaturesViewModel` dictionary unwraps; Android 6× `error!!`,
  `tokenizer!!`).
- Errors carry context: operation + transport, not just "no data".
- Every async surface has loading / empty / error states; render failures in Lab surface a visible
  banner (today: `try?` → blank screen — violates the "failures always visible" rule from the
  2026-06-04 redesign spec).
- Input validation at FFI boundary: queries trimmed + length-capped before crossing.

## Non-goals

- No changes to render/transport/FFI paths, ABI, or .NET core — chrome and state handling only.
- No new tabs/features; no removal of anything working (additive-expansion rule).
- No light theme.

## Verification

- Android: `gradlew test` + `assembleDebug` locally; instrumented suite if device attached.
- iOS: framework untouched → fast device build via `docs/ios-build-deploy-runbook.md` on the
  Mac mini (backgrounded), visual check on device.
