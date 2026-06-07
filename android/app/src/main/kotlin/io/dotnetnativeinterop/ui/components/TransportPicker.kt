package io.dotnetnativeinterop.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.dotnetnativeinterop.model.TransportKind

@Composable
internal fun TransportPicker(
    selected: TransportKind,
    onSelect: (TransportKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        val all = TransportKind.entries
        all.forEachIndexed { i, t ->
            SegmentedButton(
                selected = t == selected,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = all.size),
            ) { Text(t.displayName) }
        }
    }
}
