import XCTest
@testable import DotnetNativeInteropUnified

@MainActor
final class BoundaryViewModelTests: XCTestCase {
    func testEchoPopulatesTimingAndLedger() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .echo
        vm.input = "Hello"
        await vm.run()
        XCTAssertEqual(vm.echo?.decoded, "Hello")
        XCTAssertGreaterThan(vm.timing.totalUs, 0)
        XCTAssertEqual(vm.ledger.count, 2)
        XCTAssertEqual(vm.outstandingBytes, 0)
        XCTAssertNil(vm.errorMessage)
    }

    func testLeakAccumulatesOutstandingBytes() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .echo
        vm.skipFree = true
        await vm.run()
        XCTAssertGreaterThan(vm.outstandingBytes, 0)
        XCTAssertEqual(vm.ledger.last?.freed, false)
    }

    func testThrowIsContained() async {
        let vm = BoundaryViewModel(service: MockBoundaryService())
        vm.preset = .exception
        await vm.run()
        XCTAssertEqual(vm.thrown?.caught, true)
        XCTAssertEqual(vm.thrown?.status, -5)
        XCTAssertNil(vm.errorMessage)
    }
}
