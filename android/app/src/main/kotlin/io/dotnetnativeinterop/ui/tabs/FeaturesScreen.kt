package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.RunStatus
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.TransportPicker

/** C# version buckets for the Catalog filter chips (spec 2's "one net-new behavior"). */
private enum class VersionBucket(val label: String, val range: IntRange) {
    Early("C# 1–6", 1..6),
    Mid("C# 7–10", 7..10),
    Modern("C# 11–14", 11..14),
}

private enum class CatalogSort(val label: String) {
    Version("Version"), Name("Name"), Elapsed("Elapsed"),
}

private fun FeatureDescriptor.versionNumber(): Int? =
    Regex("""\d+""").find(version)?.value?.toIntOrNull()

/**
 * The Catalog tab (IA collapse spec 2 of 3, was "Features"): the 57-demo catalog, now searchable +
 * filterable (by C# version bucket, pass/fail) + sortable (name / version / elapsed) — the one
 * net-new behavior called for by the design doc. Default sort (Version) with no filters reproduces
 * the prior grouped-by-version layout unchanged; other sorts show a flat list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeaturesScreen(
    vm: FeaturesViewModel,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var activeBuckets by remember { mutableStateOf(setOf<VersionBucket>()) }
    var passFilter by remember { mutableStateOf(false) }
    var failFilter by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(CatalogSort.Version) }

    val filtered = s.descriptors
        .filter { d -> query.isBlank() || d.title.contains(query, ignoreCase = true) || d.id.contains(query, ignoreCase = true) }
        .filter { d ->
            activeBuckets.isEmpty() ||
                d.versionNumber()?.let { n -> activeBuckets.any { n in it.range } } == true
        }
        .filter { d ->
            if (!passFilter && !failFilter) true
            else {
                val status = s.status[d.id]
                (passFilter && status == RunStatus.Ok) || (failFilter && status == RunStatus.Failed)
            }
        }

    val sorted = when (sort) {
        CatalogSort.Name -> filtered.sortedBy { it.title }
        CatalogSort.Version -> filtered.sortedBy { it.versionNumber() ?: Int.MAX_VALUE }
        CatalogSort.Elapsed -> filtered.sortedByDescending { s.results[it.id]?.elapsedMs ?: -1.0 }
    }

    LazyColumn(modifier = modifier) {
        stickyHeader(key = "__controls__") {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    TransportPicker(
                        selected = s.transport,
                        onSelect = vm::selectTransport,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search catalog…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        VersionBucket.entries.forEach { bucket ->
                            FilterChip(
                                selected = bucket in activeBuckets,
                                onClick = {
                                    activeBuckets = if (bucket in activeBuckets) activeBuckets - bucket else activeBuckets + bucket
                                },
                                label = { Text(bucket.label) },
                            )
                        }
                        FilterChip(
                            selected = passFilter,
                            onClick = { passFilter = !passFilter },
                            label = { Text("✓ Pass") },
                        )
                        FilterChip(
                            selected = failFilter,
                            onClick = { failFilter = !failFilter },
                            label = { Text("✗ Fail") },
                        )
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        CatalogSort.entries.forEachIndexed { i, opt ->
                            SegmentedButton(
                                selected = sort == opt,
                                onClick = { sort = opt },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = CatalogSort.entries.size),
                            ) { Text(opt.label) }
                        }
                    }
                }
            }
        }

        if (sort == CatalogSort.Version) {
            val grouped = sorted.groupBy { it.version }
            grouped.forEach { (version, descriptors) ->
                stickyHeader(key = "header_$version") {
                    Surface(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = version,
                            style = MaterialTheme.typography.labelLarge,
                            color = Instrument.accent,
                            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs + 2.dp),
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
        } else {
            items(items = sorted, key = { it.id }) { d ->
                val status = s.status[d.id] ?: RunStatus.Idle
                val elapsed = s.results[d.id]?.elapsedMs
                ListItem(
                    headlineContent = { Text(d.title) },
                    supportingContent = {
                        Text(if (elapsed != null) "${d.version} · %.1f ms".format(elapsed) else d.version)
                    },
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
            color = Instrument.accent,
        )
        RunStatus.Ok -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "OK",
            tint = Instrument.ok,
            modifier = modifier,
        )
        RunStatus.Failed -> Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Failed",
            tint = Instrument.fail,
            modifier = modifier,
        )
    }
}
