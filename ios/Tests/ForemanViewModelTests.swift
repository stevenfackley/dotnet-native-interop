import XCTest
@testable import DotnetNativeInteropUnified

/// Injectable stand-in for `NativeAgentTraceReader` — no FFI. Builds the drain by DECODING JSON (the
/// same lenient path the real reader takes) rather than hand-constructing spans, so these tests also
/// agree with production on the wire shape.
private struct FakeTraceReader: AgentTraceReader {
    let json: String?
    func drain() -> TraceDrain? {
        guard let json else { return nil }
        return try? JSONDecoder().decode(TraceDrain.self, from: Data(json.utf8))
    }
}

/// One agent.turn span + two agent.tool.* spans, deliberately out of startUs order to prove
/// `turnSpans(from:expectedToolSteps:)` re-sorts for the strip.
private let drainJSON = #"""
{"nowUs":2000,"dropped":0,"capacity":512,"spans":[
  {"name":"agent.tool.engine_stats","startUs":400,"durUs":120,"toolArgs":"{}","toolResult":"{\"ops\":3}"},
  {"name":"agent.turn","startUs":100,"durUs":900},
  {"name":"agent.tool.search_manuals","startUs":150,"durUs":200,"toolArgs":"{\"query\":\"oil filter\"}","toolResult":"[2 hits]"}
]}
"""#

@MainActor
final class ForemanViewModelTests: XCTestCase {

    private func status(_ reason: AgentStopReason, toolSteps: Int = 2, backend: String) -> AgentFragment {
        .status(AgentTurnStatus(stopReason: reason, toolSteps: toolSteps, backend: backend))
    }

    func testAnsweredTurnAccumulatesTextStatusAndSpans() async {
        let vm = ForemanViewModel(
            service: MockAgentService(script: [
                .answer("Use filter OF-2231 "),
                .answer("and torque to 18 Nm."),
                status(.Answered, backend: "scripted routing — no on-device LLM present"),
            ]),
            traceReader: FakeTraceReader(json: drainJSON))
        vm.query = "  which oil filter?  "
        await vm.ask()

        XCTAssertEqual(vm.turns.count, 1)
        let turn = vm.turns.first
        XCTAssertEqual(turn?.query, "which oil filter?") // sanitized before crossing the seam
        XCTAssertEqual(turn?.answer, "Use filter OF-2231 and torque to 18 Nm.")
        XCTAssertEqual(turn?.outcome, .answered)
        XCTAssertEqual(turn?.toolSteps, 2)
        // The backend badge is the wire's string VERBATIM — never hardcoded/assumed by the client.
        XCTAssertEqual(turn?.backend, "scripted routing — no on-device LLM present")
        // agent.turn first (earliest startUs), then the tool spans re-sorted ascending.
        XCTAssertEqual(turn?.toolSpans.map(\.name),
                       ["agent.turn", "agent.tool.search_manuals", "agent.tool.engine_stats"])
        XCTAssertEqual(vm.query, "")
        XCTAssertFalse(vm.running)
        XCTAssertNil(vm.errorMessage)
    }

    func testStepCapReachedNeverReadsAnswered() async {
        let vm = ForemanViewModel(
            service: MockAgentService(script: [
                .answer("partial work before the cap"),
                status(.StepCapReached, toolSteps: 4, backend: "on-device LLM (grammar brain)"),
            ]),
            traceReader: FakeTraceReader(json: nil))
        vm.query = "audit everything"
        await vm.ask()

        XCTAssertEqual(vm.turns.first?.outcome, .stepCapReached)
        XCTAssertNotEqual(vm.turns.first?.outcome, .answered)
        XCTAssertEqual(vm.turns.first?.toolSteps, 4)
        XCTAssertEqual(vm.turns.first?.backend, "on-device LLM (grammar brain)")
        XCTAssertEqual(vm.turns.first?.toolSpans.count, 0) // nil drain -> honestly empty strip
        XCTAssertFalse(vm.running)
    }

    func testErrorStatusNeverReadsAnswered() async {
        let vm = ForemanViewModel(
            service: MockAgentService(script: [
                status(.Error, toolSteps: 0, backend: "unknown (status fragment unparseable)"),
            ]),
            traceReader: FakeTraceReader(json: nil))
        vm.query = "hello"
        await vm.ask()

        XCTAssertEqual(vm.turns.first?.outcome, .error)
        XCTAssertNotEqual(vm.turns.first?.outcome, .answered)
        XCTAssertEqual(vm.turns.first?.backend, "unknown (status fragment unparseable)")
        XCTAssertFalse(vm.running)
    }

    func testStreamEndingWithoutStatusMarksTurnError() async {
        // The stuck-spinner root: a stream that finishes without ever delivering a terminal status
        // fragment must resolve the turn as error, never leave it streaming forever.
        let vm = ForemanViewModel(
            service: MockAgentService(script: [.answer("half an answer, then the engine went dark")]),
            traceReader: FakeTraceReader(json: nil))
        vm.query = "hello"
        await vm.ask()

        XCTAssertEqual(vm.turns.first?.outcome, .error)
        XCTAssertEqual(vm.turns.first?.answer, "half an answer, then the engine went dark")
        XCTAssertFalse(vm.running)
    }

    func testServiceFailureMarksTurnErrorAndSurfacesMessage() async {
        let vm = ForemanViewModel(
            service: MockAgentService(script: [], failure: .startFailed(-3)),
            traceReader: FakeTraceReader(json: nil))
        vm.query = "hello"
        await vm.ask()

        XCTAssertEqual(vm.turns.first?.outcome, .error)
        XCTAssertNotNil(vm.errorMessage)
        XCTAssertFalse(vm.running)
    }

    func testBlankQueryIsIgnored() async {
        let vm = ForemanViewModel(service: MockAgentService(), traceReader: FakeTraceReader(json: nil))
        vm.query = "   "
        await vm.ask()

        XCTAssertTrue(vm.turns.isEmpty)
        XCTAssertFalse(vm.running)
    }
}
