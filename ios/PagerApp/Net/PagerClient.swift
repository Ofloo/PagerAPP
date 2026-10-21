import Foundation

/// Thin PagerMCP HTTP client, mirroring the Android PagerClient.
final class PagerClient {
    static let shared = PagerClient()

    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 30
        session = URLSession(configuration: cfg)
    }

    func createMailbox(serverUrl: String) async throws -> String {
        var request = URLRequest(url: URL(string: "\(serverUrl)/mailboxes")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Self.check(response)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let uuid = obj["uuid"] as? String
        else { throw PagerError.badResponse }
        return uuid
    }

    func messages(serverUrl: String, uuid: String) async throws -> [Page] {
        var request = URLRequest(url: URL(string: "\(serverUrl)/mailboxes/\(uuid)/messages")!)
        request.httpMethod = "GET"
        request.setValue("Bearer \(uuid)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Self.check(response)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = obj["messages"] as? [[String: Any]]
        else { return [] }
        return raw.compactMap { try? PageParser.parse($0) }
    }

    /// Removes one processed page from the mailbox queue.
    func ack(serverUrl: String, uuid: String, messageId: String) async throws {
        var request = URLRequest(
            url: URL(string: "\(serverUrl)/mailboxes/\(uuid)/ack")!
        )
        request.httpMethod = "POST"
        request.setValue("Bearer \(uuid)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["id": messageId])
        let (_, response) = try await session.data(for: request)
        try Self.check(response)
    }

    func notify(serverUrl: String, bearer: String, payload: [String: Any]) async throws {
        var request = URLRequest(url: URL(string: "\(serverUrl)/notify")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (_, response) = try await session.data(for: request)
        try Self.check(response)
    }

    private static func check(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw PagerError.badResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw PagerError.http(http.statusCode)
        }
    }

    enum PagerError: Error { case badResponse, http(Int) }
}
