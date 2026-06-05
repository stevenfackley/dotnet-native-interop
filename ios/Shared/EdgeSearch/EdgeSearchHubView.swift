import SwiftUI

/// The "Manuals" tab root — offline edge search driven entirely Swift-side (ONNX + Core ML), a contrast
/// to the AI tab's in-engine NativeAOT ONNX search.
struct EdgeSearchHubView: View {
    var body: some View {
        NavigationStack { EdgeSearchView() }
    }
}
