import XCTest
@testable import DotnetNativeInteropUnified

/// Pins the client half of the `dni_log_drain` contract (abi/dni.h): the camelCase payload decodes, a
/// record with no exception/requestId leaves them nil, an exception-carrying record surfaces the detail,
/// unknown/missing keys degrade gracefully, and the severity rank/filter drive `LogViewModel` correctly.
final class LogModelsTests: XCTestCase {

    func testDecodesDrainWithRecords() throws {
        let json = """
        {"nowUs":29846.8,"dropped":2,"capacity":256,"records":[
          {"level":"Information","category":"Dni.Engine","message":"Engine initialized","timestampUs":100.0},
          {"level":"Warning","category":"Dni.Engine","message":"Session 42 token drain ended abnormally",
           "timestampUs":200.5,"exception":"OperationCanceledException: cancelled"}]}
        """
        let drain = try JSONDecoder().decode(LogDrain.self, from: Data(json.utf8))
        XCTAssertEqual(drain.nowUs, 29846.8)
        XCTAssertEqual(drain.dropped, 2)
        XCTAssertEqual(drain.capacity, 256)
        XCTAssertEqual(drain.records.count, 2)

        XCTAssertEqual(drain.records[0].level, "Information")
        XCTAssertNil(drain.records[0].exception, "a record with no exception leaves it nil")
        XCTAssertNil(drain.records[0].requestId, "a record with no requestId leaves it nil")
        XCTAssertEqual(drain.records[1].exception, "OperationCanceledException: cancelled")
    }

    func testLenientDecodeToleratesMissingAndUnknownKeys() throws {
        // A future engine adds a key; a missing "records" degrades to empty, not a decode failure.
        let drain = try JSONDecoder().decode(LogDrain.self, from: Data(#"{"nowUs":1.0,"newKey":true}"#.utf8))
        XCTAssertTrue(drain.records.isEmpty)
        XCTAssertEqual(drain.capacity, 256)
    }

    func testSeverityRankOrdersLevels() {
        func rec(_ level: String) -> LogRecord { LogRecord(level: level, category: "c", message: "m", timestampUs: 0) }
        XCTAssertGreaterThan(rec("Critical").severityRank, rec("Error").severityRank)
        XCTAssertGreaterThan(rec("Error").severityRank, rec("Warning").severityRank)
        XCTAssertGreaterThan(rec("Warning").severityRank, rec("Information").severityRank)
        XCTAssertEqual(rec("Verbose").severityRank, 0, "an unknown level ranks at the bottom")
    }

    @MainActor
    func testViewModelDrainThenSeverityFilter() {
        let reader = FakeLogReader(result: LogDrain(nowUs: 5, dropped: 1, capacity: 256, records: [
            LogRecord(level: "Information", category: "c", message: "i", timestampUs: 1),
            LogRecord(level: "Warning", category: "c", message: "w", timestampUs: 2),
            LogRecord(level: "Error", category: "c", message: "e", timestampUs: 3, exception: "X: boom"),
        ]))
        let vm = LogViewModel(reader: reader)
        vm.drain()

        XCTAssertNil(vm.error)
        XCTAssertEqual(vm.records.count, 3)
        XCTAssertEqual(vm.lastDrainCount, 3)
        XCTAssertEqual(vm.droppedTotal, 1, "overflow is disclosed and accumulated")
        XCTAssertEqual(vm.visibleRecords.count, 3, "default filter (all) shows everything")

        vm.select(.errorsOnly)
        XCTAssertEqual(vm.visibleRecords.map(\.level), ["Error"])
        XCTAssertEqual(vm.hiddenByFilter, 2)

        vm.select(.warnPlus)
        XCTAssertEqual(vm.visibleRecords.map(\.level), ["Warning", "Error"])
    }

    @MainActor
    func testViewModelNullDrainSurfacesErrorNeverCrashes() {
        let vm = LogViewModel(reader: FakeLogReader(result: nil))
        vm.drain()
        XCTAssertNotNil(vm.error, "a NULL drain is surfaced honestly, not swallowed")
        XCTAssertTrue(vm.records.isEmpty)
    }
}

/// Injected in place of `NativeLogReader` so these tests never touch the C ABI.
private struct FakeLogReader: LogReader {
    let result: LogDrain?
    func drain() -> LogDrain? { result }
}
