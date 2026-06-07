package io.dotnetnativeinterop.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class AiSearchUiState(
    val corpus: String = "facts",
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

public class AiSearchViewModel(
    private val service: SearchService = SearchService(),
) : ViewModel() {

    private val _state = MutableStateFlow(AiSearchUiState())
    public val state: StateFlow<AiSearchUiState> = _state.asStateFlow()

    public val corpora: List<String> = listOf("features", "facts", "manuals")

    public fun selectCorpus(c: String): Unit = _state.update { it.copy(corpus = c) }
    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }

    public fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { service.search(s.query, s.corpus) }
                .onSuccess { r -> _state.update { it.copy(results = r, loading = false) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
}
