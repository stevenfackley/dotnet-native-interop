package io.dotnetnativeinterop.transport

import android.content.Context
import io.dotnetnativeinterop.data.BrokerDatabase
import io.dotnetnativeinterop.data.RequestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Pattern 4 — SQLite WAL Broker.
 *
 * The UI writes a request row; a .NET background loop observes the requests table,
 * runs inference, and appends rows to response_tokens.  This client observes
 * response_tokens via Room's Flow and emits tokens as they appear.
 *
 * Tradeoffs (visible in the Pattern Info panel):
 *   - Tokens appear at the polling granularity of the .NET broker (~50 ms), not
 *     the instant they are produced — this is the highest-latency transport.
 *   - Every token is a row INSERT — write amplification is real; unsuitable for
 *     hot streaming, excellent for durable job queues / offline outboxes.
 *   - Room's Flow re-emits on every table write; we track [lastSeenIdx] and
 *     push only new tokens into a Channel to avoid re-emitting duplicates.
 */
public class SqliteClient(
    private val context: Context,
) : InferenceClient {

    private val dbPath: String
        get() = File(context.cacheDir, "dni_broker.db").absolutePath

    private var db: BrokerDatabase? = null

    /** Starts the .NET broker and opens the Room database. */
    public suspend fun start(): Unit = withContext(Dispatchers.IO) {
        val status = NativeBridge.nativeBrokerStart(dbPath)
        if (status != 0) throw IllegalStateException("nativeBrokerStart failed: $status")
        db = BrokerDatabase.open(context, dbPath)
    }

    /** Stops the broker and closes the database. */
    public suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        db?.close()
        db = null
        NativeBridge.nativeBrokerStop()
    }

    override fun stream(request: InferRequest): Flow<Token> = flow {
        val database = db
            ?: throw IllegalStateException("SqliteClient not started — call start() first")

        val requestId = withContext(Dispatchers.IO) {
            database.requestDao().insert(
                RequestEntity(
                    prompt = request.prompt,
                    maxTokens = request.maxTokens,
                    temperature = request.temperature,
                    createdUtc = Instant.now().toString(),
                )
            )
        }

        val tokenDao = database.responseTokenDao()

        // Bridge Room's reactive Flow → a regular Channel we can iterate.
        // Room re-emits ALL matching rows on each table change; we de-duplicate
        // by tracking the highest index we have already forwarded.
        val channel = Channel<Token>(capacity = 64)
        var lastSeenIdx = -1

        // Launch the Room observer in a sibling coroutine within this flow's scope.
        // 'this' here is the FlowCollector scope — we need the coroutineScope from flow{}.
        // Use a coroutineScope block to keep things structured.
        kotlinx.coroutines.coroutineScope {
            val observerJob = launch(Dispatchers.IO) {
                tokenDao.observeTokensAfter(requestId, afterIdx = -1)
                    .collect { rows ->
                        var sawFinal = false
                        for (row in rows) {
                            if (row.idx > lastSeenIdx) {
                                lastSeenIdx = row.idx
                                val token = Token(row.idx, row.text, row.isFinal != 0)
                                channel.send(token)
                                if (row.isFinal != 0) {
                                    sawFinal = true
                                    break
                                }
                            }
                        }
                        if (sawFinal) channel.close()
                    }
            }

            // Drain the channel, emitting to the outer Flow collector.
            // The channel is closed by the observer when the final token arrives,
            // at which point the for-loop exits naturally.
            for (token in channel) {
                emit(token)
            }

            observerJob.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
