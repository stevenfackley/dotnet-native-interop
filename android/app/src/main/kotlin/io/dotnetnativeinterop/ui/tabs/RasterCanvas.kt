package io.dotnetnativeinterop.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.lab.RasterImage
import io.dotnetnativeinterop.lab.RasterPayload
import io.dotnetnativeinterop.model.FeatureResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun RasterImage.toImageBitmap() =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

/**
 * Drives a visual demo: renders currentCommand() as fast as the transport allows, re-rendering
 * immediately while animating() and otherwise only when the command changes. Bitmap + FPS state are
 * Compose state on the main thread; render() is suspend and hops to IO inside the service.
 */
@Composable
internal fun RasterDemoHost(
    transportName: String,
    animating: () -> Boolean,
    currentCommand: () -> String,
    advance: () -> Unit,
    render: suspend (String) -> FeatureResult?,
    gestureModifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<RasterImage?>(null) }
    var fps by remember { mutableStateOf(0.0) }
    var frameMs by remember { mutableStateOf(0.0) }
    var dims by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) {
        var last = ""
        while (isActive) {
            val command = currentCommand()
            if (animating() || command != last) {
                val start = System.nanoTime()
                val result = render(command)
                if (result != null) {
                    RasterPayload.decode(result.result)?.let {
                        image = it
                        dims = "${it.width}×${it.height}"
                    }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    frameMs = ms
                    fps = if (ms > 0) minOf(120.0, 1000.0 / ms) else 0.0
                    last = command
                }
                if (animating()) advance()
            } else {
                delay(50)
            }
        }
    }

    RasterCanvas(image, fps, frameMs, dims, transportName, gestureModifier)
}

/** Presentational frame + live readout (fps · ms/frame · dims · transport). No logic. */
@Composable
internal fun RasterCanvas(
    image: RasterImage?,
    fps: Double,
    frameMs: Double,
    dims: String,
    transport: String,
    gestureModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .then(gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image.toImageBitmap(),
                    contentDescription = "rendered frame",
                    filterQuality = FilterQuality.None,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Rendering…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("%.1f fps".format(fps), style = MaterialTheme.typography.bodySmall)
            Text("%.1f ms".format(frameMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dims, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(transport, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
