package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.feature.LatencyBin
import io.dotnetnativeinterop.feature.LatencyStats
import io.dotnetnativeinterop.ui.Instrument

/**
 * Canvas charts for the Latency tab — vertical-bar histogram (Distribution) and a labelled category
 * bar chart (Payload scaling). Drawn by hand, no chart dependency, matching the Lab's BenchmarkChart
 * convention. Line/CDF charts reuse `io.dotnetnativeinterop.lab.BenchmarkChart`. Both charts stand
 * ≥240dp tall (facelift spec §3 density rule: "don't squash the tail").
 */

/** A median/p95/p99-style marker drawn as a dashed vertical line + label on [HistogramChart]
 *  (facelift spec §5: percentile overlay on the distribution, evidence not ornament). */
internal data class PercentileMarker(val label: String, val value: Double, val color: Color = Instrument.warn)

/** Vertical-bar histogram of latency bins (midpoint ms on X, sample count on Y). */
@Composable
internal fun HistogramChart(
    bins: List<LatencyBin>,
    color: Color,
    modifier: Modifier = Modifier,
    percentileMarkers: List<PercentileMarker> = emptyList(),
) {
    if (bins.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxCount = bins.maxOf { it.count }.coerceAtLeast(1)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Instrument.textTertiary, fontSize = 10.sp)
    val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)

    // Bin domain matches LatencyStats.bins: samples.min()..samples.max(), reconstructed from midpoints.
    val binWidth = if (bins.size > 1) bins[1].midpoint - bins[0].midpoint else 0.0
    val domainLow = bins.first().midpoint - binWidth / 2.0
    val domainSpan = (bins.last().midpoint + binWidth / 2.0 - domainLow).coerceAtLeast(1e-9)

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
        val padL = 40f
        val padB = 24f
        val padT = 18f
        val padR = 10f
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val slot = w / bins.size
        val barW = slot * 0.8f

        drawLine(Instrument.hairline, Offset(padL, padT), Offset(padL, padT + h), strokeWidth = 2f)
        drawLine(Instrument.hairline, Offset(padL, padT + h), Offset(padL + w, padT + h), strokeWidth = 2f)

        bins.forEachIndexed { i, b ->
            val barH = (b.count.toFloat() / maxCount) * h
            val x = padL + i * slot + (slot - barW) / 2f
            drawRect(color, topLeft = Offset(x, padT + h - barH), size = Size(barW, barH))
        }

        percentileMarkers.forEach { marker ->
            val frac = ((marker.value - domainLow) / domainSpan).coerceIn(0.0, 1.0)
            val x = padL + frac.toFloat() * w
            drawLine(marker.color, Offset(x, padT), Offset(x, padT + h), strokeWidth = 2f, pathEffect = dashed)
            drawText(measurer, marker.label, Offset((x - 8f).coerceAtLeast(padL), 2f), TextStyle(color = marker.color, fontSize = 9.sp))
        }

        drawText(measurer, maxCount.toString(), Offset(2f, padT - 4f), labelStyle)
        drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
        drawText(measurer, LatencyStats.formatLatencyMs(bins.first().midpoint), Offset(padL, padT + h + 4f), labelStyle)
        drawText(measurer, LatencyStats.formatLatencyMs(bins.last().midpoint), Offset(padL + w - 44f, padT + h + 4f), labelStyle)
    }
}

/**
 * Labelled category bar chart: one bar per (label, value) pair with the label drawn under it.
 * [colors], when supplied, overrides [color] one-for-one by index — pass the canonical transport
 * colors when each bar IS a transport (facelift spec §2/§5: transport colors on chart series, never
 * one arbitrary color painted over every transport's bar).
 */
@Composable
internal fun CategoryBarChart(
    points: List<Pair<String, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
) {
    if (points.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxY = points.maxOf { it.second }.coerceAtLeast(1e-9)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Instrument.textTertiary, fontSize = 10.sp)

    Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
        val padL = 44f
        val padB = 26f
        val padT = 10f
        val padR = 10f
        val w = size.width - padL - padR
        val h = size.height - padT - padB
        val slot = w / points.size
        val barW = slot * 0.6f

        drawLine(Instrument.hairline, Offset(padL, padT), Offset(padL, padT + h), strokeWidth = 2f)
        drawLine(Instrument.hairline, Offset(padL, padT + h), Offset(padL + w, padT + h), strokeWidth = 2f)

        points.forEachIndexed { i, (label, value) ->
            val barH = (value / maxY * h).toFloat()
            val x = padL + i * slot + (slot - barW) / 2f
            val barColor = colors?.getOrNull(i) ?: color
            drawRect(barColor, topLeft = Offset(x, padT + h - barH), size = Size(barW, barH))
            drawText(measurer, label, Offset(padL + i * slot + 2f, padT + h + 4f), labelStyle)
        }

        drawText(measurer, String.format(java.util.Locale.US, "%.1f", maxY), Offset(2f, padT - 4f), labelStyle)
        drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
    }
}
