package io.ondevicellm.ui

import androidx.compose.foundation.background
import io.ondevicellm.transport.Token
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title
            Text(
                text = "OnDeviceLLM — Interop POC",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Engine status chip
            EngineStatusBadge(ready = state.engineReady)

            // Transport selector
            TransportSelector(
                selected = state.selectedTransport,
                onSelect = viewModel::onTransportSelected,
                enabled = !state.isRunning,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    patternsJson.patterns.find { it.id == state.selectedTransport.id }
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
// Transport selector
// ---------------------------------------------------------------------------

@Composable
private fun TransportSelector(
    selected: TransportId,
    onSelect: (TransportId) -> Unit,
    enabled: Boolean,
) {
    Column {
        Text("Transport", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransportId.entries.forEach { transport ->
                FilterChip(
                    selected = selected == transport,
                    onClick = { if (enabled) onSelect(transport) },
                    label = { Text(transport.label, fontSize = 13.sp) },
                    enabled = enabled,
                )
            }
        }
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(12.dp),
        ) {
            if (text.isEmpty() && !isRunning) {
                Text(
                    "Token stream will appear here…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Run Metrics — ${metrics.transportId}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
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
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
    }
}

// ---------------------------------------------------------------------------
// Pattern info panel (required by INTEROP_CONTRACT.md)
// ---------------------------------------------------------------------------

@Composable
public fun PatternInfoPanel(pattern: PatternInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // Header
            Text(
                text = pattern.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pattern.transport,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Summary
            Text(
                text = pattern.summary,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Best for: ${pattern.bestFor}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Features (checkmark prefix — contract requires ✓)
            if (pattern.features.isNotEmpty()) {
                Text("Features", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                pattern.features.forEach { feature ->
                    BulletLine(prefix = "✓", text = feature, prefixColor = Color(0xFF2E7D32))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Limitations (warning prefix — contract requires ⚠)
            if (pattern.limitations.isNotEmpty()) {
                Text("Limitations", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                pattern.limitations.forEach { limitation ->
                    BulletLine(prefix = "⚠", text = limitation, prefixColor = Color(0xFFE65100))
                }
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
