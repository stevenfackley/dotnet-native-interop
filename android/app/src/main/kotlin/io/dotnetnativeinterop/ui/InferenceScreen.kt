package io.dotnetnativeinterop.ui

import androidx.compose.foundation.background
import io.dotnetnativeinterop.transport.Token
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.TransportPicker
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Spacing

@Composable
public fun InferenceScreen(
    viewModel: InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            // Title
            Text(
                text = "DotnetNativeInterop — Interop POC",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Engine status chip
            EngineStatusBadge(ready = state.engineReady)

            // Transport selector
            TransportPicker(
                selected = state.selectedTransport,
                onSelect = viewModel::onTransportSelected,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            // Prompt input
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChanged,
                label = { Text("Prompt") },
                placeholder = { Text("Enter your prompt here…") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                enabled = !state.isRunning,
            )

            // Run / Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Button(
                    onClick = viewModel::startInference,
                    enabled = !state.isRunning && state.engineReady && state.prompt.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Run")
                }
                Button(
                    onClick = viewModel::stopInference,
                    enabled = state.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop")
                }
            }

            // Error banner
            state.error?.let { error ->
                ErrorBanner(message = error)
            }

            // Token stream output
            TokenStreamView(
                tokens = state.tokens,
                isRunning = state.isRunning,
            )

            // Metrics row (required by contract)
            if (state.metrics.transportId.isNotEmpty()) {
                MetricsRow(metrics = state.metrics)
            }

            // Pattern info panel (required by contract)
            state.patterns?.let { patternsJson ->
                val pattern = remember(state.selectedTransport, patternsJson) {
                    patternsJson.patterns.find { it.id == state.selectedTransport.name.lowercase() }
                }
                pattern?.let { PatternInfoPanel(pattern = it) }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Engine status
// ---------------------------------------------------------------------------

@Composable
private fun EngineStatusBadge(ready: Boolean) {
    val (label, color) = if (ready) {
        "Engine ready" to MaterialTheme.colorScheme.primary
    } else {
        "Engine initializing…" to MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!ready) {
            CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ---------------------------------------------------------------------------
// Token stream view
// ---------------------------------------------------------------------------

@Composable
private fun TokenStreamView(tokens: List<Token>, isRunning: Boolean) {
    val text = remember(tokens) {
        buildAnnotatedString {
            for (token in tokens) {
                if (!token.isFinal) append(token.text)
            }
        }
    }

    InstrumentCard(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            if (text.isEmpty() && !isRunning) {
                Text(
                    "Token stream will appear here…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Instrument.textTertiary,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Instrument.textPrimary,
                )
            }
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp)
                        .align(Alignment.BottomEnd),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Metrics row (required by INTEROP_CONTRACT.md)
// ---------------------------------------------------------------------------

@Composable
private fun MetricsRow(metrics: RunMetrics) {
    InstrumentCard(modifier = Modifier.fillMaxWidth()) {
        PanelHeader("Run Metrics — ${metrics.transportId}")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricCell(label = "TTFT", value = "${metrics.timeToFirstTokenMs} ms")
            MetricCell(label = "tok/s", value = "%.1f".format(metrics.tokensPerSec))
            MetricCell(label = "Total", value = "${metrics.totalTimeMs} ms")
            MetricCell(label = "Tokens", value = "${metrics.tokenCount}")
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
             fontFamily = FontFamily.Monospace, color = Instrument.textPrimary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Instrument.textTertiary)
    }
}

// ---------------------------------------------------------------------------
// Pattern info panel (required by INTEROP_CONTRACT.md)
// ---------------------------------------------------------------------------

@Composable
public fun PatternInfoPanel(pattern: PatternInfo) {
    InstrumentCard(modifier = Modifier.fillMaxWidth()) {
        PanelHeader(pattern.name)

        // Header
        Text(
            text = pattern.transport,
            style = MaterialTheme.typography.labelSmall,
            color = Instrument.accent,
        )

        // Summary
        Text(
            text = pattern.summary,
            style = MaterialTheme.typography.bodySmall,
            color = Instrument.textPrimary,
        )

        Text(
            text = "Best for: ${pattern.bestFor}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Instrument.textPrimary,
        )

        HorizontalDivider(color = Instrument.hairline)

        // Features (checkmark prefix — contract requires ✓)
        if (pattern.features.isNotEmpty()) {
            Text("Features", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                 color = Instrument.textSecondary)
            pattern.features.forEach { feature ->
                BulletLine(prefix = "✓", text = feature, prefixColor = Instrument.ok)
            }
        }

        // Limitations (warning prefix — contract requires ⚠)
        if (pattern.limitations.isNotEmpty()) {
            Text("Limitations", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                 color = Instrument.textSecondary)
            pattern.limitations.forEach { limitation ->
                BulletLine(prefix = "⚠", text = limitation, prefixColor = Instrument.warn)
            }
        }
    }
}

@Composable
private fun BulletLine(prefix: String, text: String, prefixColor: Color) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "$prefix ",
            style = MaterialTheme.typography.bodySmall,
            color = prefixColor,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------
// Error banner
// ---------------------------------------------------------------------------

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small)
            .padding(10.dp),
    ) {
        Text(
            text = "Error: $message",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
