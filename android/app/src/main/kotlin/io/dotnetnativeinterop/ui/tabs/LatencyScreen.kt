package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.LatencyStats
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.lab.BenchmarkChart
import io.dotnetnativeinterop.lab.BenchmarkPoint
import io.dotnetnativeinterop.lab.BenchmarkSeries
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell
import io.dotnetnativeinterop.ui.components.TransportDot
import io.dotnetnativeinterop.ui.components.TransportPicker
import io.dotnetnativeinterop.ui.components.transportColor
import kotlinx.coroutines.launch

/** One drill-down analysis under the Latency hub. Order + grouping mirror the iOS LatencyHubView. */
private enum class LatencyAnalysis(val title: String, val blurb: String) {
    Distribution("Distribution", "Fire N pings over one transport and chart the round-trip histogram."),
    TransportComparison("Transport comparison", "Ping all three transports; compare percentiles + CDF."),
    Jitter("Jitter over time", "Latency vs call index — cold→warm steady state and GC blips."),
    PayloadScaling("Payload scaling", "Round-trip an N-byte bench-echo over increasing N."),
    Telemetry("Engine telemetry", "Live NativeAOT runtime stats plus a quick latency sample."),
}

/**
 * The Latency tab: a hub of focused latency analyses + the live engine-telemetry screen, mirroring
 * the iOS LatencyHubView. Navigation is self-contained (matching the iOS NavigationStack) so the
 * AppShell stays a flat tab switch.
 */
@Composable
internal fun LatencyScreen(
    vm: LatencyViewModel,
    modifier: Modifier = Modifier,
) {
    var analysis by remember { mutableStateOf<LatencyAnalysis?>(null) }

    when (val current = analysis) {
        null -> LatencyHub(onOpen = { analysis = it }, modifier = modifier)
        else -> AnalysisScaffold(title = current.title, onBack = { analysis = null }, modifier = modifier) { inner ->
            when (current) {
                LatencyAnalysis.Distribution -> DistributionAnalysis(vm, inner)
                LatencyAnalysis.TransportComparison -> TransportComparisonAnalysis(vm, inner)
                LatencyAnalysis.Jitter -> JitterAnalysis(vm, inner)
                LatencyAnalysis.PayloadScaling -> PayloadScalingAnalysis(vm, inner)
                LatencyAnalysis.Telemetry -> TelemetryAnalysis(vm, inner)
            }
        }
    }
}

@Composable
private fun LatencyHub(onOpen: (LatencyAnalysis) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PanelHeader("Measure")
        listOf(
            LatencyAnalysis.Distribution,
            LatencyAnalysis.TransportComparison,
            LatencyAnalysis.Jitter,
            LatencyAnalysis.PayloadScaling,
        ).forEach { HubCard(it, onOpen) }

        PanelHeader("Runtime")
        HubCard(LatencyAnalysis.Telemetry, onOpen)
    }
}

@Composable
private fun HubCard(analysis: LatencyAnalysis, onOpen: (LatencyAnalysis) -> Unit) {
    InstrumentCard(modifier = Modifier.clickable { onOpen(analysis) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(analysis.title, style = MaterialTheme.typography.titleMedium, color = Instrument.textPrimary)
                Text(analysis.blurb, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = Instrument.textTertiary,
            )
        }
    }
}

@Composable
private fun AnalysisScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Instrument.textSecondary)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Instrument.textPrimary)
        }
        HorizontalDivider(thickness = 1.dp, color = Instrument.hairline)
        content(Modifier.fillMaxWidth().weight(1f))
    }
}

// --- Analyses ---------------------------------------------------------------

