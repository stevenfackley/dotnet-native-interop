package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.LatencyStats
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.lab.Benchmark
import io.dotnetnativeinterop.lab.BenchmarkChart
import io.dotnetnativeinterop.lab.BenchmarkPayload
import io.dotnetnativeinterop.lab.BenchmarkPoint
import io.dotnetnativeinterop.lab.BenchmarkSeries
import io.dotnetnativeinterop.lab.ChartAnnotation
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell
import io.dotnetnativeinterop.ui.components.TransportDot
import io.dotnetnativeinterop.ui.components.TransportPicker
import io.dotnetnativeinterop.ui.components.transportColor
import kotlin.math.log10
import kotlinx.coroutines.launch

/** One drill-down analysis under the Latency hub. Order + grouping mirror the iOS LatencyHubView. */
private enum class LatencyAnalysis(val title: String, val blurb: String) {
    Distribution("Distribution", "Fire N pings over one transport and chart the round-trip histogram."),
    TransportComparison("Transport comparison", "Ping every transport; compare percentiles + CDF."),
    Jitter("Jitter over time", "Latency vs call index — cold→warm steady state and GC blips."),
    PayloadScaling("Payload scaling", "Round-trip an N-byte bench-echo over increasing N."),
    RealPayload("Real payload", "bench-real (catalog / RAG context) across every transport, log-scale toggle."),
    GcLab("GC Lab", "Drive a bounded allocation storm and watch .NET's GC actually work."),
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
                LatencyAnalysis.RealPayload -> RealPayloadAnalysis(vm, inner)
                LatencyAnalysis.GcLab -> GcLabAnalysis(vm, inner)
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
            LatencyAnalysis.RealPayload,
        ).forEach { HubCard(it, onOpen) }

        PanelHeader("Runtime")
        HubCard(LatencyAnalysis.GcLab, onOpen)
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
            val median = LatencyStats.percentile(sorted, 0.50)
            val p95 = LatencyStats.percentile(sorted, 0.95)
            InstrumentCard {
                PanelHeader(
                    if (series.failures > 0) "Distribution — ${samples.size}/$sampleCount · ${series.failures} failed"
                    else "Distribution — ${samples.size} calls",
                )
                HistogramChart(
                    LatencyStats.bins(samples, 24),
                    transportColor(transport),
                    percentileMarkers = listOf(
                        PercentileMarker("P50", median, Instrument.textSecondary),
                        PercentileMarker("P95", p95, Instrument.warn),
                    ),
                )
            }
            InstrumentCard {
                PanelHeader("Stats")
                // One dominant metric above the fold (facelift spec §3); min/p95/max are supporting.
                StatCell("median round-trip", LatencyStats.formatLatencyMs(median), emphasis = true)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatCell("min", LatencyStats.formatLatencyMs(sorted.first()), Modifier.weight(1f), tint = Instrument.ok)
                    StatCell("p95", LatencyStats.formatLatencyMs(p95), Modifier.weight(1f), tint = Instrument.warn)
                    StatCell("max", LatencyStats.formatLatencyMs(sorted.last()), Modifier.weight(1f), tint = Instrument.warn)
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
        Caption("Fires $count pings over every transport (FFI · Binary · HTTP · SQLCipher) and compares the distributions.")
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
                            StatCell("p50", LatencyStats.formatLatencyMs(s.p50), Modifier.weight(1f))
                            StatCell("p95", LatencyStats.formatLatencyMs(s.p95), Modifier.weight(1f))
                            StatCell("p99", LatencyStats.formatLatencyMs(s.p99), Modifier.weight(1f))
                            StatCell("max", LatencyStats.formatLatencyMs(s.max), Modifier.weight(1f))
                        }
                        Text("${s.throughput.toInt()} calls/sec", style = MaterialTheme.typography.labelSmall, color = Instrument.textTertiary)
                    }
                }
            }
            InstrumentCard {
                PanelHeader("Distribution overlay (CDF)")
                // Transport-colored series, not an arbitrary palette (facelift spec §2/§5).
                BenchmarkChart(
                    TransportKind.entries.map { t ->
                        BenchmarkSeries(
                            name = t.displayName,
                            points = LatencyStats.cdf(results[t]?.samples ?: emptyList())
                                .map { BenchmarkPoint(it.value, it.fraction) },
                        )
                    },
                    colors = TransportKind.entries.map { transportColor(it) },
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
            // Annotate the tail (facelift spec §5); cap so the chart doesn't drown in ticks.
            val allOutliers = LatencyStats.outlierIndices(samples)
            val outliers = allOutliers.take(6)
            InstrumentCard {
                PanelHeader("Latency over call index (ms)")
                BenchmarkChart(
                    listOf(
                        BenchmarkSeries(
                            name = transport.displayName,
                            points = samples.mapIndexed { i, ms -> BenchmarkPoint(i.toDouble(), ms) },
                        ),
                    ),
                    colors = listOf(transportColor(transport)),
                    annotations = outliers.mapIndexed { idx, i ->
                        ChartAnnotation(i.toDouble(), if (idx == 0) "P99+" else "", Instrument.warn)
                    },
                )
                if (allOutliers.isNotEmpty()) {
                    val note = if (allOutliers.size > outliers.size) " (first ${outliers.size} marked)" else ""
                    Caption("${allOutliers.size} call(s) above p99$note — GC blips or cold-start spikes.")
                }
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
                        Text(LatencyStats.formatLatencyMs(ms), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Instrument.textPrimary)
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
                    StatCell("Min", LatencyStats.formatLatencyMs(xs.first()), Modifier.weight(1f), tint = Instrument.ok)
                    StatCell("Median", LatencyStats.formatLatencyMs(xs[xs.size / 2]), Modifier.weight(1f))
                    StatCell("Max", LatencyStats.formatLatencyMs(xs.last()), Modifier.weight(1f), tint = Instrument.warn)
                }
            }
        }

        s.error?.let { ErrorBanner(it) }
    }
}

