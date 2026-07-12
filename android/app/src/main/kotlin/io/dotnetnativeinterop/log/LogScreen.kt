package io.dotnetnativeinterop.log

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Log level → signal color: Error/Critical = red, Warning = amber, everything else = neutral. Uses the
 *  SAME shared tokens the Trace legend does (iOS mirrors this exact mapping in LogView.swift). */
internal fun levelColor(level: String): Color = when (level) {
    "Critical", "Error" -> Instrument.fail
    "Warning" -> Instrument.warn
    else -> Instrument.textSecondary
}

/**
 * Analysis · Log: drains the engine log ring (`dni_log_drain`) and renders the captured records — the
 * logging leg of the observability trio. This is where the errors the FFI boundary would otherwise
 * swallow silently (e.g. a token drain that ends abnormally) become visible. Ring overflow is disclosed —
 * the dropped-record count is shown, never silently swallowed (mirrors the Trace waterfall).
 */
@Composable
internal fun LogScreen(
    modifier: Modifier = Modifier,
    vm: LogViewModel = viewModel(),
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
        PanelHeader("Log · engine diagnostics")

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.drain() }, enabled = !state.loading) { Text("Drain ring") }
            Spacer(Modifier.weight(1f))
            StatCell("RECORDS", state.lastDrainCount.toString())
            Spacer(Modifier.width(Spacing.l))
            StatCell("CAPACITY", state.capacity.toString())
        }

        if (state.droppedThisDrain > 0 || state.droppedTotal > 0) {
            Text(
                "⚠ ${state.droppedThisDrain} record(s) dropped to ring overflow this drain " +
                    "(${state.droppedTotal} total) — the ${state.capacity}-record ring is drop-oldest, disclosed here.",
                color = Instrument.warn,
                fontSize = 11.sp,
            )
        }

        state.error?.let { ErrorBanner(message = it, onRetry = { vm.drain() }) }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            LogFilter.entries.forEach { f ->
                FilterChip(
                    selected = state.filter == f,
                    onClick = { vm.selectFilter(f) },
                    label = { Text(f.label) },
                )
            }
        }

        val records = state.visibleRecords
        if (records.isEmpty()) {
            if (!state.loading && state.error == null) {
                Text(
                    if (state.hiddenByFilter > 0) {
                        "No records at this severity (${state.hiddenByFilter} hidden by the filter)."
                    } else {
                        "No log records. Run a session/agent turn (or cancel one mid-stream), then Drain the ring."
                    },
                    color = Instrument.textSecondary,
                )
            }
        } else {
            InstrumentCard {
                PanelHeader("records · engine-side (Information and above)")
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
                    records.forEach { LogRow(it) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(record: LogRecord) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Level badge — colored pill so Errors/Warnings jump out of the neutral stream.
            Text(
                record.level.uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Instrument.bg0,
                modifier = Modifier
                    .background(levelColor(record.level), RoundedCornerShape(Radii.chip))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                record.category,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Instrument.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%.0f µs".format(record.timestampUs),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Instrument.textTertiary,
            )
        }
        Text(record.message, style = MaterialTheme.typography.bodySmall, color = Instrument.textPrimary)
        // The exception detail the FFI boundary used to swallow — surfaced in the level color so a failure
        // reads as a failure.
        record.exception?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor(record.level),
                fontSize = 11.sp,
            )
        }
    }
}
