import Foundation

// MARK: - Model (Swift port of shared/Model.kt)

enum NotifyAction: String, Codable, CaseIterable {
    case silent, soundVibrate, urgent
}

enum TimeBound: String, Codable, CaseIterable {
    case always, activeHours, never
}

enum WatchMode: String, Codable, CaseIterable {
    case off, phoneAndWatch, watchOnly
}

enum Channel: String, Codable, Hashable {
    case phone, watch
}

enum VibrationPattern: String, Codable, CaseIterable {
    case short, long, repeated
}

struct StatusRule: Codable, Equatable {
    var action: NotifyAction
    var timeBound: TimeBound
    var vibration: VibrationPattern
    var followsGeneralSchedule: Bool
    var customWindows: [ActiveWindow]

    init(
        action: NotifyAction,
        timeBound: TimeBound,
        vibration: VibrationPattern = .short,
        followsGeneralSchedule: Bool = true,
        customWindows: [ActiveWindow] = []
    ) {
        self.action = action
        self.timeBound = timeBound
        self.vibration = vibration
        self.followsGeneralSchedule = followsGeneralSchedule
        self.customWindows = customWindows
    }

    private enum CodingKeys: String, CodingKey {
        case action, timeBound, vibration, followsGeneralSchedule, customWindows
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        action = try c.decode(NotifyAction.self, forKey: .action)
        timeBound = try c.decode(TimeBound.self, forKey: .timeBound)
        vibration = try c.decodeIfPresent(VibrationPattern.self, forKey: .vibration) ?? .short
        followsGeneralSchedule = try c.decodeIfPresent(Bool.self, forKey: .followsGeneralSchedule) ?? true
        customWindows = try c.decodeIfPresent([ActiveWindow].self, forKey: .customWindows) ?? []
    }

    func windows(_ config: PagerConfig) -> [ActiveWindow] {
        if followsGeneralSchedule { return config.activeWindows }
        return customWindows.isEmpty ? config.activeWindows : customWindows
    }
}

struct ActiveWindow: Codable, Equatable {
    /// Minutes since midnight, so it stays Codable-friendly and timezone-free.
    var startMinutes: Int
    var endMinutes: Int

    func contains(minutes: Int) -> Bool {
        if startMinutes <= endMinutes {
            return minutes >= startMinutes && minutes < endMinutes
        }
        return minutes >= startMinutes || minutes < endMinutes
    }
}

let LEVEL_NAMES = ["debug", "info", "warning", "alarm", "critical"]

func defaultLevelOrder() -> [String] {
    ["info", "warning", "alarm", "critical", "debug"]
}

func sanitizeLevelOrder(_ order: [String]?) -> [String] {
    var valid: [String] = []
    for raw in order ?? [] {
        let level = raw.trimmingCharacters(in: .whitespaces).lowercased()
        if LEVEL_NAMES.contains(level) && !valid.contains(level) { valid.append(level) }
    }
    return valid + LEVEL_NAMES.filter { !valid.contains($0) }
}

struct PagerConfig: Codable, Equatable {
    var statusRules: [Int: StatusRule]
    var activeWindows: [ActiveWindow]
    var bypassMode: Bool
    var silenceMode: Bool
    var watchMode: WatchMode
    var levelOrder: [String]

    init(
        statusRules: [Int: StatusRule] = [:],
        activeWindows: [ActiveWindow] = [ActiveWindow(startMinutes: 7 * 60, endMinutes: 23 * 60)],
        bypassMode: Bool = false,
        silenceMode: Bool = false,
        watchMode: WatchMode = .off,
        levelOrder: [String] = defaultLevelOrder()
    ) {
        self.statusRules = statusRules
        self.activeWindows = activeWindows
        self.bypassMode = bypassMode
        self.silenceMode = silenceMode
        self.watchMode = watchMode
        self.levelOrder = sanitizeLevelOrder(levelOrder)
    }

    private enum CodingKeys: String, CodingKey {
        case statusRules, activeWindows, bypassMode, silenceMode, watchMode, levelOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        statusRules = try c.decodeIfPresent([Int: StatusRule].self, forKey: .statusRules) ?? [:]
        activeWindows = try c.decodeIfPresent([ActiveWindow].self, forKey: .activeWindows)
            ?? [ActiveWindow(startMinutes: 7 * 60, endMinutes: 23 * 60)]
        bypassMode = try c.decodeIfPresent(Bool.self, forKey: .bypassMode) ?? false
        silenceMode = try c.decodeIfPresent(Bool.self, forKey: .silenceMode) ?? false
        watchMode = try c.decodeIfPresent(WatchMode.self, forKey: .watchMode) ?? .off
        let order = try c.decodeIfPresent([String].self, forKey: .levelOrder)
        levelOrder = sanitizeLevelOrder(order)
    }
}

