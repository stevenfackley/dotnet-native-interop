import XCTest
@testable import DotnetNativeInteropUnified

/// Pins the client-side half of `dni_agent_session_start`'s completion contract (abi/dni.h): detection
/// is on the leading 0x01 control byte, NEVER on the readable "dni.agent.status" tag text, and the three
/// stop reasons must never be confused with one another. Mirrors Android's `AgentModelsTest.kt` 1:1,
/// plus the lenient `TraceDrain` decode the trace layer promises.
final class AgentModelsTests: XCTestCase {

    // Built from the SAME agentStatusControlByte/agentStatusTag production constants (not a hand-rolled
    // duplicate) so this test can never drift from what parseAgentFragment actually detects.
    private func statusFragment(stopReason: String, toolSteps: Int, backend: String) -> String {
        "\(agentStatusControlByte)\(agentStatusTag)"
            + "{\"stopReason\":\"\(stopReason)\",\"toolSteps\":\(toolSteps),\"backend\":\"\(backend)\"}"
    }

    /// Unwraps a fragment expected to parse as the terminal status; fails the test otherwise.
    private func parsedStatus(_ text: String) throws -> AgentTurnStatus {
        let fragment = try parseAgentFragment(text)
        guard case .status(let status) = fragment else {
            XCTFail("expected .status, got \(fragment)")
            throw NSError(domain: "AgentModelsTests", code: 1)
        }
        return status
    }

    // MARK: - parseAgentFragment

    func testPlainTextFragmentIsAnswer() throws {
        let fragment = try parseAgentFragment("the compressor is overheating; check the condenser fan.")
        guard case .answer(let text) = fragment else { return XCTFail("expected .answer") }
        XCTAssertEqual(text, "the compressor is overheating; check the condenser fan.")
    }

    func testFragmentContainingTheReadableTagButNotTheControlByteIsStillAnswer() throws {
        // An on-device LLM could legitimately stream this literal text while answering a question ABOUT
        // the marker — dni.h is explicit that the tag text alone must never be treated as the signal.
        let fragment = try parseAgentFragment("the status marker is called dni.agent.status internally")
        guard case .answer = fragment else { return XCTFail("expected .answer for tag text without 0x01") }
    }

    func testControlByteFragmentParsesAnsweredStatus() throws {
        let status = try parsedStatus(statusFragment(
            stopReason: "Answered", toolSteps: 1, backend: "scripted routing — no on-device LLM present"))
        XCTAssertEqual(status.stopReason, .Answered)
        XCTAssertEqual(status.toolSteps, 1)
        XCTAssertEqual(status.backend, "scripted routing — no on-device LLM present")
    }

    /// Ground-truth regression: the LIVE engine writes the marker with a REPEATED 0x01 prefix
    /// (0x01 0x01 dni.agent.status…), captured from a real turn on the simulator. A fixed-length skip
    /// lands a byte short and yields invalid JSON; the tag-anchored parse must still decode it.
    func testDoubledControlBytePrefixStillParses() throws {
        let doubled = "\(agentStatusControlByte)\(agentStatusControlByte)\(agentStatusTag)"
            + "{\"stopReason\":\"Answered\",\"toolSteps\":0,\"backend\":\"scripted routing — no on-device LLM present\"}"
        let status = try parsedStatus(doubled)
        XCTAssertEqual(status.stopReason, .Answered)
        XCTAssertEqual(status.toolSteps, 0)
        XCTAssertEqual(status.backend, "scripted routing — no on-device LLM present")
    }

    /// A leading 0x01 with NO readable tag is a malformed marker → an honest throw (the service maps it to
    /// an Error status), never a mis-parse.
    func testControlByteWithoutTagThrows() {
        XCTAssertThrowsError(try parseAgentFragment("\(agentStatusControlByte){\"stopReason\":\"Answered\"}"))
    }

    func testControlByteFragmentParsesStepCapReachedStatus() throws {
        let status = try parsedStatus(statusFragment(
            stopReason: "StepCapReached", toolSteps: 5, backend: "scripted (harness)"))
        XCTAssertEqual(status.stopReason, .StepCapReached)
        XCTAssertEqual(status.toolSteps, 5)
    }

