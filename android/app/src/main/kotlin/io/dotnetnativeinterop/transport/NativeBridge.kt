package io.dotnetnativeinterop.transport

/**
 * Kotlin facade for the JNI shim (dni_jni.so).
 *
 * Load order matters:
 *   1. "dni"     — the NativeAOT library; all C symbols must be resident first.
 *   2. "dni_jni" — this shim that calls those symbols.
 *
 * Both loads are performed in [DotnetNativeInteropApp] (or MainActivity) so that any
 * class referencing this object is guaranteed to find the libraries already loaded.
 */
public object NativeBridge {

    // --- Lifecycle ----------------------------------------------------------

    /** Calls dni_initialize(). Returns 0 on success, negative NativeStatus. */
    public external fun nativeInitialize(): Int

    /** Calls dni_shutdown(). */
    public external fun nativeShutdown()

    // --- Pattern 3: FFI -----------------------------------------------------

    /**
     * Starts an inference session.  Tokens are delivered by calling
     * [FfiTokenListener.onToken] on a .NET background thread (already attached
     * to the JVM by the C shim).  Returns session id (> 0) or negative status.
     */
    public external fun nativeSessionStart(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        listener: FfiTokenListener,
    ): Long

    /** Returns 0 on success, negative status. */
    public external fun nativeSessionCancel(sessionId: Long): Int

    /** Returns 0 on success, negative status. */
    public external fun nativeSessionFree(sessionId: Long): Int

    // --- Pattern 1: HTTP loopback -------------------------------------------

    /** Returns the bound port (> 0) or negative status. */
    public external fun nativeHttpStart(): Int

    /** Returns 0 on success, negative status. */
    public external fun nativeHttpStop(): Int

    // --- Pattern 2: gRPC over UDS -------------------------------------------

    /** Returns 0 on success, negative status. */
    public external fun nativeGrpcStart(socketPath: String): Int

    /** Returns 0 on success, negative status. */
    public external fun nativeGrpcStop(): Int

    // --- Pattern 4: SQLite WAL broker ---------------------------------------

    /** Returns 0 on success, negative status. */
    public external fun nativeBrokerStart(dbPath: String): Int

    /** Returns 0 on success, negative status. */
    public external fun nativeBrokerStop(): Int
}

/**
 * Callback interface matching the C shim's CallVoidMethod signature:
 *   void onToken(int index, String text, boolean isFinal)
 *
 * Called on a .NET background thread (already attached to the JVM).
 * Implementations MUST NOT block; enqueue to a channel and return immediately.
 */
public interface FfiTokenListener {
    public fun onToken(index: Int, text: String, isFinal: Boolean)
}
