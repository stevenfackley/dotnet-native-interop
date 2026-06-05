import XCTest

/// Navigates every meaningful screen of the unified app and attaches a screenshot of each. Run on a
/// Simulator via `xcodebuild test`; the attachments are exported from the .xcresult afterwards.
///
/// Best-effort: navigation is guarded by existence checks and `continueAfterFailure = true`, so a single
/// element that can't be found doesn't abort the whole capture run — you still get every other screen.
final class ScreenshotTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = true
        app.launch()
        // Let the first launch + initial catalog load settle.
        sleep(3)
    }

    func testCaptureEveryScreen() {
        // --- Top-level tabs ---
        capture(tab: "Dashboard", as: "01-dashboard", settle: 2)
        capture(tab: "Features", as: "02-features", settle: 2)

        // --- Lab ---
        tapTab("Lab")
        shot("03-lab")
        drill("Fractal Explorer", settle: 5, as: "04-fractal-explorer")
        drill("Raymarched 3D", settle: 5, as: "05-raymarcher")
        drillAndRun("SIMD Matrix Multiply", run: "Run benchmark", settle: 12, as: "06-bench-matmul")
        drillAndRun("Parallel Scaling", run: "Run benchmark", settle: 12, as: "07-bench-parallel")

        // --- Compare (runs every feature over all three transports) ---
        tapTab("Compare")
        tapIfExists(button: "Run comparison (all transports)")
        sleep(30)
        shot("08-compare")

        // --- Latency hub ---
        tapTab("Latency")
        shot("09-latency-hub")
        drillAndRun("Distribution", run: "Measure 300 pings", settle: 14, as: "10-distribution")
        drillAndRun("Transport comparison", run: "Compare all transports", settle: 22, as: "11-transport-comparison")
        drillAndRun("Jitter over time", run: "Sample 400 sequential pings", settle: 16, as: "12-jitter")
        drillAndRun("Payload scaling", run: "Sweep payload sizes", settle: 35, as: "13-payload-scaling")
        captureTelemetry(as: "14-telemetry")

        // --- About ---
        tapTab("About")
        sleep(2)
        shot("15-about")
    }

    // MARK: - Capture

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    // MARK: - Navigation (best-effort)

    private func tapTab(_ label: String) {
        let button = app.tabBars.buttons[label]
        if button.waitForExistence(timeout: 10) {
            button.tap()
            sleep(1)
        }
    }

    private func capture(tab: String, as name: String, settle: UInt32) {
        tapTab(tab)
        sleep(settle)
        shot(name)
    }

    /// Taps a row by label, trying the common SwiftUI accessibility shapes (button, then static text).
    @discardableResult
    private func tapRow(_ label: String) -> Bool {
        let button = app.buttons[label]
        if button.waitForExistence(timeout: 6) {
            button.tap()
            return true
        }
        let text = app.staticTexts[label]
        if text.waitForExistence(timeout: 3) {
            text.tap()
            return true
        }
        return false
    }

    private func tapIfExists(button label: String, timeout: TimeInterval = 8) {
        let button = app.buttons[label]
        if button.waitForExistence(timeout: timeout) {
            button.tap()
        }
    }

    private func goBack() {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        if back.exists {
            back.tap()
            sleep(1)
        }
    }

    /// Drill into a list row, wait for content to render, screenshot, then pop back.
    private func drill(_ row: String, settle: UInt32, as name: String) {
        _ = tapRow(row)
        sleep(settle)
        shot(name)
        goBack()
    }

    /// Drill into a row, tap a "run" button to populate it, wait, screenshot, then pop back.
    private func drillAndRun(_ row: String, run: String, settle: UInt32, as name: String) {
        _ = tapRow(row)
        sleep(1)
        tapIfExists(button: run)
        sleep(settle)
        shot(name)
        goBack()
    }

    /// Engine telemetry: enter, toggle the stress switch so GC/heap move, screenshot, stop, pop back.
    private func captureTelemetry(as name: String) {
        _ = tapRow("Engine telemetry")
        sleep(1)
        let toggle = app.switches["Run stress (loop benchmarks)"]
        let toggled = toggle.waitForExistence(timeout: 5)
        if toggled { toggle.tap() }
        sleep(7)
        shot(name)
        if toggled, toggle.exists { toggle.tap() }   // stop the stress loop before leaving
        goBack()
    }
}
