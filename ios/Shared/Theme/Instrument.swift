import SwiftUI

/// "Precision Instrument" design tokens. Hex values are mirrored byte-for-byte in the Android
/// theme (`ui/Theme.kt`) — change one, change both.
enum Instrument {
    // MARK: Palette

    /// App background.
    static let bg0 = Color(hex: 0x0B0E11)
    /// Card / surface.
    static let bg1 = Color(hex: 0x12161B)
    /// Raised surface, code blocks, chips.
    static let bg2 = Color(hex: 0x1A2026)
    /// Rules and borders.
    static let hairline = Color(hex: 0x2A323B)

    static let textPrimary = Color(hex: 0xE8EDF2)
    static let textSecondary = Color(hex: 0x8B98A5)
    static let textTertiary = Color(hex: 0x5C6873)

    /// Signal cyan — interactive elements, in-flight state.
    static let accent = Color(hex: 0x22D3EE)
    static let ok = Color(hex: 0x34D399)
    static let fail = Color(hex: 0xF87171)
    static let warn = Color(hex: 0xFBBF24)

    /// Violet — the framed-protobuf ("Binary") transport (Wave B Plan B). Mirrored in the Android theme.
    static let transportBinary = Color(hex: 0xA78BFA)

    static func transport(_ kind: TransportKind) -> Color {
        switch kind {
        case .ffi:    return Color(hex: 0x34D399)
        case .binary: return transportBinary
        case .http:   return Color(hex: 0xFBBF24)
        case .sqlite: return Color(hex: 0xFB7185)
        }
    }

    // MARK: Spacing (pt)

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
    }

    // MARK: Radii

    enum Radius {
        static let chip: CGFloat = 4
        static let card: CGFloat = 10
        static let canvas: CGFloat = 14
    }

    // MARK: Typography

    /// Section headers: small caps-style monospaced label, the "engraved front panel" look.
    static let panelLabel = Font.system(.caption, design: .monospaced).weight(.semibold)
    /// Monospaced readouts (heap MB, elapsed ms).
    static let readoutSmall = Font.system(.subheadline, design: .monospaced).weight(.medium)
    static let code = Font.system(.footnote, design: .monospaced)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
