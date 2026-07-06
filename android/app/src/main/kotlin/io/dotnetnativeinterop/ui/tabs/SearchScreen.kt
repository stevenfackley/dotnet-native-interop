package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.evs.EvsViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing

/**
 * Search hub (IA collapse spec 2 of 3): merges the former AI tab (engine-backed semantic search +
 * Ask-the-Manuals RAG, both over FFI) and the former Manuals/EdgeSearch tab (on-device ONNX EVS,
 * no engine) behind one segmented control, per the design doc. Each mode carries a one-line
 * engine/source label rather than hiding the architectural difference behind identical UIs.
 * Existing view bodies (AiScreen, EdgeSearchScreen) are reused unchanged.
 */
@Composable
internal fun SearchScreen(modifier: Modifier = Modifier) {
    var engineMode by remember { mutableIntStateOf(0) }
    val labels = listOf("Engine", "On-device")

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = engineMode == i,
                    onClick = { engineMode = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label) }
            }
        }

        val inner = Modifier.fillMaxWidth().weight(1f)
        if (engineMode == 0) {
            Column(modifier = inner) {
                Text(
                    "Uses the .NET engine over FFI",
                    style = MaterialTheme.typography.labelSmall,
                    color = Instrument.textTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
                AiScreen(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Column(modifier = inner) {
                Text(
                    "Runs entirely on-device via ONNX — no engine",
                    style = MaterialTheme.typography.labelSmall,
                    color = Instrument.textTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
                val vm: EvsViewModel = viewModel()
                EdgeSearchScreen(vm, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