@Composable
private fun DistributionAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val transport by vm.transport.collectAsStateWithLifecycle()
    var series by remember { mutableStateOf(LatencyViewModel.Series()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sampleCount = 300

    fun measure() {
        scope.launch {
            running = true
            series = vm.pingSeries(sampleCount, transport)
            running = false
        }
    }

    AnalysisColumn(modifier) {
        TransportPicker(selected = transport, onSelect = vm::selectTransport)
        Caption("Each call round-trips a no-op ping over ${transport.displayName} — the histogram is pure transport cost.")
        Button(onClick = ::measure, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (running) "Measuring $sampleCount calls…" else "Measure $sampleCount pings")
        }
        Spinner(running)

        val samples = series.samples
        if (samples.isEmpty()) {
            if (series.failures > 0) {
                ErrorBanner("All ${series.failures} pings failed over ${transport.displayName} — no samples to chart.", onRetry = ::measure)
            }
        } else {
            val sorted = samples.sorted()
            InstrumentCard {
                PanelHeader(
                    if (series.failures > 0) "Distribution — ${samples.size}/$sampleCount · ${series.failures} failed"
                    else "Distribution — ${samples.size} calls",
                )
                HistogramChart(LatencyStats.bins(samples, 24), transportColor(transport))
            }
            InstrumentCard {
                PanelHeader("Stats (ms)")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatCell("min", ms3(sorted.first()), Modifier.weight(1f), tint = Instrument.ok)
                    StatCell("median", ms3(LatencyStats.percentile(sorted, 0.50)), Modifier.weight(1f))
                    StatCell("p95", ms3(LatencyStats.percentile(sorted, 0.95)), Modifier.weight(1f))
                    StatCell("max", ms3(sorted.last()), Modifier.weight(1f), tint = Instrument.warn)
                }
            }
        }
    }
}

