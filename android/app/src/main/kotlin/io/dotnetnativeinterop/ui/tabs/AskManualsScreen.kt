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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.dotnetnativeinterop.ai.GgufDownloadState
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

        // Download-on-first-run affordance. Never shown once neuralActive is true — the model is
        // only ever loaded once per process (see RagViewModel.reinitializeEngine).
        if (!s.neuralActive) {
            ModelDownloadCard(
                download = s.download,
                // A method reference would bind to downloadModel's full (String) -> Unit signature —
                // defaults only apply at a normal call site, so wrap in a lambda to use the default URL.
                onDownload = { vm.downloadModel() },
                onCancel = vm::cancelDownload,
            )
            Spacer(modifier = Modifier.height(Spacing.m))
        }

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
                // Honest by construction: this label always reflects what the engine actually ran,
                // never what the UI merely hopes for — see RagUiState.neuralActive's kdoc.
                if (s.neuralActive) {
                    "on-device neural generation (Llama-3.2-1B, llama.cpp)"
                } else {
                    "grounded extraction (download the model above for on-device generation)"
                },
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

/**
 * The GGUF download affordance: an honest disclosure of the current on-device-model state plus the
 * action available for it (download / cancel / retry). Never claims neural readiness it doesn't
 * have — every branch names the state it's actually in.
 */
@Composable
private fun ModelDownloadCard(
    download: GgufDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    InstrumentCard(modifier = Modifier.fillMaxWidth()) {
        when (download) {
            is GgufDownloadState.Downloading -> {
                Text(
                    "Downloading Llama-3.2-1B… ${download.percent}%" +
                        if (download.totalBytes > 0) {
                            "  (${formatMb(download.bytesDownloaded)} / ${formatMb(download.totalBytes)} MB)"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textSecondary,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                LinearProgressIndicator(
                    progress = { if (download.totalBytes > 0) download.percent / 100f else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Instrument.accent,
                    trackColor = Instrument.bg2,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }

            GgufDownloadState.Verifying -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    Text("Verifying download…", style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
                }
            }

            GgufDownloadState.Completed -> {
                // Transient — RagViewModel flips neuralActive right after this and the card
                // disappears from the composition entirely. No dedicated visual needed.
            }

            is GgufDownloadState.Failed -> {
                ErrorBanner("Model download failed: ${download.message}", onRetry = onDownload)
            }

            GgufDownloadState.Cancelled -> {
                Text(
                    "Download cancelled. Progress is kept — resuming continues where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textSecondary,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Button(onClick = onDownload) { Text("Resume download") }
            }

            GgufDownloadState.NotStarted -> {
                Text(
                    "On-device model not downloaded — answers are grounded extraction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textPrimary,
                )
                Text(
                    "Download the 0.77 GB Llama-3.2-1B model for neural answers (Wi-Fi recommended).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Instrument.textTertiary,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Button(onClick = onDownload) { Text("Download model") }
            }
        }
    }
}

private fun formatMb(bytes: Long): String = "%.0f".format(bytes / (1024.0 * 1024.0))
