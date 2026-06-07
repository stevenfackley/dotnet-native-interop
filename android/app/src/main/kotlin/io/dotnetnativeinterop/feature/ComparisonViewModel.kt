package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class TransportTiming(
    val transport: TransportKind,
    val featureCount: Int,
    val passed: Int,
    val totalMs: Double,
)

public data class ComparisonUiState(
    val running: Boolean = false,
    val timings: List<TransportTiming> = emptyList(),
    val error: String? = null,
)

public class ComparisonViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _state = MutableStateFlow(ComparisonUiState())
    public val state: StateFlow<ComparisonUiState> = _state.asStateFlow()

    public fun runAll() {
        viewModelScope.launch {
            _state.update { it.copy(running = true, timings = emptyList(), error = null) }
            val out = mutableListOf<TransportTiming>()
            runCatching {
                for (t in TransportKind.entries) {
                    val svc = serviceFor(t)
                    val ids = svc.descriptors().map { it.id }
                    var passed = 0
                    val start = System.nanoTime()
                    for (id in ids) { if (svc.run(id).ok) passed++ }
                    val ms = (System.nanoTime() - start) / 1_000_000.0
                    out.add(TransportTiming(t, ids.size, passed, ms))
                }
            }.onSuccess { _state.update { it.copy(running = false, timings = out) } }
             .onFailure { e -> _state.update { it.copy(running = false, timings = out, error = e.message) } }
        }
    }
}
