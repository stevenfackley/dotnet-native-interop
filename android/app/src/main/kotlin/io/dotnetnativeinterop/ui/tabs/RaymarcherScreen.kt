package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.lab.LabCommands
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.TransportPicker

@Composable
internal fun RaymarcherScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    val lastError by lab.lastError.collectAsStateWithLifecycle()
    var angle by remember { mutableDoubleStateOf(0.0) }
    var spinning by remember { mutableStateOf(true) }
    val size = 220

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RasterDemoHost(
            transportName = transport.displayName,
            animating = { spinning },
            currentCommand = { LabCommands.raymarch(angle, size) },
            advance = { angle += 0.03 },
            render = { lab.render(it) },
            lastError = lastError,
            gestureModifier = Modifier.pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    spinning = false
                    angle += drag.x / 120.0
                }
            },
        )
        InstrumentCard {
            PanelHeader("Controls")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Switch(checked = spinning, onCheckedChange = { spinning = it })
                Text("Auto-rotate")
            }
        }
        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "A signed-distance-field raymarcher — sphere, ground plane, soft shadow — with every ray "
                + "traced on the CPU in C#. No GPU, no shaders.",
            style = MaterialTheme.typography.bodySmall,
            color = Instrument.textSecondary,
        )
    }
}
