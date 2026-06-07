package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.FeaturesViewModel

@Composable
internal fun FeatureDetailScreen(
    vm: FeaturesViewModel,
    id: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d = s.descriptors.firstOrNull { it.id == id }

    if (d == null) {
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Feature not found", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(d.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text(d.version) })
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Code", style = MaterialTheme.typography.titleSmall)
                Text(d.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Expected", style = MaterialTheme.typography.titleSmall)
                Text(d.expected, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(onClick = { vm.run(id) }, modifier = Modifier.fillMaxWidth()) {
            Text("Run")
        }

        val result = s.results[id]
        if (result != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Result", style = MaterialTheme.typography.titleSmall)
                    Text(result.result, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (result.ok) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "OK",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text("OK", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Failed",
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text("Failed", color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "%.3f ms".format(result.elapsedMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
