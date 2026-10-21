import Foundation

/// A mailbox in addition to the primary install mailbox. The UUID is also the bearer token.
struct PageMailbox: Codable, Identifiable, Equatable {
    var name: String
    var uuid: String
    var serverUrl: String

    var id: String { "\(serverUrl)|\(uuid)" }
}

struct MailboxTarget: Identifiable, Equatable {
    let name: String
    let uuid: String
    let serverUrl: String

    var id: String { "\(serverUrl)|\(uuid)" }
}

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
        static let pageMailboxes = "page_mailboxes"
    }

    var serverUrl: String {
        get { defaults.string(forKey: Key.server) ?? Self.defaultServer }
        set { defaults.set(newValue, forKey: Key.server) }
    }

    var uuid: String? {
        get {
            guard let value = defaults.string(forKey: Key.uuid), !value.isEmpty else {
                return nil
            }
            return value
        }
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

    // MARK: Page addresses

    var pageMailboxes: [PageMailbox] {
        get {
            guard let data = defaults.data(forKey: Key.pageMailboxes),
                  let boxes = try? decoder.decode([PageMailbox].self, from: data)
            else { return [] }
            return boxes
        }
        set {
            if let data = try? encoder.encode(newValue) {
                defaults.set(data, forKey: Key.pageMailboxes)
            }
        }
    }

    /// Primary mailbox first, followed by the additional page addresses.
    func mailboxTargets() -> [MailboxTarget] {
        var targets: [MailboxTarget] = []
        if let primary = uuid {
            targets.append(MailboxTarget(name: "Main", uuid: primary, serverUrl: serverUrl))
        }
        targets.append(contentsOf: pageMailboxes.map {
            MailboxTarget(name: $0.name, uuid: $0.uuid, serverUrl: $0.serverUrl)
        })
        return targets
    }

    func addPageMailbox(name: String, uuid: String, serverUrl: String) {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUuid = uuid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedUuid.isEmpty else { return }
        var boxes = pageMailboxes.filter { $0.uuid != trimmedUuid }
        boxes.append(PageMailbox(
            name: trimmedName.isEmpty ? "Address \(boxes.count + 1)" : trimmedName,
            uuid: trimmedUuid,
            serverUrl: serverUrl
        ))
        pageMailboxes = boxes
    }

    func removePageMailbox(id: String) {
        pageMailboxes = pageMailboxes.filter { $0.id != id }
    }

    // MARK: Dedupe

    func dedupeSnapshot() -> Set<String> {
        Set(defaults.stringArray(forKey: Key.seen) ?? [])
    }

    func rememberSeen(_ ids: [String]) {
        var merged = dedupeSnapshot()
        merged.formUnion(ids)
        defaults.set(Array(Array(merged).suffix(maxSeen)), forKey: Key.seen)
    }

    // MARK: History

    func history() -> [HistoryEntry] {
        guard let data = defaults.data(forKey: Key.history),
              let entries = try? decoder.decode([HistoryEntry].self, from: data)
        else { return [] }
        return entries.reduce(into: [HistoryEntry]()) { result, entry in
            if !result.contains(where: { $0.id == entry.id }) { result.append(entry) }
        }
    }

    func addHistory(_ entries: [HistoryEntry]) {
        let merged = (entries + history()).reduce(into: [HistoryEntry]()) { result, entry in
            if !result.contains(where: { $0.id == entry.id }) { result.append(entry) }
        }
        if let data = try? encoder.encode(Array(merged.prefix(maxHistory))) {
            defaults.set(data, forKey: Key.history)
        }
    }

    func markAcked(_ id: String) {
        rewriteHistory { entry in
            var copy = entry
            copy.acked = true
            return copy
        }
    }

    func deleteEntry(_ id: String) {
        rewriteHistory { entry in entry.id == id ? nil : entry }
    }

    private func rewriteHistory(_ transform: (HistoryEntry) -> HistoryEntry?) {
        let entries = history().compactMap(transform)
        if let data = try? encoder.encode(entries) {
            defaults.set(data, forKey: Key.history)
        }
    }
}

struct HistoryEntry: Codable, Identifiable, Equatable {
    var id: String
    var status: Int
    var prio: String?
    var jobId: String?
    var project: String?
    var message: String?
    var logs: String?
    var createdAt: Double
    var receivedAt: Double?
    var ackUuid: String?
    var replyUuid: String?
    var ackToken: String?
    var delivered: Bool
    var mailbox: String?
    var serverUrl: String?
    var acked: Bool
    var archived: Bool
    var from: String?
    var fromName: String?

