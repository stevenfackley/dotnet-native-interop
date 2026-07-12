package io.dotnetnativeinterop.log

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
 * Drives the Analysis Log segment. Drains the engine log ring (`dni_log_drain`) on demand and holds the
 * most recent drain, plus a cumulative dropped-record count. Ring overflow is disclosed (never silently
 * swallowed) — the payload's `dropped` is accumulated and shown. Mirrors [io.dotnetnativeinterop.trace.TraceViewModel].
 */
public class LogViewModel : ViewModel() {

    public data class UiState(
        val records: List<LogRecord> = emptyList(),
        val nowUs: Double = 0.0,
        val capacity: Int = 256,
        val droppedThisDrain: Long = 0,
        val droppedTotal: Long = 0,
        val lastDrainCount: Int = 0,
        val loading: Boolean = false,
        val error: String? = null,
        val filter: LogFilter = LogFilter.All,
    ) {
        /** The records to render — filtered by severity, newest LAST (drain order is oldest-first). */
        val visibleRecords: List<LogRecord>
            get() = records.filter { levelRank(it.level) >= filter.minRank }

        /** How many records the current severity filter hides (shown as a hint when > 0). */
        val hiddenByFilter: Int get() = records.size - visibleRecords.size
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(UiState())
    public val state: StateFlow<UiState> = _state.asStateFlow()

    /** Drains the ring and replaces the current view with the drained records. */
    public fun drain() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val raw = withContext(Dispatchers.IO) { NativeBridge.nativeLogDrain() }
            if (raw == null) {
                _state.update { it.copy(loading = false, error = "dni_log_drain returned null") }
                return@launch
            }
            runCatching { json.decodeFromString<LogDrain>(raw) }
                .onSuccess { drain ->
                    _state.update {
                        it.copy(
                            records = drain.records,
                            nowUs = drain.nowUs,
                            capacity = drain.capacity,
                            droppedThisDrain = drain.dropped,
                            droppedTotal = it.droppedTotal + drain.dropped,
                            lastDrainCount = drain.records.size,
                            loading = false,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
        }
    }

    public fun selectFilter(filter: LogFilter) {
        _state.update { it.copy(filter = filter) }
    }
}
