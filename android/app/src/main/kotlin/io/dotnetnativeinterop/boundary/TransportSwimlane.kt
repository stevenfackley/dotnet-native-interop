package io.dotnetnativeinterop.boundary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.transportColor

/** Which side of the managed↔native boundary a hop sits on (drives colour + honesty labelling). */
internal enum class HopSide { Client, Wire, Net }

/**
 * One lane in a transport's request path. [cadenceMs] is set only for hops whose latency is a KNOWN,
 * to-scale cost (the SQLCipher broker poll's ~50 ms cadence — the latency is the lesson). All other
 * hops are topology, drawn as uniform markers, not fabricated per-hop timings.
 */
internal data class Hop(val label: String, val side: HopSide, val cadenceMs: Double? = null)

/**
 * The actual hops per transport, mirroring the spec's "Boundary × transports — swimlanes". The Android
 * JNI-shim lane is intentionally absent here: for HTTP/SQLCipher/Binary the DATA path is a socket/file
 * (JNI only starts the server), so JNI is genuinely on the path for FFI alone. The Binary transport
 * inserts a PQ-handshake lane only when the secure channel is actually negotiated ([secure]).
 */
internal fun transportHops(transport: TransportKind, secure: Boolean): List<Hop> = when (transport) {
    TransportKind.Ffi -> emptyList() // FFI keeps its own phase swimlane (SwimlaneCanvas)
    TransportKind.Http -> listOf(
        Hop("UI thread", HopSide.Client),
        Hop("OkHttp", HopSide.Client),
        Hop("loopback socket", HopSide.Wire),
        Hop("raw parser · .NET", HopSide.Net),
        Hop("engine", HopSide.Net),
        Hop("SSE back", HopSide.Wire),
    )
    TransportKind.Sqlite -> listOf(
        Hop("UI thread", HopSide.Client),
        Hop("client SQLite", HopSide.Client),
        Hop("encrypted DB file", HopSide.Wire),
        Hop("broker poll · .NET", HopSide.Net, cadenceMs = 50.0), // ~50 ms poll cadence, to scale
        Hop("engine", HopSide.Net),
        Hop("response rows", HopSide.Wire),
    )
    TransportKind.Binary -> buildList {
        add(Hop("UI thread", HopSide.Client))
        add(Hop("pb stubs", HopSide.Client))
        add(Hop("framed socket", HopSide.Wire))
        if (secure) add(Hop("PQ handshake", HopSide.Wire)) // first-connect ML-KEM/ML-DSA lane
        add(Hop("pb decode · .NET", HopSide.Net))
        add(Hop("engine", HopSide.Net))
    }
}

private fun sideColor(side: HopSide, transport: TransportKind): Color = when (side) {
    HopSide.Client -> Instrument.accent
    HopSide.Wire -> Instrument.textSecondary
    HopSide.Net -> transportColor(transport)
}

/**
 * Draws a transport's hop topology as a swimlane, reusing the FFI SwimlaneCanvas visual language. Only
 * the known cadence hop (SQLCipher's ~50 ms poll) is drawn to scale against [clientRoundTripMs]; the
 * other hops are uniform topology markers (honestly captioned) — no fabricated per-hop numbers.
 */
@Composable
internal fun TransportSwimlane(
    transport: TransportKind,
    hops: List<Hop>,
    clientRoundTripMs: Double?,
    modifier: Modifier = Modifier,
) {
    // Scale reference: the biggest of the client round-trip and any known cadence, so the ~50 ms poll
    // lane visibly dominates when SQLCipher is the transport.
    val scaleMs = maxOf(clientRoundTripMs ?: 0.0, hops.mapNotNull { it.cadenceMs }.maxOrNull() ?: 0.0, 1.0)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        hops.forEach { hop ->
            val color = sideColor(hop.side, transport)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    hop.label,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = color,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(104.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .background(Instrument.bg2, RoundedCornerShape(3.dp))
                        .border(1.dp, Instrument.hairline, RoundedCornerShape(3.dp))
                        .padding(2.dp),
                ) {
                    if (hop.cadenceMs != null) {
                        // The one to-scale lane: fill proportional to the cadence vs the scale reference.
                        val fraction = (hop.cadenceMs / scaleMs).toFloat().coerceIn(0.02f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(12.dp)
                                .background(color.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
                        )
                        Text(
                            "~${hop.cadenceMs.toInt()} ms poll (to scale)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Instrument.warn,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.xs),
                        )
                    } else {
                        // Topology marker — a short fixed tick, NOT a measured duration.
                        Box(
                            Modifier
                                .width(18.dp)
                                .height(12.dp)
                                .background(color.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
        Text(
            "Lanes are the transport's hop topology; only SQLCipher's poll cadence is drawn to scale. " +
                "Client round-trip is measured; per-hop server timings come from the engine spans below.",
            color = Instrument.textTertiary,
            fontSize = 10.sp,
        )
    }
}
