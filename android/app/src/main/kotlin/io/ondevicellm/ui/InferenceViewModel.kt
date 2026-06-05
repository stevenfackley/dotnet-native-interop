package io.ondevicellm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ondevicellm.transport.FfiClient
import io.ondevicellm.transport.GrpcUdsClient
import io.ondevicellm.transport.HttpClient
import io.ondevicellm.transport.InferRequest
import io.ondevicellm.transport.InferenceClient
import io.ondevicellm.transport.SqliteClient
import io.ondevicellm.transport.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Transport identifier matching the contract spec. */
public enum class TransportId(public val id: String, public val label: String) {
    FFI("ffi", "FFI"),
    HTTP("http", "HTTP"),
    GRPC("grpc", "gRPC/UDS"),
    SQLITE("sqlite", "SQLite"),
}

/** Metrics captured per inference run. */
public data class RunMetrics(
    val transportId: String = "",
    val timeToFirstTokenMs: Long = 0L,
    val tokensPerSec: Double = 0.0,
    val totalTimeMs: Long = 0L,
    val tokenCount: Int = 0,
)

/** Immutable snapshot of the full UI state. */
public data class InferenceUiState(
    val prompt: String = "",
    val selectedTransport: TransportId = TransportId.FFI,
    val tokens: List<Token> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
    val metrics: RunMetrics = RunMetrics(),
    val patterns: PatternsJson? = null,
    val engineReady: Boolean = false,
)

public class InferenceViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(InferenceUiState())
    public val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    // Lazy-initialized clients — only created when first needed so the HTTP/gRPC
    // servers don't bind until the user actually selects that transport.
    private val ffiClient: FfiClient by lazy { FfiClient() }
    private val httpClient: HttpClient by lazy { HttpClient() }
    private val grpcClient: GrpcUdsClient by lazy { GrpcUdsClient(app.cacheDir) }
    private val sqliteClient: SqliteClient by lazy { SqliteClient(app.applicationContext) }

    private var inferJob: Job? = null

    init {
        loadPatterns()
        pollEngineReady()
    }

    /**
     * The Application class starts nativeInitialize() in a background coroutine.
     * We poll (cheaply, once) here to mark the engine ready in the UI state once
     * the native call completes.  nativeInitialize() is idempotent — calling it
     * twice returns OK without re-initializing.
     */
    private fun pollEngineReady() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val status = io.ondevicellm.transport.NativeBridge.nativeInitialize()
                if (status == 0) {
                    _uiState.update { it.copy(engineReady = true) }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Patterns loading
    // -----------------------------------------------------------------------

    private fun loadPatterns() {
        viewModelScope.launch {
            runCatching {
                val json = Json { ignoreUnknownKeys = true }
                val raw = getApplication<Application>()
                    .assets
                    .open("patterns.json")
                    .bufferedReader()
                    .readText()
                json.decodeFromString(PatternsJson.serializer(), raw)
            }.onSuccess { patterns ->
                _uiState.update { it.copy(patterns = patterns) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = "Failed to load patterns.json: ${e.message}") }
            }
        }
    }

    // -----------------------------------------------------------------------
    // User-driven state mutations
    // -----------------------------------------------------------------------

    public fun onPromptChanged(prompt: String) {
        _uiState.update { it.copy(prompt = prompt, error = null) }
    }

    public fun onTransportSelected(transport: TransportId) {
        _uiState.update { it.copy(selectedTransport = transport, error = null) }
    }

    // -----------------------------------------------------------------------
    // Inference
    // -----------------------------------------------------------------------

    public fun startInference() {
        val state = _uiState.value
        if (state.isRunning) return
        if (state.prompt.isBlank()) {
            _uiState.update { it.copy(error = "Prompt is empty") }
            return
        }

        inferJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, tokens = emptyList(), error = null) }

            val request = InferRequest(
                prompt = state.prompt,
                maxTokens = 256,
                temperature = 0.8f,
            )

            val client = resolveClient(state.selectedTransport)

            var startTime = 0L
            var firstTokenTime = 0L
            var tokenCount = 0

            runCatching {
                startTime = System.currentTimeMillis()

                client.stream(request).collect { token ->
                    if (tokenCount == 0) {
                        firstTokenTime = System.currentTimeMillis()
                    }
                    if (!token.isFinal) {
                        tokenCount++
                    }
                    _uiState.update { s ->
                        s.copy(tokens = s.tokens + token)
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _uiState.update { it.copy(error = e.message ?: "Unknown error") }
            }

            val endTime = System.currentTimeMillis()
            val totalMs = endTime - startTime
            val ttft = if (firstTokenTime > 0) firstTokenTime - startTime else 0L
            val tps = if (totalMs > 0 && tokenCount > 0) {
                tokenCount.toDouble() / (totalMs / 1000.0)
            } else 0.0

            _uiState.update { s ->
                s.copy(
                    isRunning = false,
                    metrics = RunMetrics(
                        transportId = state.selectedTransport.id,
                        timeToFirstTokenMs = ttft,
                        tokensPerSec = tps,
                        totalTimeMs = totalMs,
                        tokenCount = tokenCount,
                    )
                )
            }
        }
    }

    public fun stopInference() {
        inferJob?.cancel()
        inferJob = null
        _uiState.update { it.copy(isRunning = false) }
    }

    // -----------------------------------------------------------------------
    // Client resolution — starts the server-side if needed
    // -----------------------------------------------------------------------

    private suspend fun resolveClient(transport: TransportId): InferenceClient {
        return when (transport) {
            TransportId.FFI -> ffiClient

            TransportId.HTTP -> httpClient.also { client ->
                // Idempotent: if already running, nativeHttpStart is a no-op.
                // The contract says the caller must restart on foreground, so
                // this launch-time start is sufficient for the POC.
                runCatching { client.start() }
            }

            TransportId.GRPC -> grpcClient.also { client ->
                runCatching { client.start() }
            }

            TransportId.SQLITE -> sqliteClient.also { client ->
                runCatching { client.start() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            runCatching { httpClient.stop() }
            runCatching { grpcClient.stop() }
            runCatching { sqliteClient.stop() }
        }
    }
}
