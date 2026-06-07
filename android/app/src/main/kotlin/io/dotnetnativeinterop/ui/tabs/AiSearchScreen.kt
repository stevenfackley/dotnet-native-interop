package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ai.AiSearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSearchScreen(vm: AiSearchViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            vm.corpora.forEachIndexed { i, c ->
                SegmentedButton(
                    selected = s.corpus == c,
                    onClick = { vm.selectCorpus(c) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = vm.corpora.size),
                ) { Text(c) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            label = { Text("Query") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { vm.search() }) { Text("Search") }

        Spacer(modifier = Modifier.height(8.dp))

        if (s.loading) CircularProgressIndicator()

        if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            items(s.results) { result ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(result.text)
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { result.score.coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "similarity ${"%.3f".format(result.score)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
