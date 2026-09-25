import SwiftUI

struct SettingsView: View {
    private let store = Store.shared

    @State private var bypass = Store.shared.bypassMode
    @State private var silence = Store.shared.silenceMode
    @State private var poll = String(Store.shared.pollSeconds)
    @State private var server = Store.shared.serverUrl
    @State private var watchMode = Store.shared.config.watchMode

    var body: some View {
        Form {
            Section("On-call") {
                Toggle("Bypass (on-call: show every page)", isOn: Binding(
                    get: { bypass },
                    set: { bypass = $0; store.bypassMode = $0; if $0 { silence = false; store.silenceMode = false } }
                ))
                Toggle("Silence (show nothing, history only)", isOn: Binding(
                    get: { silence },
                    set: { silence = $0; store.silenceMode = $0; if $0 { bypass = false; store.bypassMode = false } }
                ))
            }

            Section("Watch") {
                Picker("mode", selection: Binding(
                    get: { watchMode },
                    set: { watchMode = $0; var c = store.config; c.watchMode = $0; store.config = c }
                )) {
                    Text("off (phone only)").tag(WatchMode.off)
                    Text("phone + watch").tag(WatchMode.phoneAndWatch)
                    Text("watch only (silent)").tag(WatchMode.watchOnly)
                }
            }

            Section("Polling") {
                TextField("interval (seconds, 30–300)", text: $poll)
                    .keyboardType(.numberPad)
                    .onChange(of: poll) { _, v in
                        if let n = Int(v) { store.pollSeconds = n }
                    }
                Text("Default 60s. iOS background refresh is opportunistic; " +
                     "sub-minute polling only runs while the app is open.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Server") {
                TextField("PagerMCP base URL", text: $server)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: server) { _, v in store.serverUrl = v }
            }
        }
        .navigationTitle("Settings")
    }
}
