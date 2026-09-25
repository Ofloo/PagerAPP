import SwiftUI

struct ContentView: View {
    @AppStorage("pager_uuid") private var uuid: String = ""

    var body: some View {
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
}
