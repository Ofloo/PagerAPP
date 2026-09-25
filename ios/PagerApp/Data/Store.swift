import Foundation

/// Local persistence: mailbox UUID, config, polling interval, dedupe set, and bounded history.
/// Kept identical in spirit to the Android Store so the two apps behave the same.
final class Store {
    static let shared = Store()

    private let defaults = UserDefaults.standard
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    static let defaultServer = "https://pager.ofloo.io"
    static let defaultPollSeconds = 60
    static let minPoll = 30
    static let maxPoll = 300
    private let maxSeen = 1000
    private let maxHistory = 200

    private enum Key {
        static let server = "server_url"
        static let uuid = "pager_uuid"
        static let poll = "poll_seconds"
        static let config = "config"
        static let bypass = "bypass"
        static let silence = "silence"
        static let seen = "seen_ids"
        static let history = "history"
    }

    var serverUrl: String {
        get { defaults.string(forKey: Key.server) ?? Self.defaultServer }
        set { defaults.set(newValue, forKey: Key.server) }
    }

    var uuid: String? {
        get { defaults.string(forKey: Key.uuid) }
        set { defaults.set(newValue, forKey: Key.uuid) }
    }

    var pollSeconds: Int {
        get {
            let v = defaults.integer(forKey: Key.poll)
            return v == 0 ? Self.defaultPollSeconds : v
        }
        set { defaults.set(min(max(newValue, Self.minPoll), Self.maxPoll), forKey: Key.poll) }
    }

    var bypassMode: Bool {
        get { defaults.bool(forKey: Key.bypass) }
        set { defaults.set(newValue, forKey: Key.bypass) }
    }

    var silenceMode: Bool {
        get { defaults.bool(forKey: Key.silence) }
        set { defaults.set(newValue, forKey: Key.silence) }
    }

    var config: PagerConfig {
        get {
            guard let data = defaults.data(forKey: Key.config),
                  let cfg = try? decoder.decode(PagerConfig.self, from: data)
            else { return PagerConfig() }
            return cfg
        }
        set {
            if let data = try? encoder.encode(newValue) {
                defaults.set(data, forKey: Key.config)
            }
        }
    }

    /// The effective config with the quick toggles overlaid.
    func effectiveConfig() -> PagerConfig {
        var cfg = config
        cfg.bypassMode = bypassMode
        cfg.silenceMode = silenceMode
        return cfg
    }

    // MARK: Dedupe

    func dedupeSnapshot() -> Set<String> {
        Set(defaults.stringArray(forKey: Key.seen) ?? [])
    }

    func rememberSeen(_ ids: [String]) {
        var merged = dedupeSnapshot()
        merged.formUnion(ids)
        let trimmed = Array(merged).suffix(maxSeen)
        defaults.set(Array(trimmed), forKey: Key.seen)
    }

    // MARK: History

    func history() -> [HistoryEntry] {
        guard let data = defaults.data(forKey: Key.history),
              let entries = try? decoder.decode([HistoryEntry].self, from: data)
        else { return [] }
        return entries
    }

    func addHistory(_ entries: [HistoryEntry]) {
        let merged = Array((entries + history()).prefix(maxHistory))
        if let data = try? encoder.encode(merged) {
            defaults.set(data, forKey: Key.history)
        }
    }
}

struct HistoryEntry: Codable, Identifiable {
    let id: String
    let status: Int
    let jobId: String?
    let project: String?
    let message: String?
    let createdAt: Double
    let ackUuid: String?
    let delivered: Bool

    static func from(_ page: Page, delivered: Bool) -> HistoryEntry {
        HistoryEntry(
            id: page.id,
            status: page.status,
            jobId: page.jobId,
            project: page.project,
            message: page.message,
            createdAt: Date().timeIntervalSince1970,
            ackUuid: page.ackUuid,
            delivered: delivered
        )
    }
}
