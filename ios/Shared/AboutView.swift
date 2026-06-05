import SwiftUI

/// Describes all three interop transports the app demonstrates, with features and limitations.
struct AboutView: View {
    let infos: [TransportInfo]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("One NativeAOT .NET library, three interop transports. Each runs the same "
                         + "C# 14 / .NET 10 feature catalog; the Compare tab shows their round-trip "
                         + "cost side by side.")
                    .font(.callout)
                }
                ForEach(infos, id: \.id) { info in
                    Section(info.displayName) {
                        Text(info.summary).font(.callout)
                        ForEach(info.features, id: \.self) { feature in
                            Label {
                                Text(feature)
                            } icon: {
                                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                            }
                        }
                        ForEach(info.limitations, id: \.self) { limitation in
                            Label {
                                Text(limitation)
                            } icon: {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                            }
                        }
                    }
                }
            }
            .navigationTitle("About")
        }
    }
}
