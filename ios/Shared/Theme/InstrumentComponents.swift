import SwiftUI

// MARK: - Card

struct InstrumentCardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(Instrument.Space.l)
            .background(Instrument.bg1, in: RoundedRectangle(cornerRadius: Instrument.Radius.card))
            .overlay(
                RoundedRectangle(cornerRadius: Instrument.Radius.card)
                    .strokeBorder(Instrument.hairline, lineWidth: 1)
            )
    }
}

extension View {
    func instrumentCard() -> some View { modifier(InstrumentCardStyle()) }

    /// Staggered entrance: fade + rise, driven by a single `revealed` flag flipped on appear.
    func revealCard(_ revealed: Bool, delay: Double) -> some View {
        self
            .opacity(revealed ? 1 : 0)
            .offset(y: revealed ? 0 : 14)
            .animation(.spring(duration: 0.5).delay(delay), value: revealed)
    }

    /// Standard screen background for themed scroll views / lists.
    func instrumentScreen() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(Instrument.bg0)
    }
}

// MARK: - Panel header

/// Engraved-front-panel section label: monospaced caps with a trailing hairline rule.
struct PanelHeader: View {
    let title: String
    init(_ title: String) { self.title = title }

    var body: some View {
        HStack(spacing: Instrument.Space.s) {
            Text(title.uppercased())
                .font(Instrument.panelLabel)
                .kerning(1.2)
                .foregroundStyle(Instrument.textSecondary)
            Rectangle()
                .fill(Instrument.hairline)
                .frame(height: 1)
        }
    }
}

// MARK: - Stat cell

/// Label-over-readout cell used in telemetry strips and dashboards.
struct StatCell: View {
    let label: String
    let value: String
    var tint: Color = Instrument.textPrimary

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.xs) {
            Text(label.uppercased())
                .font(Instrument.panelLabel)
                .kerning(0.8)
                .foregroundStyle(Instrument.textTertiary)
            Text(value)
                .font(Instrument.readoutSmall)
                .foregroundStyle(tint)
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Transport dot

/// Small colored dot + name, the canonical transport identifier across all screens.
struct TransportDot: View {
    let kind: TransportKind

    var body: some View {
        HStack(spacing: Instrument.Space.xs) {
            Circle()
                .fill(Instrument.transport(kind))
                .frame(width: 7, height: 7)
            Text(kind.displayName)
                .font(Instrument.panelLabel)
                .foregroundStyle(Instrument.textSecondary)
        }
    }
}

// MARK: - Error banner

/// Visible, contextual failure surface. Never let an error die in a blank screen.
struct ErrorBanner: View {
    let message: String
    var retry: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Instrument.Space.m) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Instrument.fail)
            Text(message)
                .font(.footnote)
                .foregroundStyle(Instrument.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let retry {
                Button("Retry", action: retry)
                    .font(.footnote.weight(.semibold))
                    .buttonStyle(.bordered)
                    .tint(Instrument.fail)
            }
        }
        .padding(Instrument.Space.m)
        .background(Instrument.fail.opacity(0.12), in: RoundedRectangle(cornerRadius: Instrument.Radius.card))
        .overlay(
            RoundedRectangle(cornerRadius: Instrument.Radius.card)
                .strokeBorder(Instrument.fail.opacity(0.4), lineWidth: 1)
        )
    }
}
