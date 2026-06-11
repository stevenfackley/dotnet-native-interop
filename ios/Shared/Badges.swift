import SwiftUI

/// Small capsule showing a C# version label (e.g. "C# 14").
struct VersionBadge: View {
    let version: String
    var body: some View {
        Text(version)
            .font(.caption2.monospaced())
            .foregroundStyle(Instrument.accent)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Instrument.accent.opacity(0.12), in: Capsule())
            .overlay(Capsule().strokeBorder(Instrument.accent.opacity(0.35), lineWidth: 1))
    }
}

/// Verdict-style badge (works / mismatch / running) — the reference's VerdictBadge analog.
struct StatusBadge: View {
    let status: RunStatus
    let label: String
    var body: some View {
        Label(label, systemImage: status.symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(status.color)
    }
}

/// Monospaced code snippet block.
struct CodeBlock: View {
    let code: String
    var body: some View {
        Text(code)
            .font(Instrument.code)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Instrument.Space.m)
            .background(Instrument.bg2, in: RoundedRectangle(cornerRadius: Instrument.Radius.card))
            .overlay(
                RoundedRectangle(cornerRadius: Instrument.Radius.card)
                    .strokeBorder(Instrument.hairline, lineWidth: 1)
            )
            .textSelection(.enabled)
    }
}
