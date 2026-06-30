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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.feature.LatencyBin
import io.dotnetnativeinterop.ui.Instrument

/**
 * Canvas charts for the Latency tab — vertical-bar histogram (Distribution) and a labelled category
 * bar chart (Payload scaling). Drawn by hand, no chart dependency, matching the Lab's BenchmarkChart
 * convention. Line/CDF charts reuse `io.dotnetnativeinterop.lab.BenchmarkChart`.
 */

/** Vertical-bar histogram of latency bins (midpoint ms on X, sample count on Y). */
@Composable
internal fun HistogramChart(
    bins: List<LatencyBin>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (bins.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxCount = bins.maxOf { it.count }.coerceAtLeast(1)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Instrument.textTertiary, fontSize = 10.sp)

    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val padL = 40f
        val padB = 24f
        val padT = 10f
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

        drawText(measurer, maxCount.toString(), Offset(2f, padT - 4f), labelStyle)
        drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
        drawText(measurer, "%.2f".format(bins.first().midpoint), Offset(padL, padT + h + 4f), labelStyle)
        drawText(measurer, "%.2f".format(bins.last().midpoint), Offset(padL + w - 40f, padT + h + 4f), labelStyle)
    }
}

/** Labelled category bar chart: one bar per (label, value) pair with the label drawn under it. */
@Composable
internal fun CategoryBarChart(
    points: List<Pair<String, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val maxY = points.maxOf { it.second }.coerceAtLeast(1e-9)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Instrument.textTertiary, fontSize = 10.sp)

    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
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
            drawRect(color, topLeft = Offset(x, padT + h - barH), size = Size(barW, barH))
            drawText(measurer, label, Offset(padL + i * slot + 2f, padT + h + 4f), labelStyle)
        }

        drawText(measurer, "%.1f".format(maxY), Offset(2f, padT - 4f), labelStyle)
        drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
    }
}
