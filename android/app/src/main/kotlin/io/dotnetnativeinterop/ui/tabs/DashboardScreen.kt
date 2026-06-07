package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.ui.components.TransportPicker

@Composable
internal fun DashboardScreen(
    vm: FeaturesViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TransportPicker(
            selected = s.transport,
            onSelect = vm::selectTransport,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { vm.runAll() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run all")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stats", style = MaterialTheme.typography.titleMedium)
                Text("Total: ${s.descriptors.size}")
                Text("Ran: ${s.results.size}")
                Text("Passing: ${s.results.values.count { it.ok }}")
                Text("Elapsed: ${"%.1f ms".format(s.results.values.sumOf { it.elapsedMs })}")
            }
        }

        if (s.loading) {
            CircularProgressIndicator()
        }

        if (s.error != null) {
            Text(s.error!!, color = MaterialTheme.colorScheme.error)
        }
    }
}
