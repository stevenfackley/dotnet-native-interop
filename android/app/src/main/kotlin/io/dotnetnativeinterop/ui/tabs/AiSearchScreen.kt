package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ai.AiSearchViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSearchScreen(vm: AiSearchViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            vm.corpora.forEachIndexed { i, c ->
                SegmentedButton(
                    selected = s.corpus == c,
                    onClick = { vm.selectCorpus(c) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = vm.corpora.size),
                ) { Text(c) }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.m))

        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            label = { Text("Query") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Spacing.s))

        Button(onClick = { vm.search() }) { Text("Search") }

        Spacer(modifier = Modifier.height(Spacing.s))

        if (s.loading) CircularProgressIndicator()

        s.error?.let { ErrorBanner(it) }

        Spacer(modifier = Modifier.height(Spacing.s))

        LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            items(s.results) { result ->
                InstrumentCard(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    PanelHeader("Result")
                    Text(result.text, color = Instrument.textPrimary)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Instrument.bg2, RoundedCornerShape(Radii.chip)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(result.score.coerceIn(0.0, 1.0).toFloat())
                                .height(4.dp)
                                .background(Instrument.accent, RoundedCornerShape(Radii.chip)),
                        )
                    }
                    Text(
                        "similarity ${"%.3f".format(result.score)}",
                        fontFamily = FontFamily.Monospace,
                        color = Instrument.textSecondary,
                    )
                }
            }
        }
    }
}
