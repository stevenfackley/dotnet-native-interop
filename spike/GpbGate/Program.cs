// Gate: does Google.Protobuf with REAL protoc codegen (Grpc.Tools build-time <Protobuf> item,
// messages only) publish + run under NativeAOT? This is the fallback named by the protobuf-net
// gate's verdict (docs/protobuf-nativeaot-findings.md) — same message shape that killed it there.
// See docs/gpb-nativeaot-findings.md.
using Google.Protobuf;
using GpbGate.Proto;

var request = new InferenceRequest
{
    Prompt = "What torque spec for the manifold bolts?",
    MaxTokens = 64,
    Temperature = 0.7f,
};
request.TokenIds.AddRange([101, 2054, 11498, 6222, 2005, 1996, 27921, 25635, 102]);

byte[] wire = request.ToByteArray();
Console.WriteLine($"Serialized InferenceRequest: {wire.Length} bytes");

InferenceRequest requestBack = InferenceRequest.Parser.ParseFrom(wire);
bool requestOk = requestBack.Equals(request); // protoc-generated value equality
Console.WriteLine($"Round-trip request: equal={requestOk} tokens={requestBack.TokenIds.Count}");

var response = new InferenceResponse
{
    Text = "12 ft-lb in a star pattern.",
    Timings = new LatencyBreakdown { FirstTokenMs = 118.4, TotalMs = 642.9, TokenCount = 9 },
};

byte[] responseWire = response.ToByteArray();
InferenceResponse responseBack = InferenceResponse.Parser.ParseFrom(responseWire);
bool responseOk = responseBack.Equals(response);
Console.WriteLine($"Round-trip response: equal={responseOk} timings.total={responseBack.Timings.TotalMs}");

// JSON formatting exercises a second, reflection-descriptor-driven code path worth probing.
string json;
bool jsonOk;
try
{
    json = JsonFormatter.Default.Format(response);
    jsonOk = json.Contains("12 ft-lb", StringComparison.Ordinal);
    Console.WriteLine($"JsonFormatter: {json}");
}
catch (Exception ex)
{
    jsonOk = false;
    Console.WriteLine($"JsonFormatter: EXCEPTION {ex.GetType().FullName}: {ex.Message}");
}

bool allOk = requestOk && responseOk;
Console.WriteLine(allOk
    ? $"PASS: Google.Protobuf codegen round-tripped request + response (JsonFormatter {(jsonOk ? "also OK" : "FAILED — binary-only viability")})"
    : "FAIL: round-trip mismatch");
