package io.dotnetnativeinterop.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell

// Span color by the engine's dotted category prefix (pb./http./sqlite./ffi./rag./agent.).
internal fun spanColor(name: String): Color = when {
    name.startsWith("pb.") -> Instrument.transportBinary
    name.startsWith("http.") -> Instrument.transportHttp
    name.startsWith("sqlite.") || name.startsWith("broker.") -> Instrument.transportSqlite
    name.startsWith("ffi.") -> Instrument.transportFfi
    name.startsWith("rag.") -> Instrument.accent
    // Foreman agent turn/tool spans (additive category) — a NEW shared token: iOS must mirror this exact
    // hex (see Instrument.agent in ui/Theme.kt) for the same agent.* spans in its own trace waterfall.
    name.startsWith("agent.") -> Instrument.agent
    else -> Instrument.textSecondary
}

/**
 * Analysis · Trace: drains the engine span ring (`dni_trace_drain`) and renders a per-request waterfall.
 * These are ENGINE-side spans (µs offsets from engine boot); client-side spans are not in the ring, so
 * the caption states the cross-side alignment tolerance honestly. Ring overflow is disclosed — the
 * dropped-span count is shown, never silently swallowed.
 */
@Composable
internal fun TraceScreen(
    modifier: Modifier = Modifier,
    vm: TraceViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Column(
        modifier
            .fillMaxWidth()
            .background(Instrument.bg0)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PanelHeader("Trace · engine span waterfall")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.drain() }, enabled = !state.loading) { Text("Drain ring") }
            Spacer(Modifier.weight(1f))
            StatCell("SPANS", state.lastDrainCount.toString())
            Spacer(Modifier.width(Spacing.l))
            StatCell("CAPACITY", state.capacity.toString())
        }

        if (state.droppedThisDrain > 0 || state.droppedTotal > 0) {
            Text(
                "⚠ ${state.droppedThisDrain} span(s) dropped to ring overflow this drain " +
                    "(${state.droppedTotal} total) — the 512-span ring is drop-oldest, disclosed here.",
                color = Instrument.warn,
                fontSize = 11.sp,
            )
        }

        state.error?.let { ErrorBanner(message = it, onRetry = { vm.drain() }) }

        if (state.requestIds.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                FilterChip(
                    selected = state.selectedRequestId == null,
                    onClick = { vm.selectRequest(null) },
                    label = { Text("all") },
                )
                state.requestIds.forEach { id ->
                    FilterChip(
                        selected = state.selectedRequestId == id,
                        onClick = { vm.selectRequest(id) },
                        label = { Text(id.take(8)) },
                    )
                }
            }
        }

        val spans = state.visibleSpans
        if (spans.isEmpty()) {
            if (!state.loading && state.error == null) {
                Text(
                    "No spans. Make a Boundary/Compare/Latency run (any transport), then Drain the ring.",
                    color = Instrument.textSecondary,
                )
            }
        } else {
            InstrumentCard {
                PanelHeader("waterfall · engine-side (µs from boot)")
                SpanWaterfall(spans)
                Text(
                    "Engine-side spans only; client-side spans align to ±the drain round-trip.",
                    color = Instrument.textTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Reused by the Foreman tool-call strip's "expand into waterfall" (see `agent.ForemanScreen`) — same
 *  rendering, same span-color legend, no duplicated waterfall code. */
@Composable
internal fun SpanWaterfall(spans: List<TraceSpan>) {
    val minStart = spans.minOf { it.startUs }
    val maxEnd = spans.maxOf { it.startUs + it.durUs }
    val span = (maxEnd - minStart).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
        spans.forEach { s ->
            val offsetFrac = ((s.startUs - minStart) / span).toFloat().coerceIn(0f, 1f)
            val widthFrac = (s.durUs / span).toFloat().coerceIn(0f, 1f)
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Instrument.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.1f µs".format(s.durUs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Instrument.textSecondary,
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                    val track = maxWidth
                    Box(
                        Modifier
                            .padding(start = track * offsetFrac)
                            .width((track * widthFrac).coerceAtLeast(2.dp))
                            .height(12.dp)
                            .background(spanColor(s.name), RoundedCornerShape(Radii.chip)),
                    )
                }
            }
        }
    }
}
