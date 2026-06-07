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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.ai.AiSearchViewModel
import io.dotnetnativeinterop.ai.RagViewModel

@Composable
internal fun AiScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("Semantic Search", "Ask the Manuals")
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label) }
            }
        }
        if (tab == 0) {
            val vm: AiSearchViewModel = viewModel()
            AiSearchScreen(vm, Modifier.fillMaxWidth())
        } else {
            val vm: RagViewModel = viewModel()
            AskManualsScreen(vm, Modifier.fillMaxWidth())
        }
    }
}
