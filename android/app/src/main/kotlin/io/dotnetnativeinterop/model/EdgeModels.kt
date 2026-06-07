package io.dotnetnativeinterop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared lenient JSON for EVS payloads (index Metadata + fixtures). */
internal val evsJson: Json = Json { ignoreUnknownKeys = true }

/** The Metadata JSON column in edge-index.db: {"error_codes":[…],"tools_required":[…]}. */
@Serializable
public data class EdgeMetadata(
    @SerialName("error_codes") val errorCodes: List<String> = emptyList(),
    @SerialName("tools_required") val toolsRequired: List<String> = emptyList(),
)

/** One indexed chunk from edge-index.db (embedding is the L2-normalized 384-d vector). */
public data class EdgeChunk(
    val chunkId: String,
    val documentId: String,
    val sectionTitle: String,
    val contentText: String,
    val errorCodes: List<String>,
    val toolsRequired: List<String>,
    val embedding: FloatArray,
)

/** A ranked search hit. */
public data class EdgeHit(val chunk: EdgeChunk, val score: Float)