    func testControlByteFragmentParsesErrorStatus() throws {
        let status = try parsedStatus(statusFragment(
            stopReason: "Error", toolSteps: 0, backend: "on-device LLM (Llama-3.2-1B, grammar-constrained)"))
        XCTAssertEqual(status.stopReason, .Error)
        XCTAssertEqual(status.backend, "on-device LLM (Llama-3.2-1B, grammar-constrained)")
    }

    func testMalformedStatusFragmentThrows() {
        // A 0x01-prefixed fragment with a garbage JSON payload throws — this is the exact failure the
        // FFIAgentService callback guard contains (mapping it onto an honest Error status instead of
        // leaving the turn stuck at streaming forever). Pinning the throw documents why that guard exists.
        XCTAssertThrowsError(
            try parseAgentFragment("\(agentStatusControlByte)\(agentStatusTag){not valid json"))
    }

    func testTheThreeStopReasonsAreMutuallyDistinguishable() throws {
        let answered = try parsedStatus(statusFragment(stopReason: "Answered", toolSteps: 1, backend: "x")).stopReason
        let capped = try parsedStatus(statusFragment(stopReason: "StepCapReached", toolSteps: 5, backend: "x")).stopReason
        let errored = try parsedStatus(statusFragment(stopReason: "Error", toolSteps: 0, backend: "x")).stopReason
        XCTAssertTrue(answered != capped && capped != errored && answered != errored)
    }

    // MARK: - turnSpans(from:expectedToolSteps:)

    func testTurnSpansPicksLatestTurnPlusLastExpectedToolsSortedByStart() {
        let drain = TraceDrain(nowUs: 100, dropped: 0, capacity: 512, spans: [
            TraceSpan(name: "rag.retrieve", startUs: 0, durUs: 5),              // unrelated prior-screen span
            TraceSpan(name: "agent.turn", startUs: 0.5, durUs: 20),             // PRIOR turn — must lose to the latest
            TraceSpan(name: "agent.tool.run_feature", startUs: 0.7, durUs: 2),  // prior turn's tool — dropped by last-N
            TraceSpan(name: "agent.turn", startUs: 1, durUs: 40),
            TraceSpan(name: "agent.tool.search_manuals", startUs: 2, durUs: 10),
            TraceSpan(name: "agent.tool.engine_stats", startUs: 20, durUs: 3),
        ])
        let spans = turnSpans(from: drain, expectedToolSteps: 2)
        XCTAssertEqual(spans.map(\.name), ["agent.turn", "agent.tool.search_manuals", "agent.tool.engine_stats"])
        XCTAssertEqual(spans.first?.startUs, 1) // the LATEST agent.turn (max startUs), not the prior turn's
    }

    func testTurnSpansWithZeroToolStepsReturnsOnlyTheTurnSpan() {
        let drain = TraceDrain(spans: [TraceSpan(name: "agent.turn", startUs: 0, durUs: 10)])
        let spans = turnSpans(from: drain, expectedToolSteps: 0)
        XCTAssertEqual(spans.map(\.name), ["agent.turn"])
    }

    func testTurnSpansNilDrainReturnsEmpty() {
        XCTAssertTrue(turnSpans(from: nil, expectedToolSteps: 3).isEmpty)
    }

    func testTurnSpansPreservesToolArgsAndToolResultTags() {
        // Proves the tag-carrying fields survive turnSpans' filter/sort — the strip reads them off the
        // SAME spans this function slices, not a separately-fetched payload.
        let drain = TraceDrain(nowUs: 100, dropped: 0, capacity: 512, spans: [
            TraceSpan(name: "agent.turn", startUs: 1, durUs: 40),
            TraceSpan(name: "agent.tool.search_manuals", startUs: 2, durUs: 10,
                      toolArgs: "{\"query\":\"E3\"}", toolResult: "{\"snippets\":[\"a\",\"b\",\"c\"]}"),
        ])
        let spans = turnSpans(from: drain, expectedToolSteps: 1)
        guard let toolSpan = spans.first(where: { $0.name == "agent.tool.search_manuals" }) else {
            return XCTFail("expected the tool span to survive the slice")
        }
        XCTAssertEqual(toolSpan.toolArgs, "{\"query\":\"E3\"}")
        XCTAssertEqual(toolSpan.toolResult, "{\"snippets\":[\"a\",\"b\",\"c\"]}")
    }

