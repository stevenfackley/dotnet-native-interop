package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.LatencyViewModel

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Telemetry card
        s.stats?.let { stats ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Engine telemetry", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Heap: ${"%.1f".format(stats.heapBytes / 1_048_576.0)} MB")
                    Text("GC gen0 / gen1 / gen2: ${stats.gcGen0} / ${stats.gcGen1} / ${stats.gcGen2}")
                    Text("Threads: ${stats.threadCount}  Processors: ${stats.processorCount}")
                    Text("Uptime: ${"%.1f".format(stats.uptimeMs / 1000.0)} s")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshStats() }) { Text("Refresh") }
            Button(onClick = { vm.sample() }) { Text("Sample latency") }
        }

        if (s.sampling) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (s.samplesMs.isNotEmpty()) {
            val xs = s.samplesMs.sorted()
            val min = xs.first()
            val max = xs.last()
            val median = xs[xs.size / 2]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Latency samples (n=${xs.size})", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Min:    ${"%.2f ms".format(min)}")
                    Text("Median: ${"%.2f ms".format(median)}")
                    Text("Max:    ${"%.2f ms".format(max)}")
                }
            }
        }

        if (s.error != null) {
            Text(
                text = s.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
