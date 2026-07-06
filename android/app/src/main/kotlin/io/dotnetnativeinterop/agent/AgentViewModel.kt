package io.dotnetnativeinterop.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.QueryInput
import io.dotnetnativeinterop.trace.TraceSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a turn currently reads to the user — distinguishes a clean answer from a capped/errored one. */
public enum class TurnOutcome { Streaming, Answered, StepCapReached, Error }

/** One chat turn: the user's query, the streamed/final answer, and (once the turn ends) its honest outcome. */
public data class ForemanTurn(
    val query: String,
    val answer: String = "",
    val outcome: TurnOutcome = TurnOutcome.Streaming,
    val toolSteps: Int = 0,
    val backend: String? = null,
    val toolSpans: List<TraceSpan> = emptyList(),
)

public data class ForemanUiState(
    val query: String = "",
    val turns: List<ForemanTurn> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the Foreman section: runs one turn at a time over [AgentService], appending streamed answer
 * fragments to the LAST turn and, on the terminal status fragment, honestly recording how the turn ended
 * (never presenting [TurnOutcome.StepCapReached]/[TurnOutcome.Error] as [TurnOutcome.Answered]) plus the
 * ACTUAL backend badge read off the wire. After the status arrives, drains the trace ring once for the
 * turn's tool-call strip — see [turnSpansFrom] for why that correlation is best-effort.
 *
 * `@JvmOverloads` makes every trailing-default combination — including a genuine no-arg constructor —
 * reflectable, so `viewModel()` (Compose's zero-arg factory path) can instantiate this class directly.
 * [traceDispatcher] is injected (rather than hardcoded) so tests can run the trace-drain hop
 * synchronously with an unconfined dispatcher instead of a real background thread pool.
 */
public class AgentViewModel @JvmOverloads constructor(
    private val service: AgentService = FfiAgentService(),
    private val traceReader: AgentTraceReader = NativeAgentTraceReader(),
    private val traceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ForemanUiState())
    public val state: StateFlow<ForemanUiState> = _state.asStateFlow()
    private var turnJob: Job? = null

    public fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    /** Starts a new turn (cancelling any turn still in flight — one active turn at a time). */
    public fun ask() {
        val q = QueryInput.sanitize(_state.value.query) ?: return
        turnJob?.cancel()

        _state.update {
            it.copy(query = "", running = true, error = null, turns = it.turns + ForemanTurn(query = q))
        }

        turnJob = viewModelScope.launch {
            runCatching {
                service.run(q).collect { fragment ->
                    when (fragment) {
                        is AgentFragment.Answer -> appendAnswer(fragment.text)
                        is AgentFragment.Status -> applyStatus(fragment.status)
                    }
                }
            }.onFailure { e ->
                // Transport-level failure (e.g. session start rejected) with no status fragment at all —
                // still honestly mark the turn as Error, never leave it looking like a clean answer.
                updateLastTurn { it.copy(outcome = TurnOutcome.Error) }
                _state.update { it.copy(error = "Foreman turn failed: ${e.message}") }
            }
            _state.update { it.copy(running = false) }
        }
    }

    /** dni_session_cancel-equivalent: stops the in-flight turn (its Flow's `finally` frees the session). */
    public fun cancel() {
        turnJob?.cancel()
        _state.update { it.copy(running = false) }
    }

    private fun appendAnswer(text: String) {
        updateLastTurn { it.copy(answer = it.answer + text) }
    }

    private suspend fun applyStatus(status: AgentTurnStatus) {
        val outcome = when (status.stopReason) {
            AgentStopReason.Answered -> TurnOutcome.Answered
            AgentStopReason.StepCapReached -> TurnOutcome.StepCapReached
            AgentStopReason.Error -> TurnOutcome.Error
        }
        // dni_trace_drain is a synchronous native call — hop off Main, matching TraceViewModel's own drain.
        val drain = withContext(traceDispatcher) { runCatching { traceReader.drain() }.getOrNull() }
        val spans = turnSpansFrom(drain, status.toolSteps)
        updateLastTurn {
            it.copy(outcome = outcome, toolSteps = status.toolSteps, backend = status.backend, toolSpans = spans)
        }
    }

    private fun updateLastTurn(transform: (ForemanTurn) -> ForemanTurn) {
        _state.update { s ->
            if (s.turns.isEmpty()) return@update s
            val idx = s.turns.lastIndex
            val updated = s.turns.toMutableList()
            updated[idx] = transform(updated[idx])
            s.copy(turns = updated)
        }
    }
}
