package io.dotnetnativeinterop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Precision Instrument" design tokens. Hex values are mirrored byte-for-byte in the iOS theme
 * (`ios/Shared/Theme/Instrument.swift`) — change one, change both. Dark-only by design.
 */
public object Instrument {
    public val bg0: Color = Color(0xFF0B0E11)
    public val bg1: Color = Color(0xFF12161B)
    public val bg2: Color = Color(0xFF1A2026)
    public val hairline: Color = Color(0xFF2A323B)

    public val textPrimary: Color = Color(0xFFE8EDF2)
    public val textSecondary: Color = Color(0xFF8B98A5)
    public val textTertiary: Color = Color(0xFF5C6873)

    /** Signal cyan — interactive elements, in-flight state. */
    public val accent: Color = Color(0xFF22D3EE)
    public val ok: Color = Color(0xFF34D399)
    public val fail: Color = Color(0xFFF87171)
    public val warn: Color = Color(0xFFFBBF24)

    public val transportFfi: Color = Color(0xFF34D399)
    public val transportBinary: Color = Color(0xFFA78BFA)  // violet — the framed-protobuf (4th) transport
    public val transportHttp: Color = Color(0xFFFBBF24)
    public val transportSqlite: Color = Color(0xFFFB7185)

    /** Foreman agent.turn / agent.tool.* spans in the trace waterfall — NEW token (2026-07-06), distinct
     *  from the four transport colors above. iOS must mirror this exact hex in Instrument.swift. */
    public val agent: Color = Color(0xFFF472B6)
}

public object Spacing {
    public val xs: Dp = 4.dp
    public val s: Dp = 8.dp
    public val m: Dp = 12.dp
    public val l: Dp = 16.dp
    public val xl: Dp = 24.dp
}

public object Radii {
    public val chip: Dp = 4.dp
    public val card: Dp = 10.dp
    public val canvas: Dp = 14.dp
}

private val InstrumentDarkScheme = darkColorScheme(
    primary = Instrument.accent,
    onPrimary = Color(0xFF062A31),
    primaryContainer = Color(0xFF0E4854),
    onPrimaryContainer = Color(0xFFBDEDF6),
    secondary = Instrument.textSecondary,
    onSecondary = Instrument.bg0,
    // Selection pills (nav rail indicator, chips) — accent-tinted, NOT the M3 default purple.
    secondaryContainer = Color(0xFF11343C),
    onSecondaryContainer = Color(0xFFBDEDF6),
    tertiary = Instrument.ok,
    onTertiary = Instrument.bg0,
    background = Instrument.bg0,
    onBackground = Instrument.textPrimary,
    surface = Instrument.bg1,
    onSurface = Instrument.textPrimary,
    // Kill tonal-elevation tinting: elevated surfaces must stay flat graphite, not glow cyan.
    surfaceTint = Instrument.bg1,
    surfaceVariant = Instrument.bg2,
    onSurfaceVariant = Instrument.textSecondary,
    surfaceContainerLowest = Instrument.bg0,
    surfaceContainerLow = Instrument.bg1,
    surfaceContainer = Instrument.bg1,
    surfaceContainerHigh = Instrument.bg2,
    surfaceContainerHighest = Instrument.bg2,
    outline = Instrument.hairline,
    outlineVariant = Instrument.hairline,
    error = Instrument.fail,
    onError = Instrument.bg0,
)

/** Engraved-front-panel labels + heavier titles layered over the M3 defaults. */
private val InstrumentTypography = Typography().run {
    copy(
        labelSmall = labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
public fun DotnetNativeInteropTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstrumentDarkScheme,
        typography = InstrumentTypography,
        content = content,
    )
}
