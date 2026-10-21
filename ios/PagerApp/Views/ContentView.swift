import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var router: AppRouter
    @AppStorage("pager_uuid") private var uuid: String = ""

    var body: some View {
        Group {
            if uuid.isEmpty {
                SetupView()
            } else {
                TabView {
                    PagesView().tabItem { Label("Pages", systemImage: "bell") }
                    RulesView().tabItem { Label("Rules", systemImage: "slider.horizontal.3") }
                    SettingsView().tabItem { Label("Settings", systemImage: "gear") }
                }
            }
        }
        .sheet(item: $router.page) { entry in
            NavigationStack {
                PageDetailView(entry: entry)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Close") { router.page = nil }
                        }
                    }
            }
        }
    }
}
