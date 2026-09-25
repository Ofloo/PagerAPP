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
    var vibration: VibrationPattern = .short
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

struct PagerConfig: Codable, Equatable {
    var statusRules: [Int: StatusRule] = [:]
    var activeWindows: [ActiveWindow] = [ActiveWindow(startMinutes: 7 * 60, endMinutes: 23 * 60)]
    var bypassMode: Bool = false
    var silenceMode: Bool = false
    var watchMode: WatchMode = .off
}

struct Page: Identifiable, Equatable {
    let id: String
    let status: Int
    var project: String?
    var jobId: String?
    var message: String?
    var logs: String?
    var prio: String?
    var ackUuid: String?

    var ackAvailable: Bool { !(ackUuid ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
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

func decide(_ page: Page, _ config: PagerConfig, nowMinutes: Int) -> Decision {
    let rule = config.statusRules[page.status] ?? defaultRuleFor(page.status)

    if config.silenceMode {
        return Decision(notify: false, action: .silent, channels: [], vibration: rule.vibration,
                        reason: "silence mode")
    }

    let within: Bool
    switch rule.timeBound {
    case .never: within = false
    case .always: within = true
    case .activeHours: within = config.activeWindows.contains { $0.contains(minutes: nowMinutes) }
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

// MARK: - Page parsing (port of shared/PageParser.kt)

enum PageParser {
    static func parse(_ json: [String: Any]) throws -> Page {
        guard let id = json["id"] as? String else { throw ParseError.missingId }
        let status: Int
        if let n = json["status"] as? Int { status = n }
        else if let n = json["status"] as? Double { status = Int(n) }
        else if let s = json["status"] as? String, let n = Int(s) { status = n }
        else { throw ParseError.badStatus }
        return Page(
            id: id,
            status: status,
            project: json["PROJECT"] as? String,
            jobId: json["JOB_ID"] as? String,
            message: json["message"] as? String,
            logs: json["logs"] as? String,
            prio: json["prio"] as? String,
            ackUuid: json["ack_uuid"] as? String
        )
    }

    enum ParseError: Error { case missingId, badStatus }
}
