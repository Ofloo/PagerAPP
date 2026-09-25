import SwiftUI

/// First-run setup: generate a mailbox or adopt one (paste UUID; QR scan is a TODO on iOS).
struct SetupView: View {
    @AppStorage("pager_uuid") private var uuid: String = ""
    private let store = Store.shared

    @State private var manual = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 16) {
            Text("PagerAPP").font(.largeTitle).bold()
            Text("This device's mailbox UUID is created once and then never changes. " +
                 "Back it up — scripts send pages to it.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(busy ? "Creating…" : "Create a new mailbox") {
                Task {
                    busy = true
                    error = nil
                    do {
                        let new = try await PagerClient.shared.createMailbox(serverUrl: store.serverUrl)
                        store.uuid = new
                        uuid = new
                    } catch {
                        self.error = "Could not create mailbox: \(error.localizedDescription)"
                    }
                    busy = false
                }
            }
            .disabled(busy)

            Text("or").foregroundStyle(.secondary)

            TextField("Paste UUID", text: $manual)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Button("Use this UUID") {
                let value = manual.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !value.isEmpty else { return }
                store.uuid = value
                uuid = value
            }
            .disabled(manual.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            if let uuid = Optional(store.uuid), !uuid.isEmpty {
                Divider()
                Text("Your mailbox UUID").bold()
                Text(uuid).font(.system(.caption, design: .monospaced))
            }

            if let error {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
        }
        .padding(24)
    }
}
