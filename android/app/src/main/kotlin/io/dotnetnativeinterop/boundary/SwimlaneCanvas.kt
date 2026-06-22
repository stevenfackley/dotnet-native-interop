package io.dotnetnativeinterop.boundary

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import io.dotnetnativeinterop.model.BoundaryPhase
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing

/**
 * Lifecycle swimlane: one lane per layer; the active phase highlights its lane(s). The callback phase
 * lights worker -> JNI -> UI (the off-thread hop). Android shows the JNI shim lane that iOS lacks.
 */
private enum class Lane(val display: String) {
    Ui("UI thread"), Binding("binding"), Jni("JNI shim"), CAbi("C ABI"), Net(".NET AOT"), Worker("worker")
}

private fun hotLanes(phase: BoundaryPhase?): Set<Lane> = when (phase) {
    BoundaryPhase.Marshal -> setOf(Lane.Binding, Lane.Jni)
    BoundaryPhase.Cross -> setOf(Lane.CAbi)
    BoundaryPhase.Execute -> setOf(Lane.Net)
    BoundaryPhase.Callback -> setOf(Lane.Worker, Lane.Jni, Lane.Ui)
    BoundaryPhase.Free -> setOf(Lane.Ui)
    null -> emptySet()
}

@Composable
internal fun SwimlaneCanvas(activePhase: BoundaryPhase?, modifier: Modifier = Modifier) {
    val hot = hotLanes(activePhase)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Lane.entries.forEach { lane ->
            val isHot = lane in hot
            val border by animateColorAsState(
                if (isHot) Instrument.accent else Instrument.hairline, label = "lane",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    lane.display,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (isHot) Instrument.accent else Instrument.textTertiary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(76.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .background(Instrument.bg2, RoundedCornerShape(3.dp))
                        .border(if (isHot) 1.5.dp else 1.dp, border, RoundedCornerShape(3.dp))
                        .padding(horizontal = Spacing.xs),
                )
            }
        }
    }
}
