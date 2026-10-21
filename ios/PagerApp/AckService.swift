import Foundation

/// The human ACK action: remove the page from its queue and, when requested, report the ACK.
enum AckService {
    @discardableResult
    static func ack(_ entry: HistoryEntry, ownUuid: String?) async -> Bool {
        guard let mailbox = entry.mailbox, let server = entry.serverUrl else { return false }

        if let reply = entry.replyUuid, !reply.isEmpty,
           let token = entry.ackToken, !token.isEmpty {
            let payload: [String: Any] = [
                "PROJECT": "PagerAPP",
                "status": 0,
                "message": "ack \(token)",
                "JOB_ID": "ack \(token)",
            ]
            _ = try? await PagerClient.shared.notify(
                serverUrl: server,
                bearer: reply,
                payload: payload
            )
        } else if let target = entry.ackUuid, !target.isEmpty {
            var payload: [String: Any] = [
                "PROJECT": "PagerAPP",
                "status": 6,
                "message": "Acknowledged",
                "in_reply_to": entry.id,
            ]
            if let job = entry.jobId { payload["JOB_ID"] = job }
            if let ownUuid, !ownUuid.isEmpty { payload["acknowledged_by"] = ownUuid }
            _ = try? await PagerClient.shared.notify(
                serverUrl: server,
                bearer: target,
                payload: payload
            )
        }

        do {
            try await PagerClient.shared.ack(
                serverUrl: server,
                uuid: mailbox,
                messageId: entry.id
            )
        } catch {
            return false
        }

        Store.shared.markAcked(entry.id)
        Notifier.remove(pageId: entry.id)
        await MainActor.run { Poller.shared.historyChanged() }
        return true
    }
}
