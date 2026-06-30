package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.EngineStats
import io.dotnetnativeinterop.model.TransportKind
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public data class LatencyUiState(
    val stats: EngineStats? = null,
    val samplesMs: List<Double> = emptyList(),
    val sampling: Boolean = false,
    val error: String? = null,
)

/**
 * Backs the Latency tab: live engine telemetry plus client-side round-trip timing of `ping` /
 * `bench-echo` over any of the three transports. Holds every transport's service and the selected
 * transport, mirroring the iOS `LatencyViewModel` (Shared/Latency/LatencyViewModel.swift).
 */
public class LatencyViewModel(
    serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    // One service per transport, built once (HTTP starts its port lazily). The immutable map is
    // safe to read from the IO dispatcher; mirrors the iOS TransportMap.
    private val services: Map<TransportKind, FeatureCatalogService> =
        TransportKind.entries.associateWith(serviceFor)

    private fun service(t: TransportKind): FeatureCatalogService = services.getValue(t)

    private val _state = MutableStateFlow(LatencyUiState())
    public val state: StateFlow<LatencyUiState> = _state.asStateFlow()

    private val _transport = MutableStateFlow(TransportKind.Ffi)
    public val transport: StateFlow<TransportKind> = _transport.asStateFlow()

    public fun selectTransport(t: TransportKind) { _transport.value = t }

    /** A measured series. Failed calls are excluded from [samples] (they have no honest timing) but
     *  counted, so the charts disclose incomplete data instead of hiding it. Mirrors iOS Series. */
    public data class Series(
        val samples: List<Double> = emptyList(),
        val failures: Int = 0,
    )

    /** One client-side round-trip of [command] over [t], in milliseconds (null on failure). */
    public suspend fun roundTripMs(command: String, t: TransportKind): Double? =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            if (runCatching { service(t).run(command) }.isSuccess) {
                (System.nanoTime() - start) / 1_000_000.0
            } else {
                null
            }
        }

    /**
     * [count] sequential `ping` round-trips over [t] (pure transport overhead).
     * Failure policy (user decision 2026-06-10, mirrored from the iOS LatencyViewModel): skip +
     * disclose — a failed ping never produces a sample but is always counted. A dead transport would
     * otherwise burn [count] sequential timeouts, so if the first calls ALL fail, stop early and
     * report the remainder as failed — the user gets the failure banner in seconds.
     */
    public suspend fun pingSeries(count: Int, t: TransportKind): Series = withContext(Dispatchers.IO) {
        val svc = service(t)
        val samples = ArrayList<Double>(count)
        var failures = 0
        for (i in 0 until count) {
            val start = System.nanoTime()
            if (runCatching { svc.run("ping") }.isSuccess) {
                samples.add((System.nanoTime() - start) / 1_000_000.0)
            } else {
                failures++
                if (samples.isEmpty() && failures >= 5) {
                    failures = count - samples.size
                    break
                }
            }
        }
        Series(samples, failures)
    }

    public fun refreshStats() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = NativeBridge.nativeEngineStats() ?: error("nativeEngineStats null")
                    catalogJson.decodeFromString<EngineStats>(raw)
                }
            }.onSuccess { s -> _state.update { it.copy(stats = s) } }
             .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    public fun sample(count: Int = 30) {
        viewModelScope.launch {
            _state.update { it.copy(sampling = true, samplesMs = emptyList(), error = null) }
            val svc = service(_transport.value)
            val id = runCatching { svc.descriptors().firstOrNull()?.id }.getOrNull()
            if (id == null) { _state.update { it.copy(sampling = false, error = "no features") }; return@launch }
            val out = mutableListOf<Double>()
            var failed = 0
            // Same skip + disclose failure policy as pingSeries.
            for (i in 0 until count) {
                val start = System.nanoTime()
                runCatching { svc.run(id) }
                    .onSuccess { out.add((System.nanoTime() - start) / 1_000_000.0) }
                    .onFailure { failed++ }
                if (out.isEmpty() && failed >= 5) {
                    failed = count
                    break
                }
            }
            _state.update {
                it.copy(
                    sampling = false, samplesMs = out,
                    error = if (failed > 0) "$failed of $count calls failed and are excluded" else null,
                )
            }
        }
    }
}
