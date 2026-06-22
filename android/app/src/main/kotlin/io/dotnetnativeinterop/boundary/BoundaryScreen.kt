package io.dotnetnativeinterop.boundary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.dotnetnativeinterop.model.BoundaryInspector
import io.dotnetnativeinterop.model.BoundaryPreset
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader

/** Approach A · A1: swimlane hero + segmented inspector that auto-follows the phase in Auto-step. */
@Composable
internal fun BoundaryScreen(vm: BoundaryViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .background(Instrument.bg0)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        PanelHeader("Boundary · trace one FFI call")

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            BoundaryPreset.entries.forEach { p ->
                FilterChip(selected = state.preset == p, onClick = { vm.selectPreset(p) }, label = { Text(p.title) })
            }
        }

        InstrumentCard {
            PanelHeader("lifecycle — swimlane")
            Spacer(Modifier.height(Spacing.s))
            SwimlaneCanvas(state.activePhase)
            Spacer(Modifier.height(Spacing.s))
            Text("⚠ callback fires off the UI thread → AttachCurrentThread → main", color = Instrument.warn, fontSize = 11.sp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Button(onClick = { vm.run() }, enabled = !state.running, modifier = Modifier.weight(1f)) {
                Text(if (state.preset == BoundaryPreset.Stream) "Run stream" else "Run")
            }
            OutlinedButton(onClick = { vm.autoStep() }, modifier = Modifier.weight(1f)) { Text("Auto-step") }
            if (state.preset == BoundaryPreset.Stream && state.running) {
                OutlinedButton(onClick = { vm.stop() }) { Text("Stop") }
            }
        }

        state.error?.let { ErrorBanner(message = it, onRetry = { vm.run() }) }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            BoundaryInspector.entries.forEach { seg ->
                FilterChip(selected = state.inspector == seg, onClick = { vm.selectInspector(seg) }, label = { Text(seg.label) })
            }
        }
        BoundaryInspectorPanel(state)
    }
}