    private enum CodingKeys: String, CodingKey {
        case id, status, prio, jobId, project, message, logs, createdAt, receivedAt
        case ackUuid, replyUuid, ackToken, delivered, mailbox, serverUrl, acked, archived
        case from, fromName
    }

    init(
        id: String,
        status: Int,
        prio: String? = nil,
        jobId: String? = nil,
        project: String? = nil,
        message: String? = nil,
        logs: String? = nil,
        createdAt: Double,
        receivedAt: Double? = nil,
        ackUuid: String? = nil,
        replyUuid: String? = nil,
        ackToken: String? = nil,
        delivered: Bool,
        mailbox: String? = nil,
        serverUrl: String? = nil,
        acked: Bool = false,
        archived: Bool = false,
        from: String? = nil,
        fromName: String? = nil
    ) {
        self.id = id
        self.status = status
        self.prio = prio
        self.jobId = jobId
        self.project = project
        self.message = message
        self.logs = logs
        self.createdAt = createdAt
        self.receivedAt = receivedAt
        self.ackUuid = ackUuid
        self.replyUuid = replyUuid
        self.ackToken = ackToken
        self.delivered = delivered
        self.mailbox = mailbox
        self.serverUrl = serverUrl
        self.acked = acked
        self.archived = archived
        self.from = from
        self.fromName = fromName
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        status = try c.decodeIfPresent(Int.self, forKey: .status) ?? 1
        prio = try c.decodeIfPresent(String.self, forKey: .prio)
        jobId = try c.decodeIfPresent(String.self, forKey: .jobId)
        project = try c.decodeIfPresent(String.self, forKey: .project)
        message = try c.decodeIfPresent(String.self, forKey: .message)
        logs = try c.decodeIfPresent(String.self, forKey: .logs)
        createdAt = try c.decodeIfPresent(Double.self, forKey: .createdAt)
            ?? Date().timeIntervalSince1970
        receivedAt = try c.decodeIfPresent(Double.self, forKey: .receivedAt)
        ackUuid = try c.decodeIfPresent(String.self, forKey: .ackUuid)
        replyUuid = try c.decodeIfPresent(String.self, forKey: .replyUuid)
        ackToken = try c.decodeIfPresent(String.self, forKey: .ackToken)
        delivered = try c.decodeIfPresent(Bool.self, forKey: .delivered) ?? false
        mailbox = try c.decodeIfPresent(String.self, forKey: .mailbox)
        serverUrl = try c.decodeIfPresent(String.self, forKey: .serverUrl)
        acked = try c.decodeIfPresent(Bool.self, forKey: .acked) ?? false
        archived = try c.decodeIfPresent(Bool.self, forKey: .archived) ?? false
        from = try c.decodeIfPresent(String.self, forKey: .from)
        fromName = try c.decodeIfPresent(String.self, forKey: .fromName)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(status, forKey: .status)
        try c.encodeIfPresent(prio, forKey: .prio)
        try c.encodeIfPresent(jobId, forKey: .jobId)
        try c.encodeIfPresent(project, forKey: .project)
        try c.encodeIfPresent(message, forKey: .message)
        try c.encodeIfPresent(logs, forKey: .logs)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encodeIfPresent(receivedAt, forKey: .receivedAt)
        try c.encodeIfPresent(ackUuid, forKey: .ackUuid)
        try c.encodeIfPresent(replyUuid, forKey: .replyUuid)
        try c.encodeIfPresent(ackToken, forKey: .ackToken)
        try c.encode(delivered, forKey: .delivered)
        try c.encodeIfPresent(mailbox, forKey: .mailbox)
        try c.encodeIfPresent(serverUrl, forKey: .serverUrl)
        try c.encode(acked, forKey: .acked)
        try c.encode(archived, forKey: .archived)
        try c.encodeIfPresent(from, forKey: .from)
        try c.encodeIfPresent(fromName, forKey: .fromName)
    }

    static func from(
        _ page: Page,
        delivered: Bool,
        mailbox: String? = nil,
        serverUrl: String? = nil
    ) -> HistoryEntry {
        let now = Date().timeIntervalSince1970
        return HistoryEntry(
            id: page.id,
            status: page.status,
            prio: page.prio,
            jobId: page.jobId,
            project: page.project,
            message: page.message,
            logs: page.logs,
            createdAt: page.createdAt ?? now,
            receivedAt: now,
            ackUuid: page.ackUuid,
            replyUuid: page.replyUuid,
            ackToken: page.ackToken,
            delivered: delivered,
            mailbox: mailbox,
            serverUrl: serverUrl,
            from: page.from,
            fromName: page.fromName
        )
    }
}
