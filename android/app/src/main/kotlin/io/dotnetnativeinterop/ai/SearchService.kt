package io.dotnetnativeinterop.ai

import io.dotnetnativeinterop.model.SearchResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Shared lenient JSON for the AI payloads. */
internal val aiJson: Json = Json { ignoreUnknownKeys = true }

/** Semantic search over a named engine corpus ("features"|"facts"|"manuals") via dni_search (FFI). */
public class SearchService {
    public suspend fun search(query: String, corpus: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val raw = NativeBridge.nativeSearch(query, corpus) ?: error("nativeSearch returned null")
            if (raw.isBlank()) emptyList() else aiJson.decodeFromString(raw)
        }
}
