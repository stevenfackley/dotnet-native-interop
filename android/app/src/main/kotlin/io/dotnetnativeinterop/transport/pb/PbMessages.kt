package io.dotnetnativeinterop.transport.pb

import dni.frame.v1.Envelope
import dni.frame.v1.FeatureDescriptorPb
import dni.frame.v1.FeatureRunPb
import dni.frame.v1.FeaturesRequest
import dni.frame.v1.PingRequest
import dni.frame.v1.RagRequest
import dni.frame.v1.RunRequest
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import java.util.UUID

/**
 * Envelope builders + payload mappers for the framed-protobuf transport. Every request carries a
 * client-minted request_id (echoed by the engine) so the tracing waterfall can group a request's
 * engine-side spans with its client-side ones. Requests are built with the generated Java lite
 * builders (unambiguous, no Kotlin-keyword friction on the `run` oneof field).
 */
internal object PbEnvelopes {
    /** A fresh correlation id for one request/response (or one rag stream). */
    fun newRequestId(): String = UUID.randomUUID().toString()

    fun features(requestId: String): Envelope = Envelope.newBuilder()
        .setRequestId(requestId)
        .setFeatures(FeaturesRequest.getDefaultInstance())
        .build()

    /** One feature/command id. bench-* / gclab ids ride this same Run path (the engine treats them alike). */
    fun run(requestId: String, id: String): Envelope = Envelope.newBuilder()
        .setRequestId(requestId)
        .setRun(RunRequest.newBuilder().setId(id))
        .build()

    fun ping(requestId: String): Envelope = Envelope.newBuilder()
        .setRequestId(requestId)
        .setPing(PingRequest.getDefaultInstance())
        .build()

    fun rag(requestId: String, query: String): Envelope = Envelope.newBuilder()
        .setRequestId(requestId)
        .setRag(RagRequest.newBuilder().setQuery(query))
        .build()
}

internal fun FeatureDescriptorPb.toModel(): FeatureDescriptor =
    FeatureDescriptor(id = id, title = title, version = version, code = code, expected = expected)

internal fun FeatureRunPb.toModel(): FeatureResult =
    FeatureResult(id = id, result = result, elapsedMs = elapsedMs, ok = ok)

/** Raised when the engine returns a typed ErrorFrame instead of the expected response (surfaced, not swallowed). */
internal class PbErrorFrameException(val code: Int, override val message: String) : Exception(message)
