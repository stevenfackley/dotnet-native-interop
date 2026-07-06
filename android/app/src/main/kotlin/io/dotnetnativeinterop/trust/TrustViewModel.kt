package io.dotnetnativeinterop.trust

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.feature.FeatureCatalogService
import io.dotnetnativeinterop.feature.FfiFeatureService
import io.dotnetnativeinterop.feature.PbFeatureService
import io.dotnetnativeinterop.transport.pb.PbTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Drives the Trust inspector. Reads the engine's `trust~posture` (engine-global command grammar, so any
 * transport can answer it — the in-process FFI catalog is cheapest) and lets the user negotiate the
 * opt-in PQ channel on the binary transport.
 *
 * The PQ toggle is HONEST end-to-end: enabling it restarts the native pb server in require-PQ mode and
 * runs one binary request so a real ML-KEM/ML-DSA handshake completes and publishes its live params;
 * only then does the re-fetched posture flip the binary transport to "encrypted" with those params. No
 * fake green lock — plaintext until a handshake genuinely negotiates a channel.
 */
public class TrustViewModel(
    private val catalog: FeatureCatalogService = FfiFeatureService(),
    private val binaryCatalog: FeatureCatalogService = PbFeatureService(),
) : ViewModel() {

    public data class UiState(
        val report: TrustPostureReport? = null,
        val loading: Boolean = false,
        val negotiating: Boolean = false,
        val pqRequested: Boolean = false,
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    /** Re-reads the current posture from the engine. */
    public fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val result = catalog.run("trust~posture")
                json.decodeFromString<TrustPostureReport>(result.result)
            }.onSuccess { report ->
                _state.update { it.copy(report = report, loading = false) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "trust~posture failed") }
            }
        }
    }

    /**
     * Turns the PQ channel on/off. On: restart the pb server in PQ mode and run one request so a real
     * handshake completes; then refresh so the posture shows the negotiated params. Off: drop back to
     * plaintext. Any failure is surfaced (e.g. the native library predates the Wave B pb exports).
     */
    public fun setPqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(negotiating = true, pqRequested = enabled, error = null) }
            runCatching {
                PbTransport.setSecure(enabled)
                // Force a connection so the handshake runs (and publishes live params) or plaintext resumes.
                binaryCatalog.run("ping")
            }.onFailure { e ->
                _state.update { it.copy(error = "PQ negotiation failed: ${e.message}") }
            }
            _state.update { it.copy(negotiating = false) }
            refresh()
        }
    }
}
