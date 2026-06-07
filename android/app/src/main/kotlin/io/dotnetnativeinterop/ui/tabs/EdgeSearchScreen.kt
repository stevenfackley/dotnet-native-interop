package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.evs.EvsViewModel

@Composable
internal fun EdgeSearchScreen(vm: EvsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            label = { Text("Search manuals…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Button(onClick = { vm.search() }, modifier = Modifier.fillMaxWidth()) {
            Text("Search")
        }

        if (s.availableErrorCodes.isNotEmpty()) {
            Text("Error codes", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.availableErrorCodes.forEach { code ->
                    FilterChip(
                        selected = code in s.activeErrorCodes,
                        onClick = { vm.toggleErrorCode(code) },
                        label = { Text(code) },
                    )
                }
            }
        }

        if (s.availableTools.isNotEmpty()) {
            Text("Tools", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.availableTools.forEach { tool ->
                    FilterChip(
                        selected = tool in s.activeTools,
                        onClick = { vm.toggleTool(tool) },
                        label = { Text(tool) },
                    )
                }
            }
        }

        if (s.loading) CircularProgressIndicator()

        if (s.error != null) Text(s.error!!, color = MaterialTheme.colorScheme.error)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = true,
        ) {
            items(s.results) { hit ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(hit.chunk.sectionTitle, style = MaterialTheme.typography.titleSmall)
                        Text(
                            hit.chunk.contentText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(
                            progress = { hit.score.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "%.0f%%".format(hit.score * 100),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (hit.chunk.errorCodes.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                hit.chunk.errorCodes.forEach { code ->
                                    AssistChip(onClick = {}, label = { Text(code) })
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            "On-device ONNX over a .NET-published index — no network, no engine call.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
