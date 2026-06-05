import XCTest

/// Navigates every meaningful screen of the unified app and attaches a screenshot of each. Run on a
/// Simulator via `xcodebuild test`; the attachments are exported from the .xcresult afterwards.
///
/// Best-effort: navigation is guarded by existence checks and `continueAfterFailure = true`, so a single
/// element that can't be found doesn't abort the whole capture run — you still get every other screen.
@MainActor
final class ScreenshotTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = true
        app.launch()
        // Capture in landscape — the iPad layout (wide transport pickers + charts) reads better than portrait.
        XCUIDevice.shared.orientation = .landscapeLeft
        // Let the first launch + rotation + initial catalog load settle.
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

        // --- AI (on-device semantic search embedded by the .NET engine + Apple's model for contrast) ---
        tapTab("AI")
        sleep(2)
        shot("16-ai-hub")
        captureSemanticSearch(as: "17-semantic-search")
        drill("Apple chat", settle: 3, as: "18-apple-chat")

        // --- Manuals (EVS: Swift-side ONNX + Core ML, prebuilt SQLite index) ---
        tapTab("Manuals")
        sleep(2)
        captureEdgeSearch(as: "19-edge-search")
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
        if tapTabButton(label) { return }
        // iPad's top tab bar fits ~6 items and collapses the rest behind an overflow control
        // (a ">"/"More"). Open whichever affordance exists, then retry the now-revealed tab.
        for overflow in ["More", "more", "ellipsis", "Show More"] {
            let control = app.buttons[overflow]
            if control.waitForExistence(timeout: 1), control.isHittable {
                control.tap()
                sleep(1)
                break
            }
        }
        _ = tapTabButton(label)
    }

    /// Taps a top-level tab by label via the tab bar or a plain button; returns whether it succeeded.
    /// iPhone renders a bottom tab bar; iPad (iPadOS 18+) renders a top bar whose items are plain buttons.
    @discardableResult
    private func tapTabButton(_ label: String) -> Bool {
        let tabBarButton = app.tabBars.buttons[label]
        if tabBarButton.waitForExistence(timeout: 2), tabBarButton.isHittable {
            tabBarButton.tap()
            sleep(1)
            return true
        }
        let button = app.buttons[label].firstMatch
        if button.waitForExistence(timeout: 3), button.isHittable {
            button.tap()
            sleep(1)
            return true
        }
        return false
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

    /// Enter semantic search, type a query, submit (onSubmit runs the .NET engine: loads the ONNX model,
    /// embeds both corpora, ranks the query), wait for results, screenshot, then pop back.
    private func captureSemanticSearch(as name: String) {
        guard tapRow("Semantic search") else { return }
        sleep(1)
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 5) {
            field.tap()
            field.typeText("encrypt my data\n")
        }
        // First query is cold: it loads the ~90 MB all-MiniLM model and embeds both corpora before ranking.
        sleep(15)
        shot(name)
        goBack()
    }

    /// Enter Manuals, type a maintenance query, submit (loads the ONNX model + index, embeds via Core ML,
    /// ranks), wait for results, screenshot.
    private func captureEdgeSearch(as name: String) {
        let field = app.textFields.firstMatch
        if field.waitForExistence(timeout: 6) {
            field.tap()
            field.typeText("compressor won't start\n")
        }
        // First query is cold: lazy-loads the ~90 MB model + SQLite index, then embeds via Core ML.
        sleep(15)
        shot(name)
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
