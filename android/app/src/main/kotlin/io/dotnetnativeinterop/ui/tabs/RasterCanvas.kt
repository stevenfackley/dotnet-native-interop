package io.dotnetnativeinterop.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.dotnetnativeinterop.lab.RasterImage
import io.dotnetnativeinterop.lab.RasterPayload
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
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
    lastError: String? = null,
) {
    var image by remember { mutableStateOf<RasterImage?>(null) }
    var fps by remember { mutableStateOf(0.0) }
    var frameMs by remember { mutableStateOf(0.0) }
    var dims by remember { mutableStateOf("—") }
    var decodeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        var last = ""
        while (isActive) {
            val command = currentCommand()
            if (animating() || command != last) {
                val start = System.nanoTime()
                val result = render(command)
                if (result != null) {
                    val decoded = RasterPayload.decode(result.result)
                    if (decoded != null) {
                        image = decoded
                        dims = "${decoded.width}×${decoded.height}"
                        decodeError = null
                    } else {
                        decodeError = "Couldn't decode the frame payload (${result.result.length} chars)"
                    }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    frameMs = ms
                    fps = if (ms > 0) minOf(120.0, 1000.0 / ms) else 0.0
                    last = command
                } else {
                    // Failed render: back off instead of hammering a failing transport at full speed.
                    delay(250)
                }
                if (animating()) advance()
            } else {
                delay(50)
            }
        }
    }

    RasterCanvas(image, fps, frameMs, dims, transportName, gestureModifier,
        errorMessage = decodeError ?: lastError)
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
    errorMessage: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(Radii.canvas))
                .background(Color.Black)
                .border(1.dp, Instrument.hairline, RoundedCornerShape(Radii.canvas))
                .then(gestureModifier),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
                    bitmap = image.toImageBitmap(),
                    contentDescription = "rendered frame",
                    filterQuality = FilterQuality.None,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                errorMessage != null -> Text(
                    errorMessage,
                    color = Instrument.fail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.l),
                )
                else -> Text(
                    "Rendering…",
                    color = Instrument.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("%.1f fps".format(fps), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = Instrument.accent)
            Text("%.1f ms".format(frameMs), style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = Instrument.textSecondary)
            Text(dims, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = Instrument.textSecondary)
            Text(transport, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = Instrument.textSecondary)
        }
    }
}
