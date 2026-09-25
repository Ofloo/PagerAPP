import Foundation

/// Foreground polling loop. iOS cannot run an unbounded background poll like Android; this runs
/// while the app is active and schedules a BGAppRefreshTask for opportunistic background refreshes.
@MainActor
final class Poller: ObservableObject {
    @Published private(set) var lastError: String?

    private var task: Task<Void, Never>?
    private let store = Store.shared

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.tick()
                let seconds = UInt64(self.store.pollSeconds)
                try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private func tick() async {
        guard let uuid = store.uuid else { return }
        do {
            let pages = try await PagerClient.shared.messages(serverUrl: store.serverUrl, uuid: uuid)
            let seen = store.dedupeSnapshot()
            let fresh = pages.filter { !seen.contains($0.id) }
            guard !fresh.isEmpty else { return }

            var calendar = Calendar.current
            calendar.timeZone = .current
            let now = Date()
            let comps = calendar.dateComponents([.hour, .minute], from: now)
            let minutes = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
            let config = store.effectiveConfig()

            var history: [HistoryEntry] = []
            for page in fresh {
                let decision = decide(page, config, nowMinutes: minutes)
                if decision.notify {
                    let audible = decision.action != .silent && decision.channels.contains(.phone)
                    Notifier.post(page, audible: audible)
                }
                history.append(HistoryEntry.from(page, delivered: decision.notify))
            }

            store.rememberSeen(fresh.map(\.id))
            store.addHistory(history)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }
}