struct Page: Identifiable, Equatable {
    let id: String
    let status: Int
    var createdAt: Double? = nil
    var ageSeconds: Double? = nil
    var project: String?
    var jobId: String?
    var message: String?
    var logs: String?
    var prio: String?
    var ackUuid: String?
    var replyUuid: String? = nil
    var ackToken: String? = nil
    var from: String? = nil
    var fromName: String? = nil
    var mid: String? = nil
    var read: String? = nil
    var pub: String? = nil
    var enc: String? = nil

    init(
        id: String,
        status: Int,
        createdAt: Double? = nil,
        ageSeconds: Double? = nil,
        project: String? = nil,
        jobId: String? = nil,
        message: String? = nil,
        logs: String? = nil,
        prio: String? = nil,
        ackUuid: String? = nil,
        replyUuid: String? = nil,
        ackToken: String? = nil,
        from: String? = nil,
        fromName: String? = nil,
        mid: String? = nil,
        read: String? = nil,
        pub: String? = nil,
        enc: String? = nil
    ) {
        self.id = id
        self.status = status
        self.createdAt = createdAt
        self.ageSeconds = ageSeconds
        self.project = project
        self.jobId = jobId
        self.message = message
        self.logs = logs
        self.prio = prio
        self.ackUuid = ackUuid
        self.replyUuid = replyUuid
        self.ackToken = ackToken
        self.from = from
        self.fromName = fromName
        self.mid = mid
        self.read = read
        self.pub = pub
        self.enc = enc
    }

