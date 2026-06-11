import SwiftUI

/// The Lab tab: GPU-free visual compute + heavy-compute benchmarks, each its own screen.
struct LabView: View {
    @ObservedObject var lab: LabViewModel

    var body: some View {
        NavigationStack {
            List {
                Section("Visual — every pixel computed in C#") {
                    NavigationLink {
                        FractalExplorerView(lab: lab)
                    } label: {
                        Label("Fractal Explorer", systemImage: "circle.hexagongrid.fill")
                    }
                    NavigationLink {
                        RaymarcherView(lab: lab)
                    } label: {
                        Label("Raymarched 3D", systemImage: "cube.transparent")
                    }
                }
                .instrumentRow()
                Section("Benchmarks — NativeAOT throughput") {
                    NavigationLink {
                        BenchmarkDetailView(lab: lab, title: "SIMD Matmul", command: "bench-matmul~max_384")
                    } label: {
                        Label("SIMD Matrix Multiply", systemImage: "function")
                    }
                    NavigationLink {
                        BenchmarkDetailView(lab: lab, title: "Parallel Scaling", command: "bench-parallel~size_480")
                    } label: {
                        Label("Parallel Scaling", systemImage: "cpu.fill")
                    }
                }
                .instrumentRow()
            }
            .instrumentScreen()
            .navigationTitle("Lab")
        }
    }
}
