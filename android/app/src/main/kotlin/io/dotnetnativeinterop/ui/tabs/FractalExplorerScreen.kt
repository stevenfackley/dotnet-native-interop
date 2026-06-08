package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import io.dotnetnativeinterop.ui.components.TransportPicker

@Composable
internal fun FractalExplorerScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    val transport by lab.transport.collectAsStateWithLifecycle()
    var centerX by remember { mutableDoubleStateOf(-0.5) }
    var centerY by remember { mutableDoubleStateOf(0.0) }
    var zoom by remember { mutableDoubleStateOf(1.0) }
    var iterations by remember { mutableFloatStateOf(220f) }
    var diving by remember { mutableStateOf(false) }
    val size = 256

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RasterDemoHost(
            transportName = transport.displayName,
            animating = { diving },
            currentCommand = { LabCommands.mandelbrot(centerX, centerY, zoom, iterations.toInt(), size) },
            advance = { zoom *= 1.03 },
            render = { lab.render(it) },
            gestureModifier = Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = maxOf(0.2, zoom * gestureZoom)
                    val span = 3.0 / zoom
                    centerX -= pan.x / 340.0 * span
                    centerY -= pan.y / 340.0 * span
                }
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = diving, onCheckedChange = { diving = it })
            Text("Dive (auto-zoom)")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Iterations")
            Slider(
                value = iterations,
                onValueChange = { iterations = it },
                valueRange = 32f..1000f,
                modifier = Modifier.weight(1f),
            )
            Text("${iterations.toInt()}", modifier = Modifier.width(44.dp))
        }
        Button(onClick = { centerX = -0.5; centerY = 0.0; zoom = 1.0; iterations = 220f }) {
            Text("Reset view")
        }
        TransportPicker(selected = transport, onSelect = lab::setTransport, modifier = Modifier.fillMaxWidth())
        Text(
            "Every pixel of this Mandelbrot is computed in C# inside the NativeAOT library and sent as raw "
                + "bytes — no GPU, no shader, no cloud. Switch transport to watch the frame rate change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
