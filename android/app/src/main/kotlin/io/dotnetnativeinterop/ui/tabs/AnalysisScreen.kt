package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.dotnetnativeinterop.feature.ComparisonViewModel
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.feature.LatencyViewModel
import io.dotnetnativeinterop.ui.Spacing

/**
 * Analysis hub (IA collapse spec 2 of 3): the neutral performance-evidence area. Merges the former
 * Dashboard (aggregate run/pass stats), Compare (transport comparison), and Latency tabs behind one
 * segmented control. Live engine telemetry already lives inside the Latency hub's "Runtime" section
 * (see LatencyScreen's TelemetryAnalysis), so it needs no separate destination here. Existing view
 * bodies are reused unchanged.
 */
@Composable
internal fun AnalysisScreen(
    features: FeaturesViewModel,
    comparison: ComparisonViewModel,
    latency: LatencyViewModel,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableIntStateOf(0) }
    val labels = listOf("Dashboard", "Compare", "Latency")

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = section == i,
                    onClick = { section = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label) }
            }
        }

        val inner = Modifier.fillMaxWidth().weight(1f)
        when (section) {
            0 -> DashboardScreen(features, inner)
            1 -> CompareScreen(comparison, inner)
            else -> LatencyScreen(latency, inner)
        }
    }
}
