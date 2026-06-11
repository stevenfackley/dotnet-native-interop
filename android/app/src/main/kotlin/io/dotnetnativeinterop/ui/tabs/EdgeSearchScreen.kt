package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.evs.EvsViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader

@Composable
internal fun EdgeSearchScreen(vm: EvsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
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
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
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
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
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

        s.error?.let { ErrorBanner(it) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            userScrollEnabled = true,
        ) {
            items(s.results) { hit ->
                InstrumentCard(modifier = Modifier.fillMaxWidth()) {
                    PanelHeader(hit.chunk.sectionTitle)
                    Text(
                        hit.chunk.contentText,
                        color = Instrument.textSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Instrument.bg2, RoundedCornerShape(Radii.chip)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(hit.score.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(Instrument.accent, RoundedCornerShape(Radii.chip)),
                        )
                    }
                    Text(
                        "%.0f%%".format(hit.score * 100),
                        fontFamily = FontFamily.Monospace,
                        color = Instrument.textSecondary,
                    )
                    if (hit.chunk.errorCodes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            hit.chunk.errorCodes.forEach { code ->
                                AssistChip(onClick = {}, label = { Text(code) })
                            }
                        }
                    }
                }
            }
        }

        Text(
            "On-device ONNX over a .NET-published index — no network, no engine call.",
            color = Instrument.textTertiary,
        )
    }
}
