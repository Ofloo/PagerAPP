import SwiftUI

@main
struct PagerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var poller = Poller.shared
    @StateObject private var router = AppRouter.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(poller)
                .environmentObject(router)
                .task {
                    await Notifier.requestAuthorization()
                    poller.start()
                }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                poller.start()
            } else {
                poller.stop()
                BackgroundRefresh.schedule()
            }
        }
    }
}
