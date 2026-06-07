package io.dotnetnativeinterop.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class RagUiState(
    val query: String = "",
    val sources: List<SearchResult> = emptyList(),
    val answer: String = "",
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    val streaming: Boolean = false,
    val error: String? = null,
)

public class RagViewModel(
    private val search: SearchService = SearchService(),
    private val rag: RagService = FfiRagService(),
) : ViewModel() {

    private val _state = MutableStateFlow(RagUiState())
    public val state: StateFlow<RagUiState> = _state.asStateFlow()

    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }

    public fun ask() {
        val q = _state.value.query
        if (q.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(streaming = true, answer = "", sources = emptyList(),
                    firstTokenMs = null, totalMs = null, error = null)
            }

            runCatching { search.search(q, "manuals") }
                .onSuccess { s -> _state.update { it.copy(sources = s) } }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }

            val start = System.nanoTime()
            runCatching {
                rag.answer(q).collect { fragment ->
                    _state.update {
                        val first = it.firstTokenMs ?: ((System.nanoTime() - start) / 1_000_000)
                        it.copy(answer = it.answer + fragment, firstTokenMs = first)
                    }
                }
            }.also { res ->
                _state.update {
                    it.copy(
                        streaming = false,
                        totalMs = (System.nanoTime() - start) / 1_000_000,
                        error = res.exceptionOrNull()?.message ?: it.error,
                    )
                }
            }
        }
    }
}
