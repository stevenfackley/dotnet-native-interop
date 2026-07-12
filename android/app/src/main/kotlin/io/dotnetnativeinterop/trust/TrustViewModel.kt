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
    // The PQ on/off actuator — injected (not a hardcoded PbTransport.setSecure call) so the honesty-critical
    // revert-on-failure invariant in setPqEnabled is unit-testable without the native pb server. Default is
    // the real transport toggle (restarts the native pb server in require-PQ / plaintext mode).
    private val pqToggle: suspend (Boolean) -> Unit = { PbTransport.setSecure(it) },
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

    /** Re-reads the current posture from the engine (clearing any stale error first — a manual refresh). */
    public fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            applyPosture()
        }
    }

    /**
     * Re-reads `trust~posture` into state. On a successful read it updates the report but LEAVES an existing
     * error untouched — so a "PQ negotiation failed" banner set by [setPqEnabled] survives the posture refresh
     * that immediately follows it. (That trailing refresh used to call [refresh], whose up-front `error = null`
     * silently wiped the banner, so the failure was reverted on the switch but never disclosed.)
     */
    private suspend fun applyPosture() {
        runCatching {
            val result = catalog.run("trust~posture")
            json.decodeFromString<TrustPostureReport>(result.result)
        }.onSuccess { report ->
            _state.update { it.copy(report = report, loading = false) }
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: "trust~posture failed") }
        }
    }

    /**
     * Turns the PQ channel on/off. On: restart the pb server in PQ mode and run one request so a real
     * handshake completes; then refresh so the posture shows the negotiated params. Off: drop back to
     * plaintext. Any failure is surfaced (e.g. the native library predates the Wave B pb exports).
     */
    public fun setPqEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val previous = _state.value.pqRequested
            _state.update { it.copy(negotiating = true, pqRequested = enabled, error = null) }
            runCatching {
                pqToggle(enabled)
                // Force a connection so the handshake runs (and publishes live params) or plaintext resumes.
                binaryCatalog.run("ping")
            }.onFailure { e ->
                // Revert the toggle: it must never read ON while the refreshed posture reads PLAINTEXT
                // (the banner discloses the failure, but the switch itself must not lie — repo honesty DNA).
                _state.update { it.copy(error = "PQ negotiation failed: ${e.message}", pqRequested = previous) }
            }
            _state.update { it.copy(negotiating = false) }
            // Re-read posture WITHOUT clearing the error above — a failed negotiation must stay disclosed.
            applyPosture()
        }
    }
}
