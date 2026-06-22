package io.dotnetnativeinterop.boundary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryInspector
import io.dotnetnativeinterop.model.BoundaryPreset
import io.dotnetnativeinterop.model.BoundaryThrow
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell

/** The panel below the swimlane for the selected inspector segment. */
@Composable
internal fun BoundaryInspectorPanel(state: BoundaryUiState, modifier: Modifier = Modifier) {
    InstrumentCard(modifier) {
        when (state.inspector) {
            BoundaryInspector.Bytes -> BytesPanel(state.echo)
            BoundaryInspector.Timing -> TimingPanel(state)
            BoundaryInspector.Memory -> MemoryPanel(state)
            BoundaryInspector.Threads -> ThreadsPanel(state)
            BoundaryInspector.Abi -> AbiPanel(state.preset)
            BoundaryInspector.Error -> ErrorPanel(state.thrown)
        }
    }
}

private fun us(d: Double): String = "%.1f µs".format(d)

@Composable
private fun mono(text: String, color: androidx.compose.ui.graphics.Color = Instrument.textSecondary, size: Int = 11) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color)
}

@Composable
private fun BytesPanel(echo: BoundaryEcho?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("bytes · marshalling")
        if (echo != null) {
            mono(echo.bytesHex.chunked(2).joinToString(" "), Instrument.accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                StatCell("decoded", "\"${echo.decoded.take(20)}\"", Modifier.weight(1f))
                StatCell("len", "${echo.len} B", Modifier.weight(1f))
                StatCell("ptr in", echo.ptrIn, Modifier.weight(1f))
            }
        } else {
            Text("Run echo to inspect the UTF-8 bytes.", color = Instrument.textTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TimingPanel(state: BoundaryUiState) {
    val t = state.timing
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("µs · per phase")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatCell("marshal", us(t.marshalUs), Modifier.weight(1f))
            StatCell("cross", us(t.crossUs), Modifier.weight(1f))
            StatCell("execute·native", us(t.executeUs), Modifier.weight(1f), tint = Instrument.ok)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatCell("callback", us(t.callbackUs), Modifier.weight(1f))
            StatCell("free", us(t.freeUs), Modifier.weight(1f))
            StatCell("total", us(t.totalUs), Modifier.weight(1f), tint = Instrument.accent)
        }
        Text(
            "marshal/cross/free are frontend-measured; execute is native (dni reports executeUs).",
            color = Instrument.textTertiary, fontSize = 10.sp,
        )
    }
}

@Composable
private fun MemoryPanel(state: BoundaryUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("memory · ownership ledger")
        state.ledger.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                mono(row.buffer, Instrument.textPrimary)
                Text("${row.bytes} B", color = Instrument.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                mono(if (row.freed) "freed" else "leaked", if (row.freed) Instrument.ok else Instrument.fail, 10)
            }
            Text("alloc ${row.allocatedBy} → free ${row.freedBy}", color = Instrument.textTertiary, fontSize = 10.sp)
        }
        HorizontalDivider(color = Instrument.hairline)
        Text(
            "On Android the JNI shim frees the result string eagerly (take_native_string → dni_string_free), " +
                "so ownership is shown here, not leaked.",
            color = Instrument.textTertiary, fontSize = 10.sp,
        )
    }
}

@Composable
private fun ThreadsPanel(state: BoundaryUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("threads · the hop")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatCell("caller (UI)", "#${state.callerThreadId}", Modifier.weight(1f))
            StatCell("managed callback", state.callbackThreadId?.let { "#$it" } ?: "—", Modifier.weight(1f), tint = Instrument.warn)
        }
        Text(
            "⚠ Callback fires on a .NET worker thread → AttachCurrentThread → posts to the main thread.",
            color = Instrument.warn, fontSize = 11.sp,
        )
        if (state.streamTokens.isNotEmpty()) {
            mono("${state.streamTokens.size} tokens · last @ ${state.streamTokens.last().elapsedUs} µs")
        }
    }
}

@Composable
private fun AbiPanel(preset: BoundaryPreset) {
    // (C ABI, Kotlin/JNI, note). [UnmanagedCallersOnly]/delegate* unmanaged: .NET 5+/C# 9+; RegisterNatives binds names.
    val rows: List<Triple<String, String, String>> = when (preset) {
        BoundaryPreset.Stream -> listOf(
            Triple("dni_ffi_stream_start(const char*, int32_t, dni_trace_cb, void*)",
                "external fun nativeFfiStreamStart(String, Int, FfiTraceListener): Long", "session id > 0"),
            Triple("dni_trace_cb(void*, int32_t, const char*, int32_t, int64_t, int64_t)",
                "FfiTraceListener.onTrace(Int, String, Long, Long, Boolean)", "extended: +threadId +elapsedUs"),
        )
        BoundaryPreset.Exception -> listOf(
            Triple("dni_ffi_throw(void) -> const char*", "external fun nativeFfiThrow(): String?", "{caught,type,message,status}"),
        )
        else -> listOf(
            Triple("dni_ffi_echo(const char*, int32_t) -> const char*",
                "external fun nativeFfiEcho(String): String?", "JNI frees via dni_string_free"),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("ABI · C ⇄ Kotlin/JNI")
        rows.forEach { (c, kt, note) ->
            Column {
                mono(c, Instrument.accent, 10)
                mono(kt, Instrument.textSecondary, 10)
                Text(note, color = Instrument.textTertiary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ErrorPanel(thrown: BoundaryThrow?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        PanelHeader("error · containment")
        if (thrown != null) {
            Text("✓ contained at the boundary — no crash", color = Instrument.ok, fontSize = 12.sp)
            StatCell("type", thrown.type)
            StatCell("status", "${thrown.status}", tint = Instrument.warn)
            Text(thrown.message, color = Instrument.textSecondary, fontSize = 12.sp)
        } else {
            Text(
                "Run the throw preset: a managed exception is caught at the ABI and returned as a status.",
                color = Instrument.textTertiary, fontSize = 12.sp,
            )
        }
    }
}
