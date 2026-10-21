import BackgroundTasks
import Foundation

enum BackgroundRefresh {
    static let identifier = "io.ofloo.pagerapp.refresh"

    /// Ask iOS for an opportunistic refresh after the configured poll interval. iOS chooses the
    /// actual time; there is no guaranteed background interval.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(
            timeIntervalSinceNow: TimeInterval(max(Store.shared.pollSeconds, 60))
        )
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Simulators and devices in restricted states can reject background requests.
            print("PagerAPP: background refresh not scheduled: \(error.localizedDescription)")
        }
    }
}
