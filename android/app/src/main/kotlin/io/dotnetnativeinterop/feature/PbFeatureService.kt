package io.dotnetnativeinterop.feature

import dni.frame.v1.Envelope
import io.dotnetnativeinterop.model.FeatureDescriptor
import io.dotnetnativeinterop.model.FeatureResult
import io.dotnetnativeinterop.transport.pb.PbEnvelopes
import io.dotnetnativeinterop.transport.pb.PbErrorFrameException
import io.dotnetnativeinterop.transport.pb.PbTransport
import io.dotnetnativeinterop.transport.pb.toModel

/**
 * Catalog over the framed-protobuf loopback transport (the 4th transport) — a [FeatureCatalogService]
 * parallel to the Ffi/Http/Sqlite services, so Compare/Latency/Features pick it up automatically through
 * the existing seam ([defaultServiceFor]). Structured binary RPC (length-prefixed Google.Protobuf frames)
 * with no ASP.NET/gRPC runtime; when the transport is in PQ mode every frame is AES-256-GCM encrypted,
 * transparently to this service.
 *
 * Typed ErrorFrame responses are surfaced as a [PbErrorFrameException] (never swallowed into a silent
 * empty state), matching the repo's honesty rules.
 */
public class PbFeatureService : FeatureCatalogService {

    override suspend fun descriptors(): List<FeatureDescriptor> = PbTransport.withConnection { connection ->
        val response = connection.request(PbEnvelopes.features(PbEnvelopes.newRequestId()))
        when (response.bodyCase) {
            Envelope.BodyCase.FEATURES_RESPONSE -> response.featuresResponse.featuresList.map { it.toModel() }
            Envelope.BodyCase.ERROR -> throw PbErrorFrameException(response.error.code, response.error.message)
            else -> error("framed-protobuf: expected FEATURES_RESPONSE but got ${response.bodyCase}")
        }
    }

    override suspend fun run(id: String): FeatureResult = PbTransport.withConnection { connection ->
        val response = connection.request(PbEnvelopes.run(PbEnvelopes.newRequestId(), id))
        when (response.bodyCase) {
            Envelope.BodyCase.RUN_RESPONSE -> response.runResponse.run.toModel()
            Envelope.BodyCase.ERROR -> throw PbErrorFrameException(response.error.code, response.error.message)
            else -> error("framed-protobuf: expected RUN_RESPONSE for '$id' but got ${response.bodyCase}")
        }
    }
}
