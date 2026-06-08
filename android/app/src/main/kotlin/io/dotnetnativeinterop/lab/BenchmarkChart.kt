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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val seriesColors = listOf(
    Color(0xFF3B82F6), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF10B981),
)

/** Multi-series line+point chart of benchmark series, drawn on a Canvas (no chart dependency). */
@Composable
internal fun BenchmarkChart(series: List<BenchmarkSeries>, modifier: Modifier = Modifier) {
    val allPoints = series.flatMap { it.points }
    if (allPoints.isEmpty()) {
        Text("No data", style = MaterialTheme.typography.bodySmall)
        return
    }
    val minX = allPoints.minOf { it.x }
    val maxX = allPoints.maxOf { it.x }
    val maxY = allPoints.maxOf { it.y }.coerceAtLeast(1e-9)
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer: TextMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = modifier.fillMaxWidth().height(240.dp)) {
            val padL = 52f
            val padB = 26f
            val padT = 10f
            val padR = 10f
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            val spanX = (maxX - minX).coerceAtLeast(1e-9)
            fun sx(x: Double) = padL + ((x - minX) / spanX * w).toFloat()
            fun sy(y: Double) = padT + (h - (y / maxY * h)).toFloat()

            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + h), strokeWidth = 2f)
            drawLine(axisColor, Offset(padL, padT + h), Offset(padL + w, padT + h), strokeWidth = 2f)

            series.forEachIndexed { i, s ->
                val color = seriesColors[i % seriesColors.size]
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

            drawText(measurer, "%.1f".format(maxY), Offset(2f, padT - 4f), labelStyle)
            drawText(measurer, "0", Offset(2f, padT + h - 6f), labelStyle)
            drawText(measurer, minX.toInt().toString(), Offset(padL, padT + h + 4f), labelStyle)
            drawText(measurer, maxX.toInt().toString(), Offset(padL + w - 28f, padT + h + 4f), labelStyle)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).background(seriesColors[i % seriesColors.size]))
                    Text(s.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
