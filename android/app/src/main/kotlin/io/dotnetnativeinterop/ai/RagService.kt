package io.dotnetnativeinterop.ai

import kotlinx.coroutines.flow.Flow

/** Streams a grounded answer fragment-by-fragment; each emission is appended to the running answer. */
public interface RagService {
    public fun answer(query: String): Flow<String>
}
