package io.dotnetnativeinterop.transport

/**
 * Kotlin facade for the JNI shim (dni_jni). Native methods are bound via RegisterNatives in the
 * shim's JNI_OnLoad, so these names are decoupled from the package name.
 *
 * Load order: System.loadLibrary("dni") then System.loadLibrary("dni_jni").
 */
public object NativeBridge {
    // Lifecycle
    public external fun nativeInitialize(): Int
    public external fun nativeShutdown()

    // Pattern 3 — FFI streaming (RAG shares cancel/free + the FfiTokenListener callback)
    public external fun nativeSessionStart(prompt: String, maxTokens: Int, temperature: Float, listener: FfiTokenListener): Long
    public external fun nativeRagSessionStart(query: String, maxTokens: Int, temperature: Float, listener: FfiTokenListener): Long
    public external fun nativeSessionCancel(sessionId: Long): Int
    public external fun nativeSessionFree(sessionId: Long): Int

    // Foreman agent (additive) — mirrors dni_agent_session_start (abi/dni.h). No max_tokens/temperature
    // (the turn loop decides its own length); shares the FfiTokenListener callback ABI and the SAME
    // nativeSessionCancel/nativeSessionFree lifecycle above — no new lifecycle exports. The last normal
    // fragment before the empty is_final=1 marker is a status fragment identified by a leading 0x01
    // control byte (see io.dotnetnativeinterop.agent.parseAgentFragment).
    public external fun nativeAgentSessionStart(query: String, listener: FfiTokenListener): Long

    // Pattern 1 — HTTP loopback
    public external fun nativeHttpStart(): Int
    public external fun nativeHttpStop(): Int

    // Wave B — framed-protobuf loopback transport (the 4th transport). start(flags) returns the bound
    // 127.0.0.1 port (> 0) or a negative DNI_* status; flags bit 0 (flags and 1) requires an
    // ML-KEM-768 / ML-DSA-65 handshake per connection, after which every frame is AES-256-GCM.
    // stop() is idempotent. Mirrors dni_pb_start / dni_pb_stop.
    public external fun nativePbStart(flags: Int): Int
    public external fun nativePbStop()

    // Wave B — in-process tracing. Drains the engine's bounded span ring (512 spans, drop-oldest) as
    // heap UTF-8 JSON (null on failure). Overflow is disclosed in the payload's "dropped" field.
    // Mirrors dni_trace_drain.
    public external fun nativeTraceDrain(): String?

    // In-process logging (the observability trio's 3rd leg). Drains the engine's bounded log ring (256
    // records, drop-oldest) as heap UTF-8 JSON (null on failure); overflow disclosed in "dropped". This is
    // where errors the FFI boundary would otherwise swallow surface. Mirrors dni_log_drain.
    public external fun nativeLogDrain(): String?

    // Pattern 4 — SQLite WAL broker
    public external fun nativeBrokerStart(dbPath: String): Int
    public external fun nativeBrokerStop(): Int

    // Pattern 2 — gRPC over UDS: EXCLUDED transport. The engine exports no dni_grpc_* (gRPC has no
    // NativeAOT mobile runtime pack). The Kotlin client (GrpcUdsClient) and its nativeGrpcStart/Stop
    // declarations were removed together (SP1 hygiene) — see patterns.json's "grpc" entry and
    // jni_bridge.c for the documented reason gRPC stays excluded from the build.

    // Structured feature catalog (string-returning; null on failure)
    public external fun nativeFeaturesJson(): String?
    public external fun nativeFeatureRun(id: String): String?
    public external fun nativeSqliteFeatures(): String?
    public external fun nativeSqliteRun(id: String): String?

    // Introspection + onboard AI
    public external fun nativeEngineStats(): String?
    public external fun nativeSearch(query: String, corpus: String): String?
    // Point the engine at the on-device assets dir (extracted model/vocab/corpus) + enable NNAPI. 0 = ok.
    public external fun nativeSetAssetsDir(path: String): Int
    public external fun nativeSqliteRag(query: String): String?

    // SP0 gate-only probes (not part of the app ABI; see abi/dni_gate_probe.h). Take a caller-supplied
    // path: writable temp for SQLCipher, the pushed GGUF model for llama.
    public external fun nativeSqliteProbe(path: String): String?
    public external fun nativeLlamaProbe(path: String): String?

    // Pattern 3 — Boundary instrumentation (additive). Mirrors dni_ffi_echo / dni_ffi_throw /
    // dni_ffi_stream_start. The JNI shim computes the byte length for echo and passes maxTokens to stream.
    public external fun nativeFfiEcho(text: String): String?
    public external fun nativeFfiThrow(): String?
    public external fun nativeFfiStreamStart(prompt: String, maxTokens: Int, listener: FfiTraceListener): Long
}

/**
 * Per-token callback (Pattern 3). onToken fires on a .NET background thread already attached to the
 * JVM by the shim; implementations MUST NOT block — enqueue and return. Reused for RAG streaming.
 */
public interface FfiTokenListener {
    public fun onToken(index: Int, text: String, isFinal: Boolean)
}

/**
 * Extended per-token callback for Boundary tracing: adds managedThreadId + elapsedUs to the base token
 * callback. Fires on a .NET background thread already attached to the JVM by the shim; MUST NOT block.
 */
public interface FfiTraceListener {
    public fun onTrace(index: Int, text: String, managedThreadId: Long, elapsedUs: Long, isFinal: Boolean)
}
