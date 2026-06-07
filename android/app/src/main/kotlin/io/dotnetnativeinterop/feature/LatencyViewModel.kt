package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.EngineStats
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

public class LatencyViewModel(
    private val service: FeatureCatalogService = FfiFeatureService(),
) : ViewModel() {

    private val _state = MutableStateFlow(LatencyUiState())
    public val state: StateFlow<LatencyUiState> = _state.asStateFlow()

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
            val ids = runCatching { service.descriptors().firstOrNull()?.id }.getOrNull()
            if (ids == null) { _state.update { it.copy(sampling = false, error = "no features") }; return@launch }
            val out = mutableListOf<Double>()
            runCatching {
                repeat(count) {
                    val start = System.nanoTime()
                    service.run(ids)
                    out.add((System.nanoTime() - start) / 1_000_000.0)
                }
            }.onSuccess { _state.update { it.copy(sampling = false, samplesMs = out) } }
             .onFailure { e -> _state.update { it.copy(sampling = false, samplesMs = out, error = e.message) } }
        }
    }
}
