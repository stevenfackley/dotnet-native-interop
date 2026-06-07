package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.model.RunStatus
import io.dotnetnativeinterop.ui.components.TransportPicker

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeaturesScreen(
    vm: FeaturesViewModel,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var failedOnly by remember { mutableStateOf(false) }

    val grouped = s.descriptors
        .let { list ->
            if (failedOnly) list.filter { s.status[it.id] == RunStatus.Failed }
            else list
        }
        .groupBy { it.version }

    LazyColumn(modifier = modifier) {
        stickyHeader(key = "__controls__") {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TransportPicker(
                        selected = s.transport,
                        onSelect = vm::selectTransport,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = failedOnly,
                        onClick = { failedOnly = !failedOnly },
                        label = { Text("Failed only") },
                    )
                }
            }
        }

        grouped.forEach { (version, descriptors) ->
            stickyHeader(key = "header_$version") {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            items(items = descriptors, key = { it.id }) { d ->
                val status = s.status[d.id] ?: RunStatus.Idle
                ListItem(
                    headlineContent = { Text(d.title) },
                    trailingContent = { StatusIndicator(status = status) },
                    modifier = Modifier.clickable { onOpenDetail(d.id) },
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: RunStatus, modifier: Modifier = Modifier) {
    when (status) {
        RunStatus.Idle -> Box(modifier = modifier.size(24.dp))
        RunStatus.Running -> CircularProgressIndicator(
            modifier = modifier.size(24.dp),
            strokeWidth = 2.dp,
        )
        RunStatus.Ok -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "OK",
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        RunStatus.Failed -> Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    }
}
