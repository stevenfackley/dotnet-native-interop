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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell

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
            modifier = modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text("Feature not found", color = Instrument.fail)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        TextButton(onClick = onBack) { Text("Back") }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(d.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text(d.version) })
        }

        InstrumentCard {
            PanelHeader("Code")
            Text(d.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = Instrument.textPrimary)
        }

        InstrumentCard {
            PanelHeader("Expected")
            Text(d.expected, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = Instrument.textPrimary)
        }

        Button(onClick = { vm.run(id) }, modifier = Modifier.fillMaxWidth()) {
            Text("Run")
        }

        val result = s.results[id]
        if (result != null) {
            InstrumentCard {
                PanelHeader("Result")
                Text(result.result, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textPrimary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    val verdictColor = if (result.ok) Instrument.ok else Instrument.fail
                    val verdictIcon = if (result.ok) Icons.Filled.Check else Icons.Filled.Close
                    val verdictDesc = if (result.ok) "OK" else "Failed"
                    Icon(imageVector = verdictIcon, contentDescription = verdictDesc, tint = verdictColor)
                    Text(verdictDesc, color = verdictColor)
                    StatCell(
                        label = "elapsed",
                        value = "%.3f ms".format(result.elapsedMs),
                    )
                }
            }
        }
    }
}
