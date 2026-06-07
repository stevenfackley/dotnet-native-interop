package io.dotnetnativeinterop.evs

import android.database.sqlite.SQLiteDatabase
import io.dotnetnativeinterop.model.EdgeChunk
import io.dotnetnativeinterop.model.EdgeMetadata
import io.dotnetnativeinterop.model.evsJson
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the .NET-published edge-index.db (table Chunks; Embedding = little-endian float32[384] BLOB). */
public object EdgeIndex {
    public fun load(dbPath: String): List<EdgeChunk> {
        val chunks = ArrayList<EdgeChunk>()
        SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT ChunkId, DocumentId, SectionTitle, ContentText, Embedding, Metadata FROM Chunks",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val blob = c.getBlob(4)
                    val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val emb = FloatArray(fb.remaining()).also { fb.get(it) }
                    val meta = evsJson.decodeFromString<EdgeMetadata>(c.getString(5))
                    chunks.add(
                        EdgeChunk(
                            chunkId = c.getString(0),
                            documentId = c.getString(1),
                            sectionTitle = c.getString(2),
                            contentText = c.getString(3),
                            errorCodes = meta.errorCodes,
                            toolsRequired = meta.toolsRequired,
                            embedding = emb,
                        ),
                    )
                }
            }
        }
        return chunks
    }
}
