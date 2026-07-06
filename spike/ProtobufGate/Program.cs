// Gate: does protobuf-net's runtime (reflection-emit-based, non-source-gen) serializer
// AOT-compile, trim-survive, and round-trip under NativeAOT?
// Purpose: gates a 4th "framed binary RPC" transport without ASP.NET.
// See docs/protobuf-nativeaot-findings.md.
using ProtoBuf;

var request = new InferenceRequest
{
    Prompt = "What torque spec for the manifold bolts?",
    TokenIds = [101, 2054, 11498, 6222, 2005, 1996, 27921, 25635, 102],
    RequestedAt = DateTimeOffset.UtcNow,
};

using var buffer = new MemoryStream();
Serializer.Serialize(buffer, request);
byte[] wire = buffer.ToArray();
Console.WriteLine($"Serialized InferenceRequest: {wire.Length} bytes");

buffer.Position = 0;
var roundTripped = Serializer.Deserialize<InferenceRequest>(buffer);

bool promptOk = roundTripped.Prompt == request.Prompt;
bool tokensOk = roundTripped.TokenIds.SequenceEqual(request.TokenIds);
bool timeOk = roundTripped.RequestedAt == request.RequestedAt;
Console.WriteLine($"Round-trip: prompt={promptOk} tokens={tokensOk} timestamp={timeOk}");

var response = new InferenceResponse
{
    Text = "12 ft-lb in a star pattern.",
    Timings = new LatencyBreakdown { FirstTokenMs = 118.4, TotalMs = 642.9, TokenCount = 9 },
};

using var responseBuffer = new MemoryStream();
Serializer.Serialize(responseBuffer, response);
responseBuffer.Position = 0;
var responseRoundTripped = Serializer.Deserialize<InferenceResponse>(responseBuffer);

bool textOk = responseRoundTripped.Text == response.Text;
bool timingsOk = responseRoundTripped.Timings.FirstTokenMs == response.Timings.FirstTokenMs
    && responseRoundTripped.Timings.TotalMs == response.Timings.TotalMs
    && responseRoundTripped.Timings.TokenCount == response.Timings.TokenCount;
Console.WriteLine($"Round-trip: text={textOk} timings={timingsOk}");

bool allOk = promptOk && tokensOk && timeOk && textOk && timingsOk;
Console.WriteLine(allOk ? "PASS: protobuf-net round-tripped request + response" : "FAIL: round-trip mismatch");

[ProtoContract]
internal sealed class InferenceRequest
{
    [ProtoMember(1)]
    public string Prompt { get; set; } = "";

    [ProtoMember(2)]
    public List<int> TokenIds { get; set; } = [];

    [ProtoMember(3)]
    public DateTimeOffset RequestedAt { get; set; }
}

[ProtoContract]
internal sealed class InferenceResponse
{
    [ProtoMember(1)]
    public string Text { get; set; } = "";

    [ProtoMember(2)]
    public LatencyBreakdown Timings { get; set; } = new();
}

[ProtoContract]
internal sealed class LatencyBreakdown
{
    [ProtoMember(1)]
    public double FirstTokenMs { get; set; }

    [ProtoMember(2)]
    public double TotalMs { get; set; }

    [ProtoMember(3)]
    public int TokenCount { get; set; }
}
