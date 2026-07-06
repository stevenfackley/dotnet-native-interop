package io.dotnetnativeinterop.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.dotnetnativeinterop.ui.Instrument
import io.dotnetnativeinterop.ui.Radii
import io.dotnetnativeinterop.ui.Spacing
import io.dotnetnativeinterop.ui.components.ErrorBanner
import io.dotnetnativeinterop.ui.components.InstrumentCard
import io.dotnetnativeinterop.ui.components.PanelHeader
import io.dotnetnativeinterop.ui.components.StatCell

/** Maps a posture transport token to its canonical instrument color (dot); mirrors transportColor(). */
private fun postureColor(transport: String): Color = when (transport.lowercase()) {
    "ffi" -> Instrument.transportFfi
    "binary" -> Instrument.transportBinary
    "http" -> Instrument.transportHttp
    "sqlcipher", "sqlite" -> Instrument.transportSqlite
    else -> Instrument.textSecondary
}

/**
 * Trust inspector: the honest per-transport security posture + an opt-in PQ toggle for the binary
 * transport. Told straight — FFI is an in-process boundary, HTTP is PLAINTEXT loopback, SQLCipher is
 * AES-256 at rest, and the binary transport shows plaintext until a real ML-KEM/ML-DSA handshake
 * negotiates a channel (then its LIVE params appear). Used in both the Boundary hub and About.
 */
@Composable
internal fun TrustInspector(
    modifier: Modifier = Modifier,
    vm: TrustViewModel = viewModel(),
    interactive: Boolean = true,
    scrollable: Boolean = true,
) {
    val state by vm.state.collectAsState()

    // When embedded in an already-scrolling parent (e.g. About), don't nest a second vertical scroll.
    val base = if (scrollable) {
        modifier.fillMaxWidth().background(Instrument.bg0).verticalScroll(rememberScrollState()).padding(Spacing.l)
    } else {
        modifier.fillMaxWidth()
    }

    Column(base, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        PanelHeader("Trust · per-transport security posture")

        state.error?.let { ErrorBanner(message = it, onRetry = { vm.refresh() }) }

        if (interactive) {
            InstrumentCard {
                PanelHeader("post-quantum channel · binary transport")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ML-KEM-768 · ML-DSA-65 · AES-256-GCM",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Instrument.textPrimary,
                        )
                        Text(
                            if (state.negotiating) "negotiating handshake…"
                            else "opt-in; off = plaintext framed protobuf",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.negotiating) Instrument.warn else Instrument.textSecondary,
                        )
                    }
                    Switch(
                        checked = state.pqRequested,
                        onCheckedChange = { vm.setPqEnabled(it) },
                        enabled = !state.negotiating,
                    )
                }
                Text(
                    "Switching restarts the pb server — its PQ mode is fixed per boot (honest, not hot-swapped).",
                    color = Instrument.textTertiary,
                    fontSize = 11.sp,
                )
            }
        }

        state.report?.transports?.forEach { TransportPostureCard(it) }
        state.report?.binaryPqChannel?.let { PqParamsCard(it) }

        if (state.report == null && !state.loading && state.error == null) {
            Text("No posture yet.", color = Instrument.textSecondary)
        }
    }
}

@Composable
private fun TransportPostureCard(posture: TransportPosture) {
    InstrumentCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(postureColor(posture.transport), CircleShape))
            Spacer(Modifier.width(Spacing.s))
            Text(
                posture.transport.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Instrument.textPrimary,
            )
            Spacer(Modifier.weight(1f))
            SecurityBadge(inProcess = posture.inProcess, encrypted = posture.encrypted)
        }
        Text(posture.wire, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
        Text(posture.detail, style = MaterialTheme.typography.bodySmall, color = Instrument.textSecondary)
    }
}

@Composable
private fun SecurityBadge(inProcess: Boolean, encrypted: Boolean) {
    val (label, tint) = when {
        inProcess -> "IN-PROCESS" to Instrument.textSecondary
        encrypted -> "ENCRYPTED" to Instrument.ok
        else -> "PLAINTEXT" to Instrument.fail
    }
    Text(
        label,
        modifier = Modifier
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(Radii.chip))
            .padding(horizontal = Spacing.s, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tint,
    )
}

@Composable
private fun PqParamsCard(params: PqChannelParams) {
    InstrumentCard {
        PanelHeader("live PQ channel · negotiated")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            StatCell("KEM", params.kem, Modifier.weight(1f), tint = Instrument.transportBinary)
            StatCell("SIG", params.sig, Modifier.weight(1f))
            StatCell("CIPHER", params.cipher, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            StatCell("KEM PUB", "${params.kemPublicKeyBytes} B", Modifier.weight(1f))
            StatCell("CIPHERTEXT", "${params.ciphertextBytes} B", Modifier.weight(1f))
            StatCell("SECRET", "${params.sharedSecretBytes} B", Modifier.weight(1f))
        }
        Text(
            "handshake ${"%.1f".format(params.handshakeUs)} µs",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Instrument.ok,
        )
    }
}
