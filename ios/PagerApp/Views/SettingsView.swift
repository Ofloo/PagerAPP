import SwiftUI
import UIKit

struct SettingsView: View {
    private let store = Store.shared

    @State private var bypass = Store.shared.bypassMode
    @State private var silence = Store.shared.silenceMode
    @State private var poll = String(Store.shared.pollSeconds)
    @State private var server = Store.shared.serverUrl
    @State private var watchMode = Store.shared.config.watchMode
    @State private var boxes = Store.shared.pageMailboxes
    @State private var newAddressName = ""
    @State private var generating = false
    @State private var addressError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("On-call") {
                    Toggle("Bypass (on-call: show every page)", isOn: Binding(
                        get: { bypass },
                        set: {
                            bypass = $0
                            store.bypassMode = $0
                            if $0 { silence = false; store.silenceMode = false }
                        }
                    ))
                    Toggle("Silence (show nothing, history only)", isOn: Binding(
                        get: { silence },
                        set: {
                            silence = $0
                            store.silenceMode = $0
                            if $0 { bypass = false; store.bypassMode = false }
                        }
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
                        .onChange(of: poll) { value in
                            if let n = Int(value) { store.pollSeconds = n }
                        }
                    Text("Default 60s. iOS background refresh is opportunistic; sub-minute polling only runs while the app is open.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Page addresses") {
                    if let main = store.uuid {
                        LabeledContent("Main", value: main)
                            .font(.system(.caption, design: .monospaced))
                        Button("Copy main UUID") {
                            UIPasteboard.general.string = main
                        }
                    }

                    ForEach(boxes) { box in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(box.name).bold()
                            Text(box.uuid)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                        HStack {
                            Button("Copy") {
                                UIPasteboard.general.string = box.uuid
                            }
                            Spacer()
                            Button("Delete", role: .destructive) {
                                store.removePageMailbox(id: box.id)
                                boxes = store.pageMailboxes
                            }
                        }
                    }

                    TextField("name (e.g. ProLOG, CI)", text: $newAddressName)
                    Button(generating ? "Generating…" : "Generate address") {
                        Task { await generateAddress() }
                    }
                    .disabled(generating)
                    Text("Extra addresses are polled like Main. Generate one per logging source; each UUID is also its bearer token.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let addressError {
                        Text(addressError).foregroundStyle(.red).font(.caption)
                    }
                }

                Section("Server") {
                    TextField("PagerMCP base URL", text: $server)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: server) { value in store.serverUrl = value }
                }

                Section("About") {
                    LabeledContent("version", value: "0.1.105")
                    Text("Pages are fetched while the app is open and on opportunistic iOS background refreshes. Chat, photos and push wake-ups are not part of this first iOS build.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
        }
    }

    private func generateAddress() async {
        generating = true
        addressError = nil
        do {
            let uuid = try await PagerClient.shared.createMailbox(serverUrl: store.serverUrl)
            store.addPageMailbox(name: newAddressName, uuid: uuid, serverUrl: store.serverUrl)
            boxes = store.pageMailboxes
            newAddressName = ""
        } catch {
            addressError = "Could not create address: \(error.localizedDescription)"
        }
        generating = false
    }
}
