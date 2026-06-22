package io.dotnetnativeinterop.boundary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.model.BoundaryEcho
import io.dotnetnativeinterop.model.BoundaryInspector
import io.dotnetnativeinterop.model.BoundaryPhase
import io.dotnetnativeinterop.model.BoundaryPreset
import io.dotnetnativeinterop.model.BoundaryStreamToken
import io.dotnetnativeinterop.model.BoundaryThrow
import io.dotnetnativeinterop.model.OwnershipEntry
import io.dotnetnativeinterop.model.PhaseTiming
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

public data class BoundaryUiState(
    val preset: BoundaryPreset = BoundaryPreset.Echo,
    val inspector: BoundaryInspector = BoundaryInspector.Timing,
    val input: String = "Hello",
    val running: Boolean = false,
    val activePhase: BoundaryPhase? = null,
    val timing: PhaseTiming = PhaseTiming(),
    val echo: BoundaryEcho? = null,
    val thrown: BoundaryThrow? = null,
    val streamTokens: List<BoundaryStreamToken> = emptyList(),
    val ledger: List<OwnershipEntry> = emptyList(),
    val callerThreadId: Long = 0,
    val error: String? = null,
) {
    val callbackThreadId: Long? get() = streamTokens.lastOrNull()?.managedThreadId ?: echo?.managedThreadId
}

public class BoundaryViewModel(private val service: BoundaryService = FfiBoundaryService()) : ViewModel() {
    private val _state = MutableStateFlow(BoundaryUiState())
    public val state: StateFlow<BoundaryUiState> = _state.asStateFlow()
    private var streamJob: Job? = null

    public fun selectPreset(p: BoundaryPreset) { _state.update { it.copy(preset = p) } }
    public fun selectInspector(i: BoundaryInspector) { _state.update { it.copy(inspector = i) } }
    public fun setInput(s: String) { _state.update { it.copy(input = s) } }

    public fun run() {
        when (_state.value.preset) {
            BoundaryPreset.Echo, BoundaryPreset.Feature, BoundaryPreset.Pixels ->
                runEcho(_state.value.preset == BoundaryPreset.Pixels)
            BoundaryPreset.Exception -> runThrow()
            BoundaryPreset.Stream -> runStream()
        }
    }

    private fun runEcho(large: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            val payload = if (large) "▇".repeat(4096) else _state.value.input
            runCatching { service.echo(payload) }
                .onSuccess { t ->
                    _state.update {
                        it.copy(running = false, echo = t.echo, timing = t.timing, callerThreadId = t.callerThreadId,
                            ledger = echoLedger(it.input.encodeToByteArray().size, t.echo.len))
                    }
                }
                .onFailure { e -> _state.update { it.copy(running = false, error = "Echo over FFI failed: ${e.message}") } }
        }
    }

    private fun runThrow() {
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            runCatching { service.throwDemo() }
                .onSuccess { t -> _state.update { it.copy(running = false, thrown = t, inspector = BoundaryInspector.Error) } }
                .onFailure { e -> _state.update { it.copy(running = false, error = "Throw demo failed: ${e.message}") } }
        }
    }

    private fun runStream() {
        streamJob?.cancel()
        _state.update { it.copy(running = true, streamTokens = emptyList(), inspector = BoundaryInspector.Threads, error = null) }
        streamJob = viewModelScope.launch {
            runCatching {
                service.stream(_state.value.input.ifBlank { "stream demo" }, maxTokens = 24).collect { tok ->
                    if (tok.text.isNotEmpty()) _state.update { it.copy(streamTokens = it.streamTokens + tok) }
                }
            }.onFailure { e -> _state.update { it.copy(error = "Stream over FFI failed: ${e.message}") } }
            _state.update { it.copy(running = false) }
        }
    }

    public fun stop() { streamJob?.cancel(); _state.update { it.copy(running = false) } }

    /** Walk the phases on a cadence, auto-selecting each phase's inspector segment. */
    public fun autoStep() {
        viewModelScope.launch {
            for (phase in BoundaryPhase.values()) {
                _state.update { it.copy(activePhase = phase, inspector = segmentFor(phase)) }
                delay(600)
            }
            _state.update { it.copy(activePhase = null) }
        }
    }

    private fun segmentFor(p: BoundaryPhase): BoundaryInspector = when (p) {
        BoundaryPhase.Marshal -> BoundaryInspector.Bytes
        BoundaryPhase.Cross -> BoundaryInspector.Abi
        BoundaryPhase.Execute -> BoundaryInspector.Timing
        BoundaryPhase.Callback -> BoundaryInspector.Threads
        BoundaryPhase.Free -> BoundaryInspector.Memory
    }
}
