package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ai.RagViewModel

@Composable
internal fun AskManualsScreen(vm: RagViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            label = { Text("Ask the manuals…") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { vm.ask() }) { Text("Ask") }

        Spacer(modifier = Modifier.height(12.dp))

        if (s.sources.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Sources", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    s.sources.forEach { src ->
                        Text(
                            "${"%.3f".format(src.score)}  ${src.text}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Answer", style = MaterialTheme.typography.titleSmall)
                Text(
                    "grounded extraction (on-device generation is a future option)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(s.answer)
                if (s.streaming) {
                    Spacer(modifier = Modifier.height(4.dp))
                    CircularProgressIndicator()
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            s.firstTokenMs?.let { Text("first token $it ms", style = MaterialTheme.typography.labelSmall) }
            s.totalMs?.let { Text("total $it ms", style = MaterialTheme.typography.labelSmall) }
        }

        if (s.error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(s.error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