@Composable
private fun TransportComparisonAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    var results by remember { mutableStateOf<Map<TransportKind, LatencyViewModel.Series>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val count = 200

    AnalysisColumn(modifier) {
        Caption("Fires $count pings over FFI, HTTP, and SQLCipher and compares the distributions.")
        Button(
            onClick = {
                scope.launch {
                    running = true
                    val collected = LinkedHashMap<TransportKind, LatencyViewModel.Series>()
                    for (t in TransportKind.entries) collected[t] = vm.pingSeries(count, t)
                    results = collected
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Comparing…" else "Compare all transports") }
        Spinner(running)

        val totalFailures = results.values.sumOf { it.failures }
        if (totalFailures > 0) {
            val breakdown = TransportKind.entries
                .mapNotNull { t -> results[t]?.failures?.takeIf { it > 0 }?.let { "${t.displayName}: $it" } }
                .joinToString()
            ErrorBanner("$totalFailures pings failed and are excluded — $breakdown")
        }

        if (results.isNotEmpty()) {
            InstrumentCard {
                PanelHeader("Percentiles + throughput")
                TransportKind.entries.forEach { t ->
                    val s = LatencyStats.summary(results[t]?.samples ?: emptyList())
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TransportDot(t)
                            val failed = results[t]?.failures ?: 0
                            if (failed > 0) {
                                Text("$failed failed", style = MaterialTheme.typography.labelSmall, color = Instrument.warn)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                            StatCell("p50", ms2(s.p50), Modifier.weight(1f))
                            StatCell("p95", ms2(s.p95), Modifier.weight(1f))
                            StatCell("p99", ms2(s.p99), Modifier.weight(1f))
                            StatCell("max", ms2(s.max), Modifier.weight(1f))
                        }
                        Text("${s.throughput.toInt()} calls/sec", style = MaterialTheme.typography.labelSmall, color = Instrument.textTertiary)
                    }
                }
            }
            InstrumentCard {
                PanelHeader("Distribution overlay (CDF)")
                BenchmarkChart(
                    TransportKind.entries.map { t ->
                        BenchmarkSeries(
                            name = t.displayName,
                            points = LatencyStats.cdf(results[t]?.samples ?: emptyList())
                                .map { BenchmarkPoint(it.value, it.fraction) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun JitterAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val transport by vm.transport.collectAsStateWithLifecycle()
    var series by remember { mutableStateOf(LatencyViewModel.Series()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val count = 400

    AnalysisColumn(modifier) {
        TransportPicker(selected = transport, onSelect = vm::selectTransport)
        Caption("Each point is one ping round-trip in order over ${transport.displayName}.")
        Button(
            onClick = { scope.launch { running = true; series = vm.pingSeries(count, transport); running = false } },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Sampling…" else "Sample $count sequential pings") }
        Spinner(running)

        val samples = series.samples
        if (series.failures > 0) {
            ErrorBanner(
                if (samples.isEmpty()) "All ${series.failures} pings failed over ${transport.displayName}."
                else "${series.failures} of $count pings failed — gaps are excluded from the line.",
            )
        }
        if (samples.isNotEmpty()) {
            InstrumentCard {
                PanelHeader("Latency over call index (ms)")
                BenchmarkChart(
                    listOf(
                        BenchmarkSeries(
                            name = transport.displayName,
                            points = samples.mapIndexed { i, ms -> BenchmarkPoint(i.toDouble(), ms) },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun PayloadScalingAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val transport by vm.transport.collectAsStateWithLifecycle()
    var points by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var failedSizes by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sizes = listOf("64 B" to 64, "1 KB" to 1_024, "16 KB" to 16_384, "256 KB" to 262_144, "1 MB" to 1_048_576)
    val reps = 5

    AnalysisColumn(modifier) {
        TransportPicker(selected = transport, onSelect = vm::selectTransport)
        Caption("Round-trips an N-byte bench-echo over ${transport.displayName}, averaged over $reps reps per size.")
        Button(
            onClick = {
                scope.launch {
                    running = true
                    val collected = ArrayList<Pair<String, Double>>()
                    val failed = ArrayList<String>()
                    for ((label, bytes) in sizes) {
                        var total = 0.0
                        var hits = 0
                        repeat(reps) {
                            vm.roundTripMs("bench-echo~bytes_$bytes", transport)?.let { total += it; hits++ }
                        }
                        if (hits > 0) collected.add(label to total / hits) else failed.add(label)
                    }
                    points = collected
                    failedSizes = failed
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Sweeping…" else "Sweep payload sizes") }
        Spinner(running)

        if (failedSizes.isNotEmpty()) {
            ErrorBanner("No successful round-trips for ${failedSizes.joinToString()} over ${transport.displayName} — excluded from the chart.")
        }
        if (points.isNotEmpty()) {
            InstrumentCard {
                PanelHeader("Latency vs payload size (ms)")
                CategoryBarChart(points, transportColor(transport))
            }
            InstrumentCard {
                PanelHeader("Values")
                points.forEach { (label, ms) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
                        Text("%.2f ms".format(ms), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Instrument.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshStats() }

    AnalysisColumn(modifier) {
        s.stats?.let { stats ->
            InstrumentCard {
                PanelHeader("Engine telemetry")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatCell("Heap", "${"%.1f".format(stats.heapBytes / 1_048_576.0)} MB", Modifier.weight(1f))
                    StatCell("Threads", "${stats.threadCount}", Modifier.weight(1f))
                    StatCell("CPUs", "${stats.processorCount}", Modifier.weight(1f))
                    StatCell("Uptime", "${"%.1f".format(stats.uptimeMs / 1000.0)} s", Modifier.weight(1f))
                }
                StatCell("GC gen0 / gen1 / gen2", "${stats.gcGen0} / ${stats.gcGen1} / ${stats.gcGen2}")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = { vm.refreshStats() }) { Text("Refresh") }
            Button(onClick = { vm.sample() }) { Text("Sample latency") }
        }

        Spinner(s.sampling)

        if (s.samplesMs.isNotEmpty()) {
            val xs = s.samplesMs.sorted()
            InstrumentCard {
                PanelHeader("Latency samples (n=${xs.size})")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatCell("Min", ms2(xs.first()), Modifier.weight(1f), tint = Instrument.ok)
                    StatCell("Median", ms2(xs[xs.size / 2]), Modifier.weight(1f))
                    StatCell("Max", ms2(xs.last()), Modifier.weight(1f), tint = Instrument.warn)
                }
            }
        }

        s.error?.let { ErrorBanner(it) }
    }
}

// --- Small shared building blocks -------------------------------------------

@Composable
private fun AnalysisColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) { content() }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
}

@Composable
private fun Spinner(running: Boolean) {
    if (running) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Instrument.accent)
        }
    }
}

private fun ms3(v: Double): String = "%.3f".format(v)
private fun ms2(v: Double): String = "%.2f".format(v)
