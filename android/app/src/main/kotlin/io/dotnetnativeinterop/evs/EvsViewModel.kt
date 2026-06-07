package io.dotnetnativeinterop.evs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.AssetExtractor
import io.dotnetnativeinterop.model.EdgeHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public data class EvsUiState(
    val query: String = "",
    val results: List<EdgeHit> = emptyList(),
    val availableErrorCodes: List<String> = emptyList(),
    val availableTools: List<String> = emptyList(),
    val activeErrorCodes: Set<String> = emptySet(),
    val activeTools: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class EvsViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = EdgeSearchEngine(AssetExtractor.ensure(app))
    private val _state = MutableStateFlow(EvsUiState())
    public val state: StateFlow<EvsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.Default) { engine.facets() } }
                .onSuccess { (codes, tools) ->
                    _state.update { it.copy(availableErrorCodes = codes, availableTools = tools) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }
    public fun toggleErrorCode(c: String): Unit = _state.update {
        it.copy(activeErrorCodes = it.activeErrorCodes.toggle(c))
    }
    public fun toggleTool(t: String): Unit = _state.update { it.copy(activeTools = it.activeTools.toggle(t)) }

    public fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { engine.search(s.query, errorCodes = s.activeErrorCodes, tools = s.activeTools) }
                .onSuccess { r -> _state.update { it.copy(results = r, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    private fun Set<String>.toggle(v: String): Set<String> = if (v in this) this - v else this + v
}
