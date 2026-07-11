/// Total mapping from `TransportKind` to a value. Replaces `[TransportKind: Value]` in the
/// ViewModels so transport lookup can never fail — no optionals, no force-unwraps.
struct TransportMap<Value> {
    let ffi: Value
    let binary: Value
    let http: Value
    let sqlite: Value

    subscript(kind: TransportKind) -> Value {
        switch kind {
        case .ffi:    return ffi
        case .binary: return binary
        case .http:   return http
        case .sqlite: return sqlite
        }
    }
}

extension TransportMap: Sendable where Value: Sendable {}
