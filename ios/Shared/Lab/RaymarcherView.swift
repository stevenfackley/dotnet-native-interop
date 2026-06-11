import SwiftUI

/// A ray-marched 3D scene (sphere on a checker plane) rendered entirely on the CPU in C#. Auto-rotates;
/// drag to orbit manually. The live FPS readout is the achievable frame rate of a managed software renderer.
@MainActor
final class RaymarcherModel: ObservableObject, RasterDemo {
    @Published var payload: String?
    @Published var fps = 0.0
    @Published var frameMs = 0.0
    @Published var dims = "—"

    @Published var angle = 0.0
    @Published var spinning = true

    let size = 220
    private let lab: LabViewModel

    init(lab: LabViewModel) { self.lab = lab }

    var animating: Bool { spinning }
    var render: (String) async -> FeatureResult? { { [lab] command in await lab.render(command) } }

    func currentCommand() -> String {
        "viz-raymarch~angle_\(String(format: "%.3f", angle))~w_\(size)~h_\(size)"
    }

    func advance() { angle += 0.03 }
}

struct RaymarcherView: View {
    @ObservedObject var lab: LabViewModel
    @StateObject private var model: RaymarcherModel

    @State private var baseAngle: Double?

    init(lab: LabViewModel) {
        _lab = ObservedObject(wrappedValue: lab)
        _model = StateObject(wrappedValue: RaymarcherModel(lab: lab))
    }

    var body: some View {
        List {
            Section {
                RasterCanvas(payload: model.payload, fps: model.fps, frameMs: model.frameMs,
                             dims: model.dims, transport: lab.transport.displayName,
                             errorMessage: lab.lastError)
                    .gesture(orbit)
            }
            .instrumentRow()
            Section("Controls") {
                Toggle("Auto-rotate", isOn: $model.spinning)
                LabTransportPicker(transport: $lab.transport)
            }
            .instrumentRow()
            Section {
                Text("A signed-distance-field raymarcher — sphere, ground plane, soft shadow — with every "
                     + "ray traced on the CPU in C#. No GPU, no Metal, no shaders.")
                    .font(.caption).foregroundStyle(Instrument.textSecondary)
            }
            .instrumentRow()
        }
        .instrumentScreen()
        .navigationTitle("Raymarched 3D")
        .task { await model.renderLoop() }
    }

    private var orbit: some Gesture {
        DragGesture()
            .onChanged { value in
                if baseAngle == nil { baseAngle = model.angle }
                model.spinning = false
                model.angle = (baseAngle ?? model.angle) + (Double(value.translation.width) / 120.0)
            }
            .onEnded { _ in baseAngle = nil }
    }
}
