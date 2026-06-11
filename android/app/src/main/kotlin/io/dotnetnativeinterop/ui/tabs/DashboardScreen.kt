package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.FeaturesViewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell
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
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
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

        InstrumentCard {
            PanelHeader("Stats")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                StatCell(
                    label = "Total",
                    value = "${s.descriptors.size}",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Ran",
                    value = "${s.results.size}",
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = "Passing",
                    value = "${s.results.values.count { it.ok }}",
                    modifier = Modifier.weight(1f),
                    tint = Instrument.ok,
                )
                StatCell(
                    label = "Elapsed",
                    value = "%.1f ms".format(s.results.values.sumOf { it.elapsedMs }),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (s.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        s.error?.let { ErrorBanner(it) }
    }
}
