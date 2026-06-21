import SwiftUI

/// The lifecycle swimlane: one lane per layer, with the active phase highlighting its lane(s) and a
/// traveling token. The callback phase is the signature moment — the token hops from `worker` up to `UI`.
/// iOS has NO JNI lane (that hop only exists on Android).
struct SwimlaneView: View {
    let activePhase: BoundaryPhase?
    let streaming: Bool

    private enum Lane: Int, CaseIterable, Identifiable {
        case ui, binding, cabi, net, worker
        var id: Int { rawValue }
        var name: String {
            switch self {
            case .ui: "UI thread"
            case .binding: "binding"
            case .cabi: "C ABI"
            case .net: ".NET AOT"
            case .worker: "worker"
            }
        }
        var tint: Color {
            switch self {
            case .ui, .cabi: Instrument.accent
            case .binding: Instrument.textSecondary
            case .net, .worker: Instrument.ok
            }
        }
    }

    /// Which lane holds the token for a given phase.
    private func lane(for phase: BoundaryPhase) -> Lane {
        switch phase {
        case .marshal: .binding
        case .cross: .cabi
        case .execute: .net
        case .callback: .ui      // the hop destination
        case .free: .ui
        }
    }

    private func isHot(_ lane: Lane) -> Bool {
        guard let activePhase else { return false }
        if activePhase == .callback { return lane == .worker || lane == .ui } // both lanes light during the hop
        return self.lane(for: activePhase) == lane
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Instrument.Space.s) {
            ForEach(Lane.allCases) { lane in
                HStack(spacing: Instrument.Space.s) {
                    Text(lane.name)
                        .font(Instrument.panelLabel)
                        .foregroundStyle(isHot(lane) ? Instrument.accent : Instrument.textTertiary)
                        .frame(width: 72, alignment: .trailing)
                    track(lane)
                }
            }
        }
        .animation(.spring(duration: 0.4), value: activePhase)
    }

    private func track(_ lane: Lane) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Instrument.bg2)
                RoundedRectangle(cornerRadius: 3)
                    .strokeBorder(isHot(lane) ? Instrument.accent : Instrument.hairline,
                                  lineWidth: isHot(lane) ? 1.5 : 1)
                if showsToken(lane) {
                    Circle()
                        .fill(lane.tint)
                        .frame(width: 9, height: 9)
                        .shadow(color: lane.tint.opacity(0.7), radius: isHopping ? 6 : 0)
                        .offset(x: tokenX(lane, width: geo.size.width))
                }
            }
        }
        .frame(height: 16)
    }

    private var isHopping: Bool { activePhase == .callback }

    private func showsToken(_ lane: Lane) -> Bool {
        guard let activePhase else { return false }
        if activePhase == .callback { return lane == .worker || lane == .ui }
        return self.lane(for: activePhase) == lane
    }

    /// Token x within a lane: progresses left→right across phases; on the hop it sits at the right edge.
    private func tokenX(_ lane: Lane, width: CGFloat) -> CGFloat {
        guard let activePhase, let idx = BoundaryPhase.allCases.firstIndex(of: activePhase) else { return 4 }
        let progress = CGFloat(idx) / CGFloat(BoundaryPhase.allCases.count - 1)
        return max(4, min(width - 13, progress * (width - 13)))
    }
}

#Preview {
    SwimlaneView(activePhase: .callback, streaming: true)
        .padding()
        .background(Instrument.bg0)
}
