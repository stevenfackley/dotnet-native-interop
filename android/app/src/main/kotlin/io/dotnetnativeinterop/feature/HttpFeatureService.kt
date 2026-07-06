package io.dotnetnativeinterop.feature

import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

/** Catalog over the in-process loopback HTTP server (dni_http_start -> GET /features, /feature/run/{id}). */
public class HttpFeatureService(
    private val client: OkHttpClient = OkHttpClient(),
) : FeatureCatalogService {

    private val startGate = Mutex()
    @Volatile private var port: Int = 0

    private suspend fun ensurePort(): Int {
        if (port > 0) return port
        startGate.withLock {
            if (port <= 0) {
                val p = NativeBridge.nativeHttpStart()
                require(p > 0) { "nativeHttpStart failed: $p" }
                port = p
            }
        }
        return port
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val p = ensurePort()
        // Mint a per-request correlation id and pass it in the X-Dni-Request-Id header the raw-HTTP server
        // reads (Exports.HttpRaw) and tags onto its engine spans — so HTTP requests group in the Trace
        // waterfall's request picker exactly like the framed-protobuf transport's request_id does.
        val req = Request.Builder()
            .url("http://127.0.0.1:$p$path")
            .header("X-Dni-Request-Id", UUID.randomUUID().toString())
            .build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "GET $path -> HTTP ${resp.code}" }
            resp.body?.string() ?: error("empty body for $path")
        }
    }

    override suspend fun descriptors(): List<FeatureDescriptor> =
        catalogJson.decodeFromString(get("/features"))

    override suspend fun run(id: String): FeatureResult =
        catalogJson.decodeFromString(get("/feature/run/$id"))
}
