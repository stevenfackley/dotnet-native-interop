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
        val requestDao = database.requestDao()

        // Bridge Room's reactive Flow → a regular Channel we can iterate.
        // Room re-emits ALL matching rows on each table change; we de-duplicate
        // by tracking the highest index we have already forwarded.
        val channel = Channel<Token>(capacity = 64)
        var lastSeenIdx = -1

        // Launch the Room observers in sibling coroutines within this flow's scope.
        // 'this' here is the FlowCollector scope — we need the coroutineScope from flow{}.
        // Use a coroutineScope block to keep things structured.
        kotlinx.coroutines.coroutineScope {
            val observerJob = launch(Dispatchers.IO) {
                try {
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
                } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    // The status observer closed the channel first (a broker fault) — stop forwarding.
                }
            }

            // The token observer only ends the stream on an is_final row. A broker FAULT sets
            // requests.status='error' and inserts NO final token (SqliteBroker.ProcessNextPendingAsync's
            // catch) — so without also watching the status, the stream would hang forever on any engine
            // fault, showing an endless spinner with no error. The broker sets that status precisely "so
            // the host does not poll forever"; honor it by closing the stream with an error. ('done' needs
            // no handling here: it always follows the is_final row the token observer already acted on.)
            val statusJob = launch(Dispatchers.IO) {
                requestDao.observeStatus(requestId).collect { status ->
                    when (status) {
                        "error" -> channel.close(IllegalStateException("SQLite broker reported an engine error"))
                        "canceled" -> channel.close()
                    }
                }
            }

            // Drain the channel, emitting to the outer Flow collector. Closed cleanly by the token
            // observer on the final token (or by the status observer on a terminal broker state), at which
            // point the for-loop drains any buffered tokens and then exits (or rethrows the close cause).
            try {
                for (token in channel) {
                    emit(token)
                }
            } finally {
                observerJob.cancel()
                statusJob.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)
}
