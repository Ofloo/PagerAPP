import SwiftUI

@main
struct PagerApp: App {
    @StateObject private var poller = Poller()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(poller)
                .task {
                    Notifier.registerCategories()
                    await Notifier.requestAuthorization()
                    poller.start()
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { poller.start() } else { poller.stop() }
        }
    }
}
