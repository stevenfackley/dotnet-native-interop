package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.lab.Benchmark
import io.dotnetnativeinterop.lab.BenchmarkChart
import io.dotnetnativeinterop.lab.BenchmarkPayload
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.components.TransportPicker
import kotlinx.coroutines.launch

@Composable
internal fun BenchmarkScreen(
    lab: LabViewModel,
    command: String,
    modifier: Modifier = Modifier,
) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    var payload by remember { mutableStateOf<BenchmarkPayload?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {
                scope.launch {
                    running = true
                    error = null
                    val r = lab.render(command)
                    when {
                        r == null -> error = "The native library returned no data."
                        else -> {
                            val p = Benchmark.decode(r.result)
                            if (p == null) error = "Could not decode benchmark JSON." else payload = p
                        }
                    }
                    running = false
                }
            },
            enabled = !running,
        ) {
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Running…")
                }
            } else {
                Text("Run benchmark")
            }
        }

        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "The benchmark executes inside the NativeAOT library and returns its series as JSON over the "
                + "selected transport.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        payload?.let { p ->
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            BenchmarkChart(p.series)
            Text("Summary", style = MaterialTheme.typography.titleMedium)
            p.summary.forEach { stat ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stat.label, style = MaterialTheme.typography.bodyMedium)
                    Text(stat.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
