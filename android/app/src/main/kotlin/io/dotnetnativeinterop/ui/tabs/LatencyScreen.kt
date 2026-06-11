package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell

@Composable
internal fun LatencyScreen(
    vm: LatencyViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshStats() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        s.stats?.let { stats ->
            InstrumentCard {
                PanelHeader("Engine telemetry")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    StatCell(
                        label = "Heap",
                        value = "${"%.1f".format(stats.heapBytes / 1_048_576.0)} MB",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Threads",
                        value = "${stats.threadCount}",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "CPUs",
                        value = "${stats.processorCount}",
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Uptime",
                        value = "${"%.1f".format(stats.uptimeMs / 1000.0)} s",
                        modifier = Modifier.weight(1f),
                    )
                }
                StatCell(
                    label = "GC gen0 / gen1 / gen2",
                    value = "${stats.gcGen0} / ${stats.gcGen1} / ${stats.gcGen2}",
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = { vm.refreshStats() }) { Text("Refresh") }
            Button(onClick = { vm.sample() }) { Text("Sample latency") }
        }

        if (s.sampling) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = Instrument.accent,
            )
        }

        if (s.samplesMs.isNotEmpty()) {
            val xs = s.samplesMs.sorted()
            val min = xs.first()
            val max = xs.last()
            val median = xs[xs.size / 2]
            InstrumentCard {
                PanelHeader("Latency samples (n=${xs.size})")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                ) {
                    StatCell(
                        label = "Min",
                        value = "%.2f ms".format(min),
                        modifier = Modifier.weight(1f),
                        tint = Instrument.ok,
                    )
                    StatCell(
                        label = "Median",
                        value = "%.2f ms".format(median),
                        modifier = Modifier.weight(1f),
                    )
                    StatCell(
                        label = "Max",
                        value = "%.2f ms".format(max),
                        modifier = Modifier.weight(1f),
                        tint = Instrument.warn,
                    )
                }
            }
        }

        s.error?.let { ErrorBanner(it) }
    }
}
