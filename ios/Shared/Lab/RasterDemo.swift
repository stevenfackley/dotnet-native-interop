import SwiftUI

/// A visual demo whose state lives in an ObservableObject (so a long-lived async loop reads it live,
/// avoiding SwiftUI @State capture staleness). `renderLoop` renders frames as fast as the transport
/// allows; when `animating`, it advances the demo's parameters each frame.
@MainActor
protocol RasterDemo: ObservableObject {
    var payload: String? { get set }
    var fps: Double { get set }
    var frameMs: Double { get set }
    var dims: String { get set }
    var animating: Bool { get }
    func currentCommand() -> String
    func advance()
    var render: (String) async -> FeatureResult? { get }
}

extension RasterDemo {
    /// Continuously renders the current command. Re-renders immediately while animating; otherwise only
    /// when the command changes (gesture/slider), idling 50 ms between checks to avoid a busy loop.
    func renderLoop() async {
        var last = ""
        while !Task.isCancelled {
            let command = currentCommand()
            if animating || command != last {
                let start = DispatchTime.now().uptimeNanoseconds
                if let result = await render(command) {
                    payload = result.result
                    if let colon = result.result.firstIndex(of: ":") {
                        dims = String(result.result[..<colon])
                    }
                    let ms = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
                    frameMs = ms
                    fps = ms > 0 ? min(120, 1000 / ms) : 0
                    last = command
                }
                if animating { advance() }
            } else {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }
}

/// Presentational frame + live readout (fps · ms/frame · dimensions · transport). No logic.
struct RasterCanvas: View {
    let payload: String?
    let fps: Double
    let frameMs: Double
    let dims: String
    let transport: String

    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                if let payload, let image = VisualFeature.image(from: payload) {
                    image.interpolation(.none).resizable().scaledToFit()
                } else {
                    ContentUnavailableView("Rendering…", systemImage: "cpu")
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 340)
            .background(.black)
            .clipShape(RoundedRectangle(cornerRadius: 12))

            HStack {
                Label(String(format: "%.1f fps", fps), systemImage: "speedometer")
                Spacer()
                Text(String(format: "%.1f ms/frame", frameMs)).foregroundStyle(.secondary)
                Spacer()
                Text(dims).foregroundStyle(.secondary)
                Spacer()
                Text(transport).foregroundStyle(.secondary)
            }
            .font(.caption.monospacedDigit())
        }
    }
}

/// Segmented transport picker bound to the Lab's selected transport.
struct LabTransportPicker: View {
    @Binding var transport: TransportKind

    var body: some View {
        Picker("Transport", selection: $transport) {
            ForEach(TransportKind.allCases) { kind in
                Text(kind.displayName).tag(kind)
            }
        }
        .pickerStyle(.segmented)
    }
}
