import Foundation

/// Builds the request Envelopes the framed-protobuf transport sends, and maps the response payload types
/// back to the shared UI models. Mirrors the Android `PbEnvelopes` + `toModel` helpers so both clients
/// mint the same wire messages.
enum PbEnvelopes {
    /// A fresh client-minted correlation id, echoed by the server in the response (waterfall grouping).
    static func newRequestId() -> String { UUID().uuidString }

    static func features(_ requestId: String) -> Dni_Frame_V1_Envelope {
        var e = Dni_Frame_V1_Envelope()
        e.requestID = requestId
        e.features = Dni_Frame_V1_FeaturesRequest()
        return e
    }

    static func run(_ requestId: String, _ id: String) -> Dni_Frame_V1_Envelope {
        var e = Dni_Frame_V1_Envelope()
        e.requestID = requestId
        var run = Dni_Frame_V1_RunRequest()
        run.id = id
        e.run = run
        return e
    }

    static func ping(_ requestId: String) -> Dni_Frame_V1_Envelope {
        var e = Dni_Frame_V1_Envelope()
        e.requestID = requestId
        e.ping = Dni_Frame_V1_PingRequest()
        return e
    }
}

extension Dni_Frame_V1_FeatureDescriptorPb {
    func toModel() -> FeatureDescriptor {
        FeatureDescriptor(id: id, title: title, version: version, code: code, expected: expected)
    }
}

extension Dni_Frame_V1_FeatureRunPb {
    func toModel() -> FeatureResult {
        FeatureResult(id: id, result: result, elapsedMs: elapsedMs, ok: ok)
    }
}
