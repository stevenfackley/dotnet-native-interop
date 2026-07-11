import SwiftUI

/// Foreman: the on-device tool-calling agent, made visible. Chat-style turns, a per-turn tool-call
/// strip read from the same `dni_trace_drain` ring the Boundary trace uses, and an honest backend
/// badge read off the wire every turn — never a hardcoded assumption. Empty/loading/error/step-cap
/// states per the repo's no-silent-blank rule. Mirrors Android's `ForemanScreen`; the expanded strip
/// renders a compact per-span timing (turn total + each tool's duration) in place of Android's
/// SpanWaterfall — same `agent.turn` + `agent.tool.*` spans, a lighter presentation.
struct ForemanView: View {
    @StateObject private var model: ForemanViewModel
    @State private var revealed = false

    /// Default init wires the real FFI agent; the preview injects `MockAgentService` instead.
    init(model: ForemanViewModel = ForemanViewModel()) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Instrument.Space.m) {
                PanelHeader("Foreman · on-device tool-calling agent")
                    .revealCard(revealed, delay: 0)

                if model.turns.isEmpty {
                    Text("Ask Foreman a maintenance question. It can search the manuals, run an engine "
                         + "feature, or check live engine stats — every tool call is a real native "
                         + "operation you can inspect below.")
                        .font(.subheadline)
                        .foregroundStyle(Instrument.textSecondary)
                        .revealCard(revealed, delay: 0.05)
                }

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Instrument.Space.m) {
                        ForEach(model.turns) { turn in
                            ForemanTurnCard(turn: turn)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if let message = model.errorMessage {
                    ErrorBanner(message: message)
                }

                inputRow.revealCard(revealed, delay: 0.1)
            }
            .padding(Instrument.Space.l)
            .instrumentScreen()
            .navigationTitle("Foreman")
        }
        .task { revealed = true }
    }

    /// Query field + Ask/Cancel. The field is disabled while a turn is running (one active turn at a
    /// time — asking again would cancel the prior turn mid-stream, so the UI forbids it, like Android).
    private var inputRow: some View {
        HStack(spacing: Instrument.Space.s) {
            TextField("Ask Foreman…", text: $model.query)
                .textInputAutocapitalization(.never)
                .onSubmit { Task { await model.ask() } }
                .disabled(model.running)
                .padding(Instrument.Space.m)
                .background(Instrument.bg1, in: RoundedRectangle(cornerRadius: Instrument.Radius.card))
                .overlay(
                    RoundedRectangle(cornerRadius: Instrument.Radius.card)
                        .strokeBorder(Instrument.hairline, lineWidth: 1)
                )

            if model.running {
                Button("Cancel") { model.cancel() }
                    .buttonStyle(.borderedProminent)
                    .tint(Instrument.fail)
            } else {
                Button("Ask") { Task { await model.ask() } }
                    .buttonStyle(.borderedProminent)
                    .tint(Instrument.accent)
            }
        }
    }
}

// MARK: - Turn card

/// One question → answer exchange: query, streamed answer, the honest outcome badge (a step-capped or
/// errored turn must NEVER read as a clean answer), the wire-verbatim backend badge, and an expandable
/// tool-call strip rendered via `formatToolCall` from the turn's trace spans.
private struct ForemanTurnCard: View {
    let turn: ForemanTurn
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            PanelHeader("you asked")
            Text(turn.query)
                .foregroundStyle(Instrument.textPrimary)

