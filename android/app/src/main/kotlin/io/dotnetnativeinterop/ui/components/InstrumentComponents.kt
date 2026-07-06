package io.dotnetnativeinterop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing

/** Canonical transport color, mirrored on iOS (`Instrument.transport(_:)`). */
internal fun transportColor(kind: TransportKind): Color = when (kind) {
    TransportKind.Ffi -> Instrument.transportFfi
    TransportKind.Binary -> Instrument.transportBinary
    TransportKind.Http -> Instrument.transportHttp
    TransportKind.Sqlite -> Instrument.transportSqlite
}

/** Card surface: bg1 fill + hairline border + card radius, the iOS `.instrumentCard()` analog. */
@Composable
internal fun InstrumentCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Instrument.bg1, RoundedCornerShape(Radii.card))
            .border(1.dp, Instrument.hairline, RoundedCornerShape(Radii.card))
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        content = content,
    )
}

/** Engraved-front-panel section label: monospaced caps with a trailing hairline rule. */
@Composable
internal fun PanelHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.textSecondary,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = Instrument.hairline,
        )
    }
}

/** Label-over-readout cell used in telemetry strips and dashboards. */
@Composable
internal fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = Instrument.textPrimary,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.textTertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
    }
}

/** Small colored dot + name, the canonical transport identifier across all screens. */
@Composable
internal fun TransportDot(kind: TransportKind, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(transportColor(kind), CircleShape),
        )
        Text(
            kind.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.textSecondary,
        )
    }
}

/** Visible, contextual failure surface. Never let an error die in a blank screen. */
@Composable
internal fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Instrument.fail.copy(alpha = 0.12f), RoundedCornerShape(Radii.card))
            .border(1.dp, Instrument.fail.copy(alpha = 0.4f), RoundedCornerShape(Radii.card))
            .padding(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = Instrument.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Instrument.fail.copy(alpha = 0.25f),
                    contentColor = Instrument.textPrimary,
                ),
            ) { Text("Retry") }
        }
    }
}
