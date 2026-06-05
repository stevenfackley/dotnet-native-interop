import SwiftUI

/// Interactive colorized Mandelbrot: pinch to zoom, drag to pan, slide iterations, toggle an auto-dive.
/// Every pixel is computed in C# inside the NativeAOT library; switching transport changes the frame rate.
@MainActor
final class FractalExplorerModel: ObservableObject, RasterDemo {
    @Published var payload: String?
    @Published var fps = 0.0
    @Published var frameMs = 0.0
    @Published var dims = "—"

    @Published var centerX = -0.5
    @Published var centerY = 0.0
    @Published var zoom = 1.0
    @Published var iterations = 220.0
    @Published var diving = false

    let size = 256
    private let lab: LabViewModel

    init(lab: LabViewModel) { self.lab = lab }

    var animating: Bool { diving }
    var render: (String) async -> FeatureResult? { { [lab] command in await lab.render(command) } }

    func currentCommand() -> String {
        "viz-mandelbrot~cx_\(fmt(centerX))~cy_\(fmt(centerY))~zoom_\(fmt(zoom))~iters_\(Int(iterations))~w_\(size)~h_\(size)"
    }

    func advance() { zoom *= 1.03 }

    func reset() {
        centerX = -0.5
        centerY = 0.0
        zoom = 1.0
        iterations = 220
    }

    private func fmt(_ value: Double) -> String { String(format: "%.6f", value) }
}

struct FractalExplorerView: View {
    @ObservedObject var lab: LabViewModel
    @StateObject private var model: FractalExplorerModel

    @State private var baseCenter: (x: Double, y: Double)?
    @State private var baseZoom: Double?

    init(lab: LabViewModel) {
        _lab = ObservedObject(wrappedValue: lab)
        _model = StateObject(wrappedValue: FractalExplorerModel(lab: lab))
    }

    var body: some View {
        List {
            Section {
                RasterCanvas(payload: model.payload, fps: model.fps, frameMs: model.frameMs,
                             dims: model.dims, transport: lab.transport.displayName)
                    .gesture(magnify.simultaneously(with: drag))
            }
            Section("Controls") {
                Toggle("Dive (auto-zoom)", isOn: $model.diving)
                HStack {
                    Text("Iterations")
                    Slider(value: $model.iterations, in: 32...1000, step: 1)
                    Text("\(Int(model.iterations))").monospacedDigit().frame(width: 44, alignment: .trailing)
                }
                Button("Reset view") { model.reset() }
                LabTransportPicker(transport: $lab.transport)
            }
            Section {
                Text("Every pixel of this Mandelbrot set is computed in C# inside the NativeAOT library "
                     + "and sent to SwiftUI as raw bytes — no GPU, no shader, no cloud. Switch transport "
                     + "to watch the frame rate change.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Fractal Explorer")
        .task { await model.renderLoop() }
    }

    private var magnify: some Gesture {
        MagnificationGesture()
            .onChanged { scale in
                if baseZoom == nil { baseZoom = model.zoom }
                model.zoom = max(0.2, (baseZoom ?? model.zoom) * Double(scale))
            }
            .onEnded { _ in baseZoom = nil }
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { value in
                if baseCenter == nil { baseCenter = (model.centerX, model.centerY) }
                let span = 3.0 / model.zoom
                let dx = Double(value.translation.width) / 340.0 * span
                let dy = Double(value.translation.height) / 340.0 * span
                model.centerX = (baseCenter?.x ?? model.centerX) - dx
                model.centerY = (baseCenter?.y ?? model.centerY) - dy
            }
            .onEnded { _ in baseCenter = nil }
    }
}
