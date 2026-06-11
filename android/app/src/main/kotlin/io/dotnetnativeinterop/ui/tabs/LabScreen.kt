package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.dotnetnativeinterop.lab.LabCommands
import io.dotnetnativeinterop.lab.LabViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing

internal sealed interface LabRoute {
    data object List : LabRoute
    data object Fractal : LabRoute
    data object Raymarch : LabRoute
    data class Bench(val title: String, val command: String) : LabRoute
}

@Composable
internal fun LabScreen(lab: LabViewModel, modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf<LabRoute>(LabRoute.List) }

    when (val r = route) {
        LabRoute.List -> LabList(onOpen = { route = it }, modifier = modifier)
        LabRoute.Fractal -> LabDetail("Fractal Explorer", { route = LabRoute.List }, modifier) {
            FractalExplorerScreen(lab)
        }
        LabRoute.Raymarch -> LabDetail("Raymarched 3D", { route = LabRoute.List }, modifier) {
            RaymarcherScreen(lab)
        }
        is LabRoute.Bench -> LabDetail(r.title, { route = LabRoute.List }, modifier) {
            BenchmarkScreen(lab, r.command)
        }
    }
}

@Composable
private fun LabList(onOpen: (LabRoute) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        item {
            Text(
                "Visual — every pixel computed in C#",
                style = MaterialTheme.typography.labelLarge,
                color = Instrument.accent,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Fractal Explorer") },
                supportingContent = { Text("Interactive colorized Mandelbrot") },
                modifier = Modifier.clickable { onOpen(LabRoute.Fractal) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Raymarched 3D") },
                supportingContent = { Text("CPU signed-distance-field scene") },
                modifier = Modifier.clickable { onOpen(LabRoute.Raymarch) },
            )
        }
        item {
            Text(
                "Benchmarks — NativeAOT throughput",
                style = MaterialTheme.typography.labelLarge,
                color = Instrument.accent,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("SIMD Matrix Multiply") },
                supportingContent = { Text("Scalar vs SIMD GFLOPS") },
                modifier = Modifier.clickable { onOpen(LabRoute.Bench("SIMD Matmul", LabCommands.matmul())) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Parallel Scaling") },
                supportingContent = { Text("Single-thread vs Parallel.For") },
                modifier = Modifier.clickable { onOpen(LabRoute.Bench("Parallel Scaling", LabCommands.parallel())) },
            )
        }
    }
}

@Composable
private fun LabDetail(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            leadingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        content()
    }
}
