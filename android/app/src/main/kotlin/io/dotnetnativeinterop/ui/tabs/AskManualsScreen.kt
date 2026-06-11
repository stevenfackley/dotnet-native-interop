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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ai.RagViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader

@Composable
internal fun AskManualsScreen(vm: RagViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(Spacing.l)) {
        OutlinedTextField(
            value = s.query,
            onValueChange = vm::setQuery,
            label = { Text("Ask the manuals…") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Spacing.s))

        Button(onClick = { vm.ask() }) { Text("Ask") }

        Spacer(modifier = Modifier.height(Spacing.m))

        if (s.sources.isNotEmpty()) {
            InstrumentCard(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                PanelHeader("Sources")
                s.sources.forEach { src ->
                    Text(
                        "${"%.3f".format(src.score)}  ${src.text}",
                        fontFamily = FontFamily.Monospace,
                        color = Instrument.textSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.s))
        }

        InstrumentCard(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
            PanelHeader("Answer")
            Text(
                "grounded extraction (on-device generation is a future option)",
                color = Instrument.textTertiary,
            )
            Text(s.answer, color = Instrument.textPrimary)
            if (s.streaming) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                CircularProgressIndicator()
            }
        }

        Spacer(modifier = Modifier.height(Spacing.s))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            s.firstTokenMs?.let {
                Text("first token $it ms", fontFamily = FontFamily.Monospace, color = Instrument.textSecondary)
            }
            s.totalMs?.let {
                Text("total $it ms", fontFamily = FontFamily.Monospace, color = Instrument.textSecondary)
            }
        }

        s.error?.let {
            Spacer(modifier = Modifier.height(Spacing.xs))
            ErrorBanner(it)
        }
    }
}
