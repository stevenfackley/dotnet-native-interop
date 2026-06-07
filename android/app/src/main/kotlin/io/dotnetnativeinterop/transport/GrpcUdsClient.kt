package io.dotnetnativeinterop.transport

import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import dni.v1.Dni.InferRequest as ProtoInferRequest
import dni.v1.InferenceGrpcKt
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/*
 * Pattern 2 — gRPC over Unix Domain Socket (UDS).
 *
 * Android supports UDS via LocalSocket (API 21+) and the okhttp transport can
 * be configured to use a custom SocketFactory.  grpc-java's okhttp transport
 * is the right choice here because grpc-cronet or grpc-netty are heavier.
 *
 * UDS sharp edges on Android:
 *   - The socket file must be in the app's private directory (cacheDir / filesDir).
 *     System SELinux policy denies access to arbitrary paths.
 *   - grpc-java's okhttp transport does NOT natively support UDS; we use a
 *     custom OkHttpChannelBuilder with an OkHttpClient whose SocketFactory
 *     wraps an Android LocalSocket.  That custom factory is in UdsSocketFactory.
 *   - Delete stale .sock files before passing the path to the server (the
 *     NativeAOT side does this too, but belt-and-suspenders is fine).
 *   - HTTP/2 over plain UDS (no TLS) is explicitly allowed by gRPC when
 *     usePlaintext() is set.
 *
 * NOTE: grpc-java 1.66 ships a "UdsNameResolverProvider" on some platforms but
 * it is not universally available on Android.  The reliable pattern is the
 * custom SocketFactory approach shown here.
 */
public class GrpcUdsClient(
    /** App's cacheDir — socket file will be placed here. */
    private val cacheDir: File,
) : InferenceClient {

    private val socketPath: String
        get() = File(cacheDir, "dni_grpc.sock").absolutePath

    private var channel: ManagedChannel? = null
    private var stub: InferenceGrpcKt.InferenceCoroutineStub? = null

    /**
     * Starts the gRPC server on the UDS path and creates the ManagedChannel.
     * Must be called before [stream].
     */
    public suspend fun start(): Unit = withContext(Dispatchers.IO) {
        // Remove stale socket if present — belt-and-suspenders alongside the
        // .NET side's own cleanup.
        val sockFile = File(socketPath)
        if (sockFile.exists()) sockFile.delete()

        val status = NativeBridge.nativeGrpcStart(socketPath)
        if (status != 0) throw IllegalStateException("nativeGrpcStart failed: $status")

        // OkHttpChannelBuilder is used directly (not via ManagedChannelBuilder.forAddress)
        // so we can inject the UDS SocketFactory at construction time.
        // The host/port are placeholders; the SocketFactory ignores them and
        // connects to [socketPath] instead.
        val builtChannel = OkHttpChannelBuilder
            .forAddress("localhost", 1)   // placeholder — never resolved; UdsSocketFactory overrides
            .usePlaintext()
            .socketFactory(UdsSocketFactory(socketPath))
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build()

        channel = builtChannel
        stub = InferenceGrpcKt.InferenceCoroutineStub(builtChannel)
    }

    /** Stops the gRPC server and shuts down the channel. */
    public suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        channel = null
        stub = null
        NativeBridge.nativeGrpcStop()
    }

    override fun stream(request: InferRequest): Flow<Token> = flow {
        val s = stub ?: throw IllegalStateException("GrpcUdsClient not started — call start() first")

        val protoRequest = ProtoInferRequest.newBuilder()
            .setPrompt(request.prompt)
            .setMaxTokens(request.maxTokens)
            .setTemperature(request.temperature)
            .build()

        s.infer(protoRequest).collect { inferToken ->
            // `final` is a Kotlin hard keyword → backtick the generated accessor.
            emit(Token(inferToken.index, inferToken.text, inferToken.`final`))
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * OkHttp [javax.net.SocketFactory] that creates [android.net.LocalSocket] connections
 * to the given Unix Domain Socket path instead of TCP sockets.
 *
 * grpc-java's okhttp transport calls [createSocket] and then [Socket.connect] with
 * an [InetSocketAddress].  We intercept [createSocket] and return a [LocalSocketWrapper]
 * that ignores the InetSocketAddress and connects to [socketPath] instead.
 */
private class UdsSocketFactory(private val socketPath: String) : javax.net.SocketFactory() {

    override fun createSocket(): java.net.Socket = LocalSocketWrapper(socketPath)

    override fun createSocket(host: String, port: Int): java.net.Socket =
        LocalSocketWrapper(socketPath).also { it.connectToUds() }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket =
        LocalSocketWrapper(socketPath).also { it.connectToUds() }

    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
        LocalSocketWrapper(socketPath).also { it.connectToUds() }

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket =
        LocalSocketWrapper(socketPath).also { it.connectToUds() }
}

/**
 * Wraps [android.net.LocalSocket] behind [java.net.Socket] so OkHttp can use it.
 *
 * OkHttp calls [connect] with an [InetSocketAddress] — we ignore that address and
 * connect to the UDS path instead.  All stream operations are delegated to the
 * underlying LocalSocket's InputStream/OutputStream.
 */
private class LocalSocketWrapper(private val socketPath: String) : java.net.Socket() {

    private val localSocket = android.net.LocalSocket()

    fun connectToUds() {
        localSocket.connect(android.net.LocalSocketAddress(socketPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
    }

    override fun connect(endpoint: java.net.SocketAddress?) { connectToUds() }
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) { connectToUds() }

    override fun getInputStream(): java.io.InputStream = localSocket.inputStream
    override fun getOutputStream(): java.io.OutputStream = localSocket.outputStream

    override fun isConnected(): Boolean = localSocket.isConnected
    override fun isClosed(): Boolean = false  // LocalSocket has no isClosed()
    override fun close() { localSocket.close() }

    override fun setSoTimeout(timeout: Int) { /* LocalSocket has no socket timeout */ }
    override fun setTcpNoDelay(on: Boolean) { /* not applicable to UDS */ }
}
