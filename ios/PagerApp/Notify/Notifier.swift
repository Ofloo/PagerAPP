import Foundation
import UserNotifications

/// Local notifications + the opt-in ACK action, mirroring the Android Notifier.
enum Notifier {
    static let categoryId = "PAGER_PAGE"
    static let ackActionId = "PAGER_ACK"

    static func registerCategories() {
        let ack = UNNotificationAction(identifier: ackActionId, title: "ACK", options: [])
        let category = UNNotificationCategory(
            identifier: categoryId,
            actions: [ack],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    static func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }

    static func post(_ page: Page, audible: Bool) {
        let content = UNMutableNotificationContent()
        content.title = page.jobId ?? page.project ?? "PagerAPP page"
        content.body = [page.message ?? "status \(page.status)", page.logs]
            .compactMap { $0 }
            .joined(separator: "\n\n")
        content.categoryIdentifier = categoryId
        content.userInfo = [
            "page_id": page.id,
            "ack_uuid": page.ackUuid ?? "",
            "reply_uuid": page.replyUuid ?? "",
            "ack_token": page.ackToken ?? "",
        ]
        if audible { content.sound = .default } else { content.sound = nil }

        let request = UNNotificationRequest(
            identifier: page.id,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
