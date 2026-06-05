import SwiftUI

/// Small capsule showing a C# version label (e.g. "C# 14").
struct VersionBadge: View {
    let version: String
    var body: some View {
        Text(version)
            .font(.caption2.monospaced())
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(.quaternary, in: Capsule())
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
            .font(.system(.footnote, design: .monospaced))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
            .textSelection(.enabled)
    }
}
