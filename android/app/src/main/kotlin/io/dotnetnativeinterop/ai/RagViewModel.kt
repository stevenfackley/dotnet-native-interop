package io.dotnetnativeinterop.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.dotnetnativeinterop.AssetExtractor
import io.dotnetnativeinterop.BuildConfig
import io.dotnetnativeinterop.model.QueryInput
import io.dotnetnativeinterop.model.SearchResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public data class RagUiState(
    val query: String = "",
    val sources: List<SearchResult> = emptyList(),
    val answer: String = "",
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    val streaming: Boolean = false,
    val error: String? = null,
    // Neural-model download affordance (additive; grounded extraction remains the honest fallback
    // whenever the GGUF isn't present/loadable — see EngineHost.BuildRagModel). [neuralActive]
    // reflects what the ENGINE is actually resolved to, not merely whether a file exists on disk —
    // it only flips after a successful post-download engine re-init.
    val neuralActive: Boolean = false,
    val download: GgufDownloadState = GgufDownloadState.NotStarted,
)

// @JvmOverloads matters here, not just style: Compose's viewModel() resolves a plain AndroidViewModel
// via reflection for an (Application)-only constructor (see EvsViewModel/InferenceViewModel, the
// project's existing pattern). Without @JvmOverloads a Kotlin default-args constructor only exists
// at the bytecode level as ONE method taking every parameter (+ synthetic mask/marker) — reflection
// would fail to find an (Application) overload and the tab would crash the moment it's opened.
public class RagViewModel @JvmOverloads constructor(
    app: Application,
    private val search: SearchService = SearchService(),
    private val rag: RagService = FfiRagService(),
    private val ggufDownloader: GgufDownloader = GgufDownloader(AssetExtractor.dir(app)),
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RagUiState(neuralActive = ggufDownloader.isDownloaded()))
    public val state: StateFlow<RagUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ggufDownloader.state.collect { s -> _state.update { it.copy(download = s) } }
        }
    }

    public fun setQuery(q: String): Unit = _state.update { it.copy(query = q) }

    /** Starts (or resumes, if a `.part` file already exists) the GGUF download. */
    public fun downloadModel(url: String = BuildConfig.GGUF_URL) {
        viewModelScope.launch {
            ggufDownloader.download(url)
            // download() never throws except real coroutine cancellation (see GgufDownloader kdoc);
            // every other outcome is reflected in ggufDownloader.state, already mirrored into
            // state.download above. Only re-init the engine if the model actually finished — a
            // Failed/Cancelled outcome must keep serving the honest extractive fallback.
            if (ggufDownloader.state.value == GgufDownloadState.Completed) {
                reinitializeEngine()
            }
        }
    }

    /** Cancels an in-flight download; the partial file is kept so a later [downloadModel] resumes it. */
    public fun cancelDownload(): Unit = ggufDownloader.cancel()

    // The only reset seam today (EngineHost.Reset, wired into dni_shutdown — see that file's kdoc)
    // is process-wide: it also drains any other live FFI/RAG sessions via SessionRegistry. Acceptable
    // here because this fires exactly once, as a direct result of a user-initiated download that the
    // user is actively waiting on — not a background event that could surprise another in-flight tab.
    private suspend fun reinitializeEngine() {
        withContext(Dispatchers.Default) {
            NativeBridge.nativeShutdown()
            val status = NativeBridge.nativeInitialize()
            // status == 0 means EngineHost.Initialize() ran; combined with the Completed check at
            // the call site, BuildRagModel is guaranteed to have found the GGUF this time.
            if (status == 0) {
                _state.update { it.copy(neuralActive = true) }
            }
        }
    }

    public fun ask() {
        val q = QueryInput.sanitize(_state.value.query) ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(streaming = true, answer = "", sources = emptyList(),
                    firstTokenMs = null, totalMs = null, error = null)
            }

            // Retrieval feeds the answer its context — if it fails there is nothing to ground
            // the stream on, so stop here (mirrors the iOS RagViewModel).
            val retrieval = runCatching { search.search(q, "manuals") }
            retrieval.fold(
                onSuccess = { s -> _state.update { it.copy(sources = s) } },
                onFailure = { e ->
                    _state.update {
                        it.copy(streaming = false, error = "Retrieving sources failed: ${e.message}")
                    }
                    return@launch
                },
            )

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
                        error = res.exceptionOrNull()?.let { e -> "Engine answer failed: ${e.message}" }
                            ?: it.error,
                    )
                }
            }
        }
    }
}