// --- Real payload (bench-real) ----------------------------------------------

private data class RealPayloadRow(
    val transport: TransportKind,
    val roundTripMs: Double,
    val avgBytes: String?,
    val avgSerialize: String?,
)

@Composable
private fun RealPayloadAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val kinds = listOf("catalog", "ragctx", "mixed")
    var kind by remember { mutableStateOf("catalog") }
    var reps by remember { mutableIntStateOf(5) }
    var logScale by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<RealPayloadRow>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    var clampNote by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AnalysisColumn(modifier) {
        Caption(
            "Round-trips a REAL structured payload (catalog JSON / RAG context / both) over every " +
                "transport, $reps reps each. Transport cost is measured client-side — toggle log scale " +
                "to keep sub-millisecond FFI visible next to SQLCipher.",
        )
        ChipRow("Payload", kinds, kind) { kind = it }
        ChipRow("Reps", listOf("5", "10", "25"), reps.toString()) { reps = it.toInt() }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Log scale", style = MaterialTheme.typography.bodyMedium, color = Instrument.textPrimary)
            Spacer(Modifier.weight(1f))
            Switch(checked = logScale, onCheckedChange = { logScale = it })
        }
        Button(
            onClick = {
                scope.launch {
                    running = true
                    val collected = ArrayList<RealPayloadRow>()
                    val fails = ArrayList<String>()
                    var clamp: String? = null
                    for (t in TransportKind.entries) {
                        val timed = vm.runResult("bench-real~kind_${kind}~reps_$reps", t)
                        val payload = timed?.result?.takeIf { it.ok }?.let { Benchmark.decode(it.result) }
                        if (timed == null || payload == null) { fails.add(t.displayName); continue }
                        summaryValue(payload, "clamped")?.takeIf { it.startsWith("yes") }?.let { clamp = it }
                        collected.add(
                            RealPayloadRow(
                                transport = t,
                                roundTripMs = timed.roundTripMs,
                                avgBytes = summaryValue(payload, "avg bytes/rep"),
                                avgSerialize = summaryValue(payload, "avg serialize/rep"),
                            ),
                        )
                    }
                    rows = collected; failed = fails; clampNote = clamp; running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Running bench-real…" else "Run over all transports") }
        Spinner(running)

        if (failed.isNotEmpty()) {
            ErrorBanner("bench-real failed over ${failed.joinToString()} — excluded (needs that transport's engine support).")
        }
        clampNote?.let { Text("⚠ reps clamped by the engine: $it", color = Instrument.warn, fontSize = 11.sp) }

        if (rows.isNotEmpty()) {
            InstrumentCard {
                PanelHeader(if (logScale) "Client round-trip · log₁₀(ms+1)" else "Client round-trip (ms)")
                // Every bar IS a transport — color each one canonically, not one flat color for all
                // four (facelift spec §2/§5: transport colors on chart series, not decoration).
                CategoryBarChart(
                    rows.map { it.transport.displayName to if (logScale) log10(it.roundTripMs + 1.0) else it.roundTripMs },
                    Instrument.transportBinary,
                    colors = rows.map { transportColor(it.transport) },
                )
                Caption(
                    if (logScale) "Bars are log₁₀(ms+1) — a monotonic transform so sub-ms FFI stays visible; not raw ms."
                    else "Linear ms; FFI can look flat next to SQLCipher — toggle log scale to see it.",
                )
            }
            InstrumentCard {
                PanelHeader("Per-transport · $kind, $reps reps")
                rows.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TransportDot(row.transport)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                            StatCell("round-trip", LatencyStats.formatLatencyMs(row.roundTripMs), Modifier.weight(1f))
                            StatCell("avg bytes/rep", row.avgBytes ?: "—", Modifier.weight(1f))
                            StatCell("avg serialize", row.avgSerialize ?: "—", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// --- GC Lab (gclab) ---------------------------------------------------------

@Composable
private fun GcLabAnalysis(vm: LatencyViewModel, modifier: Modifier = Modifier) {
    val presets = listOf("gen0", "loh", "pinned")
    var preset by remember { mutableStateOf("gen0") }
    var secs by remember { mutableIntStateOf(5) }
    val mb = 64
    var payload by remember { mutableStateOf<BenchmarkPayload?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AnalysisColumn(modifier) {
        Caption(
            "Runs a bounded allocation storm in the engine (gen0 churn / LOH / GCHandle-pinned) and " +
                "plots .NET's GC working. mb=$mb, over FFI — the GC is engine-global, so the transport " +
                "is immaterial to what's measured.",
        )
        ChipRow("Preset", presets, preset) { preset = it }
        ChipRow("Seconds", listOf("3", "5", "10"), secs.toString()) { secs = it.toInt() }
        Button(
            onClick = {
                scope.launch {
                    running = true; error = null; payload = null
                    val timed = vm.runResult("gclab~preset_${preset}~mb_${mb}~secs_$secs", TransportKind.Ffi)
                    val result = timed?.result
                    when {
                        result == null -> error = "GC Lab call failed — the transport returned no result."
                        !result.ok -> error = result.result // e.g. "…gclab (already running…)" or unknown-command
                        else -> Benchmark.decode(result.result)
                            ?.let { payload = it }
                            ?: run { error = "Could not decode the GC Lab payload." }
                    }
                    running = false
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Running storm ($secs s)…" else "Run GC storm") }
        Spinner(running)
        error?.let { ErrorBanner(it) }

        payload?.let { p ->
            val mode = summaryValue(p, "collection mode") ?: "unknown"
            CollectionModeChip(forced = mode.startsWith("forced"), text = mode)
            summaryValue(p, "clamped")?.takeIf { it.startsWith("yes") }
                ?.let { Text("⚠ clamped by the engine: $it", color = Instrument.warn, fontSize = 11.sp) }

            InstrumentCard {
                PanelHeader("Heap / committed over time (MB)")
                // Heap is the story (primary), committed is supporting context (muted) — not accent,
                // which the facelift spec reserves for interaction/in-flight, not decoration. GC
                // collection give-backs are detected from the heap series and annotated.
                val heapPoints = p.series.firstOrNull { it.name == "heapMB" }?.points?.map { it.x to it.y } ?: emptyList()
                val gcEvents = LatencyStats.collectionEventXs(heapPoints).take(10)
                BenchmarkChart(
                    p.series,
                    colors = listOf(Instrument.textPrimary, Instrument.textTertiary),
                    annotations = gcEvents.mapIndexed { idx, x ->
                        ChartAnnotation(x, if (idx == 0) "GC" else "", Instrument.warn)
                    },
                )
                Caption("X = seconds, Y = MB. Committed staying above heap after a collection is the pin/LOH fragmentation.")
            }
            InstrumentCard {
                PanelHeader("Collections + pause")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    StatCell("gen0", summaryValue(p, "gen0 collections") ?: "—", Modifier.weight(1f))
                    StatCell("gen1", summaryValue(p, "gen1 collections") ?: "—", Modifier.weight(1f))
                    StatCell("gen2", summaryValue(p, "gen2 collections") ?: "—", Modifier.weight(1f))
                    StatCell("pause Δ", summaryValue(p, "GC pause Δ") ?: "—", Modifier.weight(1f), tint = Instrument.warn)
                }
                summaryValue(p, "heap before → after")?.let { StatCell("heap MB", it) }
                summaryValue(p, "committed before → after")?.let { StatCell("committed MB", it) }
                summaryValue(p, "allocated Δ")?.let { StatCell("allocated Δ", it) }
            }
        }
    }
}

/** The engine's forced-vs-organic disclosure as a first-class chip — never present forced GC as organic. */
@Composable
private fun CollectionModeChip(forced: Boolean, text: String) {
    val tint = if (forced) Instrument.warn else Instrument.ok
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (forced) "COLLECTION MODE · FORCED" else "COLLECTION MODE · ORGANIC",
            modifier = Modifier
                .background(tint.copy(alpha = 0.14f), RoundedCornerShape(Radii.chip))
                .padding(horizontal = Spacing.s, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
    }
}

/** First summary stat whose label starts with [labelPrefix] (labels carry "(incl. forced)"/"(cold start)" suffixes). */
private fun summaryValue(payload: BenchmarkPayload, labelPrefix: String): String? =
    payload.summary.firstOrNull { it.label.startsWith(labelPrefix) }?.value

// --- Small shared building blocks -------------------------------------------

/** A labelled row of single-choice filter chips (payload kind, reps, preset, seconds). */
@Composable
private fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Instrument.textTertiary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

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
