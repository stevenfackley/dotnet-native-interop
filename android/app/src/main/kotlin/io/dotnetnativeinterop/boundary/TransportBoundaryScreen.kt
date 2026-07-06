package io.dotnetnativeinterop.boundary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.feature.defaultServiceFor
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.trace.TraceDrain
import io.dotnetnativeinterop.trace.TraceSpan
import io.dotnetnativeinterop.transport.NativeBridge
import io.dotnetnativeinterop.transport.pb.PbTransport
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val transportTraceJson = Json { ignoreUnknownKeys = true }

// The engine span-name prefixes that belong to each transport's server-side path (see EngineTrace).
private fun categoryPrefixes(transport: TransportKind): List<String> = when (transport) {
    TransportKind.Http -> listOf("http.")
    TransportKind.Sqlite -> listOf("sqlite.", "broker.")
    TransportKind.Binary -> listOf("pb.")
    TransportKind.Ffi -> listOf("ffi.")
}

// Honest per-transport preset availability (spec: throw is FFI-only; pixels capped on SQLCipher).
private fun availabilityNote(transport: TransportKind): String = when (transport) {
    TransportKind.Sqlite -> "‘throw’ is FFI-only; ‘pixels’ is capped on SQLCipher (row-size reality). Errors surface as protocol-level payloads, not fake successes."
    else -> "‘throw’ is FFI-only here; other presets surface engine errors as protocol-level payloads, not faked."
}

/**
 * The per-transport Boundary view (HTTP / SQLCipher / Binary): the transport's honest hop swimlane plus a
 * real run — client round-trip is measured, and the engine-side phases come from the trace span ring
 * (dni_trace_drain), filtered to this transport's category. Where the span ring is empty (e.g. libdni.so
 * predates Wave B tracing) the server-side lanes render as an honest "awaiting engine" state, never a
 * fabricated number. FFI keeps its own richer phase screen (BoundaryScreen).
 */
@Composable
internal fun TransportBoundaryScreen(transport: TransportKind, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var preset by remember(transport) { mutableStateOf("ping") }
    var running by remember { mutableStateOf(false) }
    var clientMs by remember(transport) { mutableStateOf<Double?>(null) }
    var serverSpans by remember(transport) { mutableStateOf<List<TraceSpan>>(emptyList()) }
    var drained by remember(transport) { mutableStateOf(false) }
    var error by remember(transport) { mutableStateOf<String?>(null) }

    val secure = if (transport == TransportKind.Binary) PbTransport.isSecure else false
    val hops = transportHops(transport, secure)

    fun runPreset() {
        scope.launch {
            running = true; error = null
            val service = defaultServiceFor(transport)
            val id = if (preset == "ping") "ping"
            else runCatching { service.descriptors().firstOrNull()?.id }.getOrNull()

            if (id == null) {
                error = "No feature available over ${transport.displayName} (catalog unreachable)."
                clientMs = null
            } else {
                val start = System.nanoTime()
                runCatching { service.run(id) }
                    .onSuccess { result ->
                        clientMs = (System.nanoTime() - start) / 1_000_000.0
                        error = if (!result.ok) "engine returned an error payload: ${result.result}" else null
                    }
                    .onFailure { e ->
                        clientMs = null
                        error = "‘$preset’ over ${transport.displayName} failed: ${e.message ?: e.javaClass.simpleName}"
                    }
            }

            // Drain the engine span ring and keep only this transport's server-side spans.
            val raw = withContext(Dispatchers.IO) { NativeBridge.nativeTraceDrain() }
            serverSpans = raw
                ?.let { runCatching { transportTraceJson.decodeFromString<TraceDrain>(it).spans }.getOrNull() }
                ?.filter { span -> categoryPrefixes(transport).any { span.name.startsWith(it) } }
                ?: emptyList()
            drained = true
            running = false
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Instrument.bg0)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PanelHeader("Boundary · ${transport.displayName} transport")
        Text(transport.mechanism, color = Instrument.textSecondary, fontSize = 12.sp)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf("ping", "feature").forEach { p ->
                FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p) })
            }
        }
        Text(availabilityNote(transport), color = Instrument.textTertiary, fontSize = 10.sp)

        Button(onClick = { runPreset() }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Running over ${transport.displayName}…" else "Run ‘$preset’")
        }
        if (running) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Instrument.accent)
            }
        }
        error?.let { ErrorBanner(message = it, onRetry = { runPreset() }) }

        if (transport == TransportKind.Binary) {
            Text(
                if (secure) "PQ channel active — the handshake lane runs on first connect (toggle in Trust)."
                else "Plaintext framed protobuf — enable the PQ channel in the Trust inspector to add the handshake lane.",
                color = if (secure) Instrument.ok else Instrument.textTertiary,
                fontSize = 10.sp,
            )
        }

        InstrumentCard {
            PanelHeader("hop swimlane")
            TransportSwimlane(transport = transport, hops = hops, clientRoundTripMs = clientMs)
        }

        InstrumentCard {
            PanelHeader("timing")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                StatCell("client round-trip", clientMs?.let { "%.3f ms".format(it) } ?: "—", Modifier.weight(1f), tint = Instrument.accent)
                StatCell("engine spans", if (drained) serverSpans.size.toString() else "—", Modifier.weight(1f))
            }
        }

        InstrumentCard {
            PanelHeader("engine-side spans · ${transport.displayName}")
            when {
                serverSpans.isNotEmpty() -> serverSpans.sortedBy { it.startUs }.forEach { span ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(span.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Instrument.textPrimary)
                        Text("%.1f µs".format(span.durUs), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Instrument.textSecondary)
                    }
                }
                drained -> Text(
                    "Awaiting engine spans — the span ring is empty for this transport. Needs libdni.so " +
                        "rebuilt with the Wave B tracing exports (dni_trace_drain); no fabricated timings shown.",
                    color = Instrument.warn,
                    fontSize = 11.sp,
                )
                else -> Text("Run a preset to drain this transport's engine spans.", color = Instrument.textTertiary, fontSize = 12.sp)
            }
        }
    }
}
