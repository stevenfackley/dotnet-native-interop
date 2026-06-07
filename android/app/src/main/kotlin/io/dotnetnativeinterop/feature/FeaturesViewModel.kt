package io.dotnetnativeinterop.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.model.RunStatus
import io.dotnetnativeinterop.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class FeaturesUiState(
    val transport: TransportKind = TransportKind.Ffi,
    val descriptors: List<FeatureDescriptor> = emptyList(),
    val status: Map<String, RunStatus> = emptyMap(),
    val results: Map<String, FeatureResult> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class FeaturesViewModel(
    private val serviceFor: (TransportKind) -> FeatureCatalogService = ::defaultServiceFor,
) : ViewModel() {

    private val _state = MutableStateFlow(FeaturesUiState())
    public val state: StateFlow<FeaturesUiState> = _state.asStateFlow()

    init { loadCatalog() }

    public fun selectTransport(t: TransportKind) {
        if (t == _state.value.transport) return
        _state.update { it.copy(transport = t, descriptors = emptyList(), status = emptyMap(), results = emptyMap()) }
        loadCatalog()
    }

    public fun loadCatalog() {
        val svc = serviceFor(_state.value.transport)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { svc.descriptors() }
                .onSuccess { d -> _state.update { it.copy(descriptors = d, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    public fun run(id: String) {
        val svc = serviceFor(_state.value.transport)
        viewModelScope.launch {
            _state.update { it.copy(status = it.status + (id to RunStatus.Running)) }
            runCatching { svc.run(id) }
                .onSuccess { r ->
                    _state.update {
                        it.copy(
                            results = it.results + (id to r),
                            status = it.status + (id to if (r.ok) RunStatus.Ok else RunStatus.Failed),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(status = it.status + (id to RunStatus.Failed), error = e.message) }
                }
        }
    }

    public fun runAll() {
        _state.value.descriptors.forEach { run(it.id) }
    }
}

internal fun defaultServiceFor(t: TransportKind): FeatureCatalogService = when (t) {
    TransportKind.Ffi -> FfiFeatureService()
    TransportKind.Http -> HttpFeatureService()
    TransportKind.Sqlite -> SqliteFeatureService()
}