    var ackAvailable: Bool { !(ackUuid ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
    var prologAckAvailable: Bool {
        !(replyUuid ?? "").trimmingCharacters(in: .whitespaces).isEmpty &&
            !(ackToken ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }
}

struct Decision: Equatable {
    let notify: Bool
    let action: NotifyAction
    let channels: Set<Channel>
    let vibration: VibrationPattern
    let reason: String
}

// MARK: - Defaults (port of shared/Defaults.kt)

func defaultRuleFor(_ status: Int) -> StatusRule {
    switch status {
    case 0: return StatusRule(action: .silent, timeBound: .never)                                  // Success
    case 1: return StatusRule(action: .soundVibrate, timeBound: .activeHours, vibration: .long)    // Warning
    case 2: return StatusRule(action: .soundVibrate, timeBound: .activeHours, vibration: .long)    // Failed
    case 3: return StatusRule(action: .urgent, timeBound: .always, vibration: .repeated)           // Critical
    case 4: return StatusRule(action: .silent, timeBound: .never)                                  // In Progress
    case 5: return StatusRule(action: .silent, timeBound: .never)                                  // Queued
    default: return StatusRule(action: .silent, timeBound: .never)                                 // custom
    }
}

// MARK: - Decide (port of shared/Decision.kt)

func statusForUrgency(_ prio: String?) -> Int? {
    switch (prio ?? "").trimmingCharacters(in: .whitespaces).lowercased() {
    case "debug": return STATUS_DEBUG
    case "info": return 0
    case "warning": return 1
    case "alarm": return 2
    case "critical": return 3
    default: return nil
    }
}

func decide(_ page: Page, _ config: PagerConfig, nowMinutes: Int) -> Decision {
    let status = statusForUrgency(page.prio) ?? page.status
    let rule = config.statusRules[status] ?? defaultRuleFor(status)

    if config.silenceMode {
        return Decision(notify: false, action: .silent, channels: [], vibration: rule.vibration,
                        reason: "silence mode")
    }

    let within: Bool
    switch rule.timeBound {
    case .never: within = false
    case .always: within = true
    case .activeHours: within = rule.windows(config).contains { $0.contains(minutes: nowMinutes) }
    }
    let show = config.bypassMode || within
    let reason = config.bypassMode ? "bypass mode"
        : (within ? "within time bound" : "outside time bound (\(rule.timeBound.rawValue))")

    guard show else {
        return Decision(notify: false, action: .silent, channels: [], vibration: rule.vibration, reason: reason)
    }

    var channels = Set<Channel>()
    if config.watchMode != .watchOnly { channels.insert(.phone) }
    if config.watchMode != .off { channels.insert(.watch) }

    return Decision(notify: true, action: rule.action, channels: channels, vibration: rule.vibration, reason: reason)
}

// MARK: - Dedup (port of shared/Deduplicator.kt)

final class Deduplicator {
    private var seen: Set<String>
    init(seen: some Sequence<String> = []) { self.seen = Set(seen) }
    func isNew(_ id: String) -> Bool { seen.insert(id).inserted }
    var snapshot: Set<String> { seen }
}

// MARK: - ACK (port of shared/Ack.kt)

let STATUS_ACKNOWLEDGED = 6
let STATUS_DEBUG = 7

struct AckRequest {
    let targetUuid: String
    let payload: [String: Any]
}

func buildAck(_ page: Page, ownUuid: String) -> AckRequest? {
    guard let target = page.ackUuid, !target.trimmingCharacters(in: .whitespaces).isEmpty else {
        return nil
    }
    var payload: [String: Any] = [
        "PROJECT": "PagerAPP",
        "status": STATUS_ACKNOWLEDGED,
        "message": "Acknowledged",
        "acknowledged_by": ownUuid,
    ]
    if let job = page.jobId { payload["JOB_ID"] = job }
    if !page.id.isEmpty { payload["in_reply_to"] = page.id }
    return AckRequest(targetUuid: target, payload: payload)
}

/// ProLOG's sender-managed ACK: a plain page with the token to its reply mailbox.
func buildPrologAck(_ page: Page) -> AckRequest? {
    guard let target = page.replyUuid, !target.trimmingCharacters(in: .whitespaces).isEmpty,
          let token = page.ackToken, !token.trimmingCharacters(in: .whitespaces).isEmpty
    else { return nil }
    return AckRequest(targetUuid: target, payload: [
        "PROJECT": "PagerAPP",
        "status": 0,
        "message": "ack \(token)",
        "JOB_ID": "ack \(token)",
    ])
}

// MARK: - Page parsing (port of shared/PageParser.kt)

enum PageParser {
    static func parse(_ json: [String: Any]) throws -> Page {
        guard let id = json["id"] as? String else { throw ParseError.missingId }
        let project = json["PROJECT"] as? String
        let message = json["message"] as? String
        let logs = json["logs"] as? String
        let parsedStatus = parseStatus(json["status"])
        let createdAt = numeric(json["created_at"])
        let ageSeconds = numeric(json["age_seconds"])
        let rawPrio = (json["prio"] as? String)?.trimmingCharacters(in: .whitespaces)
        let prio: String?
        if let rawPrio, statusForUrgency(rawPrio) != nil {
            prio = rawPrio
        } else {
            prio = prologLevel(message, project: project) ?? rawPrio
        }
        let prologAck = parsePrologAck(logs)
        return Page(
            id: id,
            status: statusForUrgency(prio) ?? parsedStatus,
            createdAt: createdAt,
            ageSeconds: ageSeconds,
            project: project,
            jobId: json["JOB_ID"] as? String,
            message: message,
            logs: logs,
            prio: prio,
            ackUuid: json["ack_uuid"] as? String,
            replyUuid: (json["reply_uuid"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? prologAck?.0,
            ackToken: (json["ack_token"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? prologAck?.1,
            from: json["from"] as? String,
            fromName: json["from_name"] as? String,
            mid: json["mid"] as? String,
            read: json["read"] as? String,
            pub: json["pub"] as? String,
            enc: json["enc"] as? String
        )
    }

    static func numeric(_ raw: Any?) -> Double? {
        if let n = raw as? Double { return n }
        if let n = raw as? Int { return Double(n) }
        if let n = raw as? NSNumber { return n.doubleValue }
        if let s = raw as? String { return Double(s) }
        return nil
    }

    static func parseStatus(_ raw: Any?) -> Int {
        if let n = raw as? Int { return n }
        if let n = raw as? Double { return Int(n) }
        if let s = raw as? String {
            if let n = Int(s.trimmingCharacters(in: .whitespaces)) { return n }
            switch s.trimmingCharacters(in: .whitespaces).lowercased() {
            case "success", "ok", "succeeded", "done", "completed", "complete": return 0
            case "warning", "warn": return 1
            case "failed", "failure", "fail", "error": return 2
            case "critical", "fatal", "urgent": return 3
            case "in progress", "in_progress", "running", "started": return 4
            case "queued", "pending": return 5
            case "acknowledged", "ack": return STATUS_ACKNOWLEDGED
            default: return 1
            }
        }
        return 1
    }

    static func prologLevel(_ message: String?, project: String?) -> String? {
        guard project?.lowercased() == "prolog", let message else { return nil }
        let text = message.trimmingCharacters(in: .whitespaces)
        guard text.hasPrefix("["), let close = text.firstIndex(of: "]") else { return nil }
        let level = String(text[text.index(after: text.startIndex)..<close]).lowercased()
        return statusForUrgency(level) == nil ? nil : level
    }

    static func parsePrologAck(_ logs: String?) -> (String, String)? {
        guard let logs else { return nil }
        let lines = logs.components(separatedBy: .newlines).map {
            $0.trimmingCharacters(in: .whitespaces)
        }
        guard let marker = lines.firstIndex(where: {
            $0.lowercased().hasPrefix("ack verplicht")
        }) else { return nil }
        let tail = lines.dropFirst(marker + 1)
        let uuidPattern = try! NSRegularExpression(
            pattern: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
        guard let uuid = tail.first(where: { line in
            uuidPattern.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil
        }) else { return nil }
        guard let tokenLine = tail.first(where: {
            $0.range(of: "ack ", options: .caseInsensitive) != nil
        }), let range = tokenLine.range(of: "ack ", options: .caseInsensitive) else { return nil }
        let token = tokenLine[range.upperBound...]
            .trimmingCharacters(in: .whitespaces)
            .split(whereSeparator: { $0.isWhitespace })
            .first
            .map(String.init)?
            .trimmingCharacters(in: CharacterSet(charactersIn: ".,;:"))
        guard let token, !token.isEmpty else { return nil }
        return (uuid, token)
    }

    enum ParseError: Error { case missingId }
}
