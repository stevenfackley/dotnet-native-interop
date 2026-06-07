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

    // Pattern 1 — HTTP loopback
    public external fun nativeHttpStart(): Int
    public external fun nativeHttpStop(): Int

    // Pattern 4 — SQLite WAL broker
    public external fun nativeBrokerStart(dbPath: String): Int
    public external fun nativeBrokerStop(): Int

    // Pattern 2 — gRPC over UDS: EXCLUDED transport. The engine exports no dni_grpc_* (gRPC has no
    // NativeAOT mobile runtime pack), so these are deliberately NOT registered in the JNI table — they
    // exist only so the legacy GrpcUdsClient still compiles. Invoking them throws UnsatisfiedLinkError
    // (no regression: the pre-rename shim referenced a non-existent dni_grpc_start, so gRPC never worked
    // on Android). TODO(SP1): remove GrpcUdsClient + these two declarations together.
    public external fun nativeGrpcStart(socketPath: String): Int
    public external fun nativeGrpcStop(): Int

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
}

/**
 * Per-token callback (Pattern 3). onToken fires on a .NET background thread already attached to the
 * JVM by the shim; implementations MUST NOT block — enqueue and return. Reused for RAG streaming.
 */
public interface FfiTokenListener {
    public fun onToken(index: Int, text: String, isFinal: Boolean)
}
