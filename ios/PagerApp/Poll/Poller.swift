import Combine
import Foundation

/// Foreground polling loop plus the single refresh path used by BGAppRefresh/background fetch.
/// iOS cannot run an unbounded background poll like Android; background delivery is opportunistic.
final class Poller: ObservableObject {
    static let shared = Poller()

    @Published private(set) var lastError: String?
    /// Bumped after a refresh writes history, so the Pages list can reload.
    @Published private(set) var revision = 0
    @Published private(set) var refreshing = false

    private var task: Task<Void, Never>?
    private let store = Store.shared
    private let client = PagerClient.shared

    func start() {
        guard task == nil else { return }
        task = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                _ = await self.refreshOnce()
                let seconds = UInt64(max(1, self.store.pollSeconds))
                try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    /// Called after a manual ACK or a local history change.
    @MainActor
    func historyChanged() {
        revision += 1
    }

    /// Fetch every configured mailbox once. Returns false if any mailbox failed.
    @discardableResult
    @MainActor
    func refreshOnce() async -> Bool {
        if refreshing { return false }
        refreshing = true
        defer { refreshing = false }

        var allSucceeded = true
        var changed = false

        for target in store.mailboxTargets() {
            do {
                let pages = try await client.messages(
                    serverUrl: target.serverUrl,
                    uuid: target.uuid
                )
                let seen = store.dedupeSnapshot()
                let fresh = pages.filter { !seen.contains($0.id) }
                guard !fresh.isEmpty else { continue }

                var history: [HistoryEntry] = []
                var pendingNotifications: [(Page, Bool)] = []
                for page in fresh {
                    // Chat and receipt pages belong to the Android chat client. This first iOS
                    // version deliberately leaves them in the queue rather than consuming them.
                    if page.status == 10 { continue }

                    var calendar = Calendar.current
                    calendar.timeZone = .current
                    let comps = calendar.dateComponents([.hour, .minute], from: Date())
                    let minutes = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
                    let decision = decide(page, store.effectiveConfig(), nowMinutes: minutes)
                    if decision.notify {
                        let audible = decision.action != .silent &&
                            decision.channels.contains(.phone)
                        pendingNotifications.append((page, audible))
                    }
                    history.append(HistoryEntry.from(
                        page,
                        delivered: decision.notify,
                        mailbox: target.uuid,
                        serverUrl: target.serverUrl
                    ))
                }

                if !history.isEmpty {
                    store.addHistory(history)
                    changed = true
                }
                for (page, audible) in pendingNotifications {
                    Notifier.post(
                        page,
                        audible: audible,
                        mailbox: target.uuid,
                        serverUrl: target.serverUrl
                    )
                }
                store.rememberSeen(fresh.filter { $0.status != 10 }.map(\.id))
                // Delivery is at-least-once: confirm every page we have processed so it does not
                // come back forever. The optional ACK report is a separate human action.
                for page in fresh where page.status != 10 {
                    do {
                        try await client.ack(
                            serverUrl: target.serverUrl,
                            uuid: target.uuid,
                            messageId: page.id
                        )
                    } catch {
                        // Keep the page in the queue; dedupe still prevents duplicate alerts in
                        // this install, and the next refresh can retry the confirmation.
                        lastError = "ACK \(page.id): \(error.localizedDescription)"
                        allSucceeded = false
                    }
                }
            } catch {
                allSucceeded = false
                lastError = "\(target.name): \(error.localizedDescription)"
            }
        }

        if allSucceeded { lastError = nil }
        if changed { revision += 1 }
        BackgroundRefresh.schedule()
        return allSucceeded
    }
}
