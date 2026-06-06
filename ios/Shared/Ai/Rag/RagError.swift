import Foundation

enum RagError: LocalizedError {
    case startFailed(Int)
    case nullResult
    case badURL
    case http(Int)
    case appleUnavailable(String)

    var errorDescription: String? {
        switch self {
        case .startFailed(let code): return "The engine couldn't start the RAG session (status \(code))."
        case .nullResult:            return "The native library returned no data."
        case .badURL:                return "Couldn't form the loopback RAG URL."
        case .http(let code):        return "RAG HTTP request failed (status \(code))."
        case .appleUnavailable(let why): return why
        }
    }
}
