package io.dotnetnativeinterop.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Drives the Analysis Trace segment. Drains the engine span ring (`dni_trace_drain`) on demand and holds
 * the most recent drain for the waterfall, plus a cumulative dropped-span count. Ring overflow is
 * disclosed (never silently swallowed) — the drain payload's `dropped` is accumulated and shown.
 */
public class TraceViewModel : ViewModel() {

    public data class UiState(
        val spans: List<TraceSpan> = emptyList(),
        val nowUs: Double = 0.0,
        val capacity: Int = 512,
        val droppedThisDrain: Long = 0,
        val droppedTotal: Long = 0,
        val lastDrainCount: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        val selectedRequestId: String? = null,
    ) {
        /** Distinct request ids present in the current drain (for the waterfall's request picker). */
        val requestIds: List<String> get() = spans.mapNotNull { it.requestId }.distinct()

        /** The spans to render — all of them, or only the selected request's, sorted by start time. */
        val visibleSpans: List<TraceSpan>
            get() = spans
                .filter { selectedRequestId == null || it.requestId == selectedRequestId }
                .sortedBy { it.startUs }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    /** Drains the ring and replaces the current view with the drained spans. */
    public fun drain() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val raw = withContext(Dispatchers.IO) { NativeBridge.nativeTraceDrain() }
            if (raw == null) {
                _state.update { it.copy(loading = false, error = "dni_trace_drain returned null") }
                return@launch
            }
            runCatching { json.decodeFromString<TraceDrain>(raw) }
                .onSuccess { drain ->
                    _state.update {
                        it.copy(
                            spans = drain.spans,
                            nowUs = drain.nowUs,
                            capacity = drain.capacity,
                            droppedThisDrain = drain.dropped,
                            droppedTotal = it.droppedTotal + drain.dropped,
                            lastDrainCount = drain.spans.size,
                            loading = false,
                            selectedRequestId = null,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    public fun selectRequest(requestId: String?) {
        _state.update { it.copy(selectedRequestId = requestId) }
    }
}
