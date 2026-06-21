import XCTest
@testable import DotnetNativeInteropUnified

final class BoundaryModelsTests: XCTestCase {
    func testBoundaryEchoDecodesCamelCase() throws {
        let json = #"{"bytesHex":"48656C6C6F","len":5,"decoded":"Hello","managedThreadId":2,"executeUs":3.4,"ptrIn":"0x16d4e08"}"#
        let echo = try JSONDecoder().decode(BoundaryEcho.self, from: Data(json.utf8))
        XCTAssertEqual(echo.decoded, "Hello")
        XCTAssertEqual(echo.bytesHex, "48656C6C6F")
        XCTAssertEqual(echo.len, 5)
        XCTAssertEqual(echo.managedThreadId, 2)
        XCTAssertEqual(echo.executeUs, 3.4, accuracy: 1e-9)
        XCTAssertEqual(echo.ptrIn, "0x16d4e08")
    }

    func testBoundaryThrowDecodes() throws {
        let json = #"{"caught":true,"type":"System.InvalidOperationException","message":"contained","status":-5}"#
        let t = try JSONDecoder().decode(BoundaryThrow.self, from: Data(json.utf8))
        XCTAssertTrue(t.caught)
        XCTAssertEqual(t.status, -5)
        XCTAssertTrue(t.type.contains("InvalidOperationException"))
    }

    func testPhaseTimingTotal() {
        let t = PhaseTiming(marshalUs: 1, crossUs: 2, executeUs: 4, callbackUs: 0, freeUs: 1)
        XCTAssertEqual(t.totalUs, 8, accuracy: 1e-9)
    }
}
