package io.dotnetnativeinterop.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

/*
 * Room schema mirroring the SQLite WAL broker tables defined in INTEROP_CONTRACT.md.
 *
 * The .NET broker process owns the 'requests' lifecycle (pending → running → done).
 * Kotlin only inserts into 'requests' and reads from 'response_tokens'.
 *
 * WAL mode: Room enables WAL by default since Room 2.0 — no explicit pragma needed,
 * but we set it explicitly for clarity.  WAL allows the UI to read response_tokens
 * concurrently while .NET is writing, without blocking.
 */

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "requests")
public data class RequestEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "prompt")
    val prompt: String,

    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int = 256,

    @ColumnInfo(name = "temperature")
    val temperature: Float = 0.8f,

    /** pending | running | done | error | canceled */
    @ColumnInfo(name = "status")
    val status: String = "pending",

    @ColumnInfo(name = "created_utc")
    val createdUtc: String,
)

@Entity(
    tableName = "response_tokens",
    primaryKeys = ["request_id", "idx"],
)
public data class ResponseTokenEntity(
    @ColumnInfo(name = "request_id")
    val requestId: Long,

    @ColumnInfo(name = "idx")
    val idx: Int,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "is_final")
    val isFinal: Int,  // 0 | 1 — matches .NET INTEGER column

    @ColumnInfo(name = "created_utc")
    val createdUtc: String,
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
public interface RequestDao {
    @Insert
    public suspend fun insert(request: RequestEntity): Long
}

@Dao
public interface ResponseTokenDao {
    /**
     * Returns all tokens for [requestId] with idx > [afterIdx], ordered by idx.
     * Room converts this query into a Flow that re-emits whenever the table changes.
     */
    @Query(
        "SELECT * FROM response_tokens WHERE request_id = :requestId AND idx > :afterIdx ORDER BY idx ASC"
    )
    public fun observeTokensAfter(requestId: Long, afterIdx: Int): Flow<List<ResponseTokenEntity>>

    /** One-shot query to check if the final token has arrived. */
    @Query("SELECT COUNT(*) FROM response_tokens WHERE request_id = :requestId AND is_final = 1")
    public suspend fun finalTokenCount(requestId: Long): Int
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [RequestEntity::class, ResponseTokenEntity::class],
    version = 1,
    exportSchema = false,
)
public abstract class BrokerDatabase : RoomDatabase() {
    public abstract fun requestDao(): RequestDao
    public abstract fun responseTokenDao(): ResponseTokenDao

    public companion object {
        /**
         * Opens (or creates) the WAL broker database at [dbPath].
         * The path is supplied by the SqliteClient so it matches the path
         * passed to [NativeBridge.nativeBrokerStart].
         */
        public fun open(context: Context, dbPath: String): BrokerDatabase {
            // Room.databaseBuilder interprets the name argument as a file name
            // relative to getDatabasePath() unless it contains a path separator.
            // Pass the File directly (supported since Room 2.1) so that the .db
            // lands at the exact same path passed to nativeBrokerStart.
            val dbFile = java.io.File(dbPath)
            return Room.databaseBuilder(context.applicationContext, BrokerDatabase::class.java, dbFile.absolutePath)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