    // MARK: - formatToolCall

    func testFormatToolCallRendersRealArgsAndResult() {
        let span = TraceSpan(name: "agent.tool.run_feature", startUs: 0, durUs: 1,
                             toolArgs: "{\"id\":\"ping\"}", toolResult: "{\"ok\":true,\"result\":\"pong\"}")
        XCTAssertEqual(formatToolCall(span), "run_feature({\"id\":\"ping\"}) -> {\"ok\":true,\"result\":\"pong\"}")
    }

    func testFormatToolCallShowsTheTruncationMarkerWhenTheEngineTruncated() {
        // The engine (ForemanAgent's bound) appends this exact suffix — the client just renders it,
        // never hides that a result was clamped.
        let span = TraceSpan(name: "agent.tool.search_manuals", startUs: 0, durUs: 1,
                             toolArgs: "{\"query\":\"E3\"}",
                             toolResult: String(repeating: "z", count: 512) + "…(truncated)")
        XCTAssertTrue(formatToolCall(span).contains("…(truncated)"))
    }

    func testFormatToolCallShowsAFailedToolsJsonErrorVerbatim() {
        // The engine tags a failed/unknown tool call's JSON error as the result — never blank; the strip
        // must render the failure honestly rather than hide it.
        let span = TraceSpan(name: "agent.tool.run_feature", startUs: 0, durUs: 1,
                             toolArgs: "{\"id\":\"nope\"}",
                             toolResult: "{\"error\":\"unknown feature id: nope\"}")
        XCTAssertEqual(formatToolCall(span), "run_feature({\"id\":\"nope\"}) -> {\"error\":\"unknown feature id: nope\"}")
    }

    func testFormatToolCallFallsBackHonestlyWhenTagsAreAbsent() {
        // A span from an engine build that predates this feature carries nil tags — the strip must never
        // render a blank/misleading call, and must never crash.
        let span = TraceSpan(name: "agent.tool.engine_stats", startUs: 0, durUs: 1)
        let formatted = formatToolCall(span)
        XCTAssertTrue(formatted.contains("not captured"))
        XCTAssertTrue(formatted.hasPrefix("engine_stats("))
    }

    // MARK: - TraceDrain lenient decode (the trace layer's contract)

    func testTraceDrainDecodesLenientlyWhenKeysAreMissing() throws {
        // The engine may evolve the drain payload; a missing key must degrade to its default
        // (0 / 0 / 512 / empty), not fail the whole drain.
        let drain = try JSONDecoder().decode(TraceDrain.self, from: Data("{}".utf8))
        XCTAssertEqual(drain.nowUs, 0)
        XCTAssertEqual(drain.dropped, 0)
        XCTAssertEqual(drain.capacity, 512)
        XCTAssertTrue(drain.spans.isEmpty)
    }

    func testTraceDrainDecodesTheRealPayloadShapeAndToleratesUnknownKeys() throws {
        // Matches dni_trace_drain's documented shape (abi/dni.h), plus an unknown key a future engine
        // build might add — it must be ignored, not fatal. Spans carry no "id" on the wire (minted
        // client-side for SwiftUI identity only).
        let json = """
        {"nowUs":123.5,"dropped":2,"capacity":512,"futureKey":true,
         "spans":[{"name":"agent.tool.engine_stats","startUs":1.0,"durUs":2.5,
                   "requestId":null,"status":"ok","toolArgs":"{}","toolResult":"{\\"ok\\":true}"}]}
        """
        let drain = try JSONDecoder().decode(TraceDrain.self, from: Data(json.utf8))
        XCTAssertEqual(drain.nowUs, 123.5, accuracy: 1e-9)
        XCTAssertEqual(drain.dropped, 2)
        XCTAssertEqual(drain.spans.count, 1)
        XCTAssertEqual(drain.spans[0].name, "agent.tool.engine_stats")
        XCTAssertNil(drain.spans[0].requestId)
        XCTAssertEqual(drain.spans[0].status, "ok")
        XCTAssertEqual(drain.spans[0].toolResult, "{\"ok\":true}")
    }
}
