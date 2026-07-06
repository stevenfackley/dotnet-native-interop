package io.dotnetnativeinterop.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing

/** Fallback palette for series with no domain meaning (e.g. Lab's scalar/SIMD, ms/speedup). Callers
 *  whose series ARE transports must pass [BenchmarkChart]'s `colors` with the canonical transport
 *  colors instead (facelift spec §2: transport colors on chart series, never an arbitrary rainbow). */
private val seriesColors = listOf(
    Instrument.accent, Instrument.warn, Instrument.fail, Instrument.ok,
)

/**
 * A notable x-position worth calling out on a [BenchmarkChart] — a GC collection event, an outlier
 * sample, etc. Drawn as a short dashed vertical tick with a small label (facelift spec §5: data-viz as
 * evidence, not ornament).
 */
internal data class ChartAnnotation(val x: Double, val label: String, val color: Color = Instrument.warn)

/**
 * Multi-series line+point chart of benchmark series, drawn on a Canvas (no chart dependency).
 * [colors], when supplied, overrides the default series palette one-for-one by index — pass the
 * canonical transport colors when the series ARE transports. [annotations] marks specific x-positions
 * (GC events, outliers) with a dashed tick + label.
 */
@Composable
internal fun BenchmarkChart(
    series: List<BenchmarkSeries>,
    modifier: Modifier = Modifier,
    colors: List<Color>? = null,
    annotations: List<ChartAnnotation> = emptyList(),
) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val palette = colors ?: seriesColors
    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val maxY = allPoints.maxOf { it.y }.coerceAtLeast(1e-9)
    val axisColor = Instrument.hairline
    val labelColor = Instrument.textTertiary
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)
    val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
            val padL = 52f
            val padB = 26f
            val padT = 18f // extra headroom for annotation labels above the plot
            val padR = 10f
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val spanX = (maxX - minX).coerceAtLeast(1e-9)
            fun sx(x: Double) = padL + ((x - minX) / spanX * w).toFloat()
            fun sy(y: Double) = padT + (h - (y / maxY * h)).toFloat()

            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + h), strokeWidth = 2f)
            drawLine(axisColor, Offset(padL, padT + h), Offset(padL + w, padT + h), strokeWidth = 2f)

            series.forEachIndexed { i, s ->
                val color = palette[i % palette.size]
                val pts = s.points.sortedBy { it.x }
                for (j in 1 until pts.size) {
                    drawLine(
                        color,
                        Offset(sx(pts[j - 1].x), sy(pts[j - 1].y)),
                        Offset(sx(pts[j].x), sy(pts[j].y)),
                        strokeWidth = 3f,
                    )
                }
                pts.forEach { drawCircle(color, 4f, Offset(sx(it.x), sy(it.y))) }
            }

            annotations.forEach { a ->
                val x = sx(a.x)
                drawLine(a.color, Offset(x, padT), Offset(x, padT + h), strokeWidth = 2f, pathEffect = dashed)
                if (a.label.isNotEmpty()) {
                    drawText(measurer, a.label, Offset((x - 8f).coerceAtLeast(padL), 2f), TextStyle(color = a.color, fontSize = 9.sp))
                }
            }

            drawText(measurer, String.format(java.util.Locale.US, "%.1f", maxY), Offset(2f, padT - 4f), labelStyle)
            drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
            drawText(measurer, minX.toInt().toString(), Offset(padL, padT + h + 4f), labelStyle)
            drawText(measurer, maxX.toInt().toString(), Offset(padL + w - 28f, padT + h + 4f), labelStyle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Box(Modifier.size(10.dp).background(palette[i % palette.size]))
                    Text(s.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
