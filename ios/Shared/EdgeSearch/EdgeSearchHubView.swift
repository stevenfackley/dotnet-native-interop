import SwiftUI

/// The Search section's "On-device" segment root — offline edge search driven entirely Swift-side (ONNX +
/// Core ML), a contrast to the Engine (FFI) segment's in-engine NativeAOT ONNX search (IA collapse spec,
/// 2026-06-21 — was the standalone "Manuals" tab).
struct EdgeSearchHubView: View {
    var body: some View {
        NavigationStack { EdgeSearchView() }
    }
}
