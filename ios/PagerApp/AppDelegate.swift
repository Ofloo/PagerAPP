import BackgroundTasks
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Notifier.registerCategories()
        application.setMinimumBackgroundFetchInterval(UIApplication.backgroundFetchIntervalMinimum)

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: BackgroundRefresh.identifier,
            using: nil
        ) { [weak self] task in
            self?.handle(task)
        }
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        BackgroundRefresh.schedule()
    }

    func application(
        _ application: UIApplication,
        performFetchWithCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Task { @MainActor in
            let ok = await Poller.shared.refreshOnce()
            completionHandler(ok ? .newData : .failed)
        }
    }

    private func handle(_ task: BGTask) {
        BackgroundRefresh.schedule()
        guard let refresh = task as? BGAppRefreshTask else {
            task.setTaskCompleted(success: false)
            return
        }
        let operation = Task { @MainActor in
            let ok = await Poller.shared.refreshOnce()
            refresh.setTaskCompleted(success: ok)
        }
        refresh.expirationHandler = {
            operation.cancel()
        }
    }

    // Show local notifications while the app is in the foreground too, matching Android.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo

        if response.actionIdentifier == Notifier.ackActionId {
            Task { @MainActor in
                if let pageId = info["page_id"] as? String,
                   let entry = Store.shared.history().first(where: { $0.id == pageId }) {
                    _ = await AckService.ack(entry, ownUuid: Store.shared.uuid)
                }
                completionHandler()
            }
            return
        }

        if let pageId = info["page_id"] as? String {
            Task { @MainActor in
                AppRouter.shared.open(pageId: pageId)
            }
        }
        completionHandler()
    }
}
