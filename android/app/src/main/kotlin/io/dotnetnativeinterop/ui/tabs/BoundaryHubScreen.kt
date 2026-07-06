package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.dotnetnativeinterop.boundary.BoundaryScreen
import io.dotnetnativeinterop.boundary.BoundaryViewModel
import io.dotnetnativeinterop.ui.InferenceScreen
import io.dotnetnativeinterop.ui.InferenceViewModel
import io.dotnetnativeinterop.ui.Spacing

/**
 * Boundary hub (IA collapse spec 2 of 3): the hero/default landing tab. "Trace" is the FFI
 * phase-trace demo (unchanged). "Transports" is the Android-only legacy Stream tab (multi-transport
 * streaming picker + patterns) folded in here per the design doc, rather than removed —
 * additive-expansion rule. Kills the iOS<->Android Stream parity gap: neither shell has a
 * top-level Stream destination anymore.
 */
@Composable
internal fun BoundaryHubScreen(
    inference: InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableIntStateOf(0) }
    val labels = listOf("Trace", "Transports")

    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = section == i,
                    onClick = { section = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label) }
            }
        }

        val inner = Modifier.fillMaxWidth().weight(1f)
        if (section == 0) {
            val vm: BoundaryViewModel = viewModel()
            BoundaryScreen(vm, inner)
        } else {
            InferenceScreen(viewModel = inference, modifier = inner)
        }
    }
}
