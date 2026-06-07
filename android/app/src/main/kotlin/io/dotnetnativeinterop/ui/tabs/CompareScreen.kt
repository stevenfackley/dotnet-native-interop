package io.dotnetnativeinterop.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.feature.ComparisonViewModel

@Composable
internal fun CompareScreen(
    vm: ComparisonViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val maxMs = s.timings.maxOfOrNull { it.totalMs } ?: 1.0

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { vm.runAll() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Run comparison")
        }

        if (s.running) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        s.timings.forEach { t ->
            val fraction = (t.totalMs / maxMs).toFloat().coerceIn(0.02f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = t.transport.displayName,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "%.1f ms · %d/%d".format(t.totalMs, t.passed, t.featureCount),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(16.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }

        if (s.error != null) {
            Text(
                text = s.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