            PanelHeader("foreman")
            HStack(alignment: .top, spacing: Instrument.Space.s) {
                Text(answerText)
                    .foregroundStyle(Instrument.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if turn.outcome == .streaming {
                    ProgressView()
                        .controlSize(.small)
                }
            }

            outcomeRow

            if let backend = turn.backend {
                // HONEST badge: the wire's "backend" string verbatim — never hardcoded or assumed.
                Text("backend: \(backend)")
                    .font(Instrument.panelLabel)
                    .foregroundStyle(Instrument.textTertiary)
            }

            if turn.outcome != .streaming {
                toolStrip
            }
        }
        .instrumentCard()
    }

    private var answerText: String {
        if turn.answer.isEmpty {
            return turn.outcome == .streaming ? "…" : "(no answer text)"
        }
        return turn.answer
    }

    /// Distinct badge per outcome — StepCapReached and Error carry an explanatory line so neither can
    /// masquerade as a clean answer. No badge while streaming: the spinner above already says so.
    @ViewBuilder
    private var outcomeRow: some View {
        switch turn.outcome {
        case .streaming:
            EmptyView()
        case .answered:
            OutcomeBadge(text: "ANSWERED", tint: Instrument.ok)
        case .stepCapReached:
            VStack(alignment: .leading, spacing: Instrument.Space.xs) {
                OutcomeBadge(text: "STEP CAP", tint: Instrument.warn)
                Text("Stopped: step cap reached before a definitive answer. Shown honestly, not as a clean answer.")
                    .font(.caption2)
                    .foregroundStyle(Instrument.warn)
            }
        case .error:
            VStack(alignment: .leading, spacing: Instrument.Space.xs) {
                OutcomeBadge(text: "ERROR", tint: Instrument.fail)
                Text("Turn errored — the text above is whatever the agent managed to say before failing.")
                    .font(.caption2)
                    .foregroundStyle(Instrument.fail)
            }
        }
    }

    /// Tool-call count + expandable trace strip. Each row is `name(args) -> result` via
    /// `formatToolCall`, which substitutes explicit "(args not captured)" / "(result not captured)"
    /// fallbacks — never a blank.
    @ViewBuilder
    private var toolStrip: some View {
        HStack {
            Text("\(turn.toolSteps) tool call(s)")
                .font(.caption)
                .foregroundStyle(Instrument.textSecondary)
            Spacer()
            if !turn.toolSpans.isEmpty {
                Button(expanded ? "Hide trace" : "Show trace") {
                    withAnimation(.spring(duration: 0.3)) { expanded.toggle() }
                }
                .buttonStyle(.bordered)
                .tint(Instrument.agent)
                .font(.caption)
            }
        }
        if expanded && !turn.toolSpans.isEmpty {
            VStack(alignment: .leading, spacing: Instrument.Space.xs) {
                if let turnSpan {
                    // The enclosing turn's timing context (Android renders this as a SpanWaterfall).
                    Text(String(format: "turn %.1f ms · %d tool call(s)",
                                turnSpan.durUs / 1000, toolCallSpans.count))
                        .font(Instrument.panelLabel)
                        .foregroundStyle(Instrument.textSecondary)
                }
                ForEach(toolCallSpans) { span in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(formatToolCall(span))
                            .font(Instrument.code)
                            .foregroundStyle(Instrument.agent)
                        Text(String(format: "%.1f ms", span.durUs / 1000))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(Instrument.textTertiary)
                    }
                }
                Text("Tool args/result are bounded (args ≤ 256 chars, result ≤ 512 chars) and truncated "
                     + "with \"…(truncated)\" past that — the same dni_trace_drain ring, now carrying "
                     + "dni.agent.tool_args / dni.agent.tool_result span tags. A failed/unknown tool "
                     + "call still shows its real error here, never a blank result.")
                    .font(.caption2)
                    .foregroundStyle(Instrument.textTertiary)
            }
        }
    }

    /// `turn.toolSpans` also carries the enclosing "agent.turn" span; the strip shows only the
    /// "agent.tool.*" calls as rows (same filter as Android's `isToolCall()`).
    private var toolCallSpans: [TraceSpan] {
        turn.toolSpans.filter { $0.name.hasPrefix("agent.tool.") }
    }

    /// The enclosing "agent.turn" span — the timing context for the whole turn, when the drain caught it.
    private var turnSpan: TraceSpan? {
        turn.toolSpans.first { $0.name == "agent.turn" }
    }
}

/// Small caps chip in the outcome color — the same visual weight as the panel labels.
private struct OutcomeBadge: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(Instrument.panelLabel)
            .kerning(0.8)
            .padding(.horizontal, Instrument.Space.s)
            .padding(.vertical, Instrument.Space.xs)
            .background(tint.opacity(0.15), in: RoundedRectangle(cornerRadius: Instrument.Radius.chip))
            .foregroundStyle(tint)
    }
}

#Preview {
    ForemanView(model: ForemanViewModel(service: MockAgentService()))
}
