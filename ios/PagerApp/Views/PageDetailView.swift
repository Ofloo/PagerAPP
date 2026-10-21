import SwiftUI

struct PageDetailView: View {
    @State private var entry: HistoryEntry
    @State private var busy = false
    @State private var error: String?

    init(entry: HistoryEntry) {
        _entry = State(initialValue: entry)
    }

    var body: some View {
        Form {
            Section("Page") {
                LabeledContent("job", value: entry.jobId ?? "—")
                LabeledContent("project", value: entry.project ?? "—")
                LabeledContent("status", value: pageStatusLabel(entry.status, entry.prio))
                if !entry.delivered {
                    LabeledContent("delivery", value: "history only")
                }
            }

            Section("Message") {
                Text(entry.message ?? "(no message)")
                if let logs = entry.logs, !logs.isEmpty {
                    DisclosureGroup("Logs") {
                        Text(logs)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                    }
                }
            }

            Section("Timing") {
                LabeledContent("sent", value: pageDate(entry.createdAt))
                LabeledContent("received", value: entry.receivedAt.map { pageDate($0) } ?? "—")
                LabeledContent("message id", value: entry.id)
                if let mailbox = entry.mailbox {
                    LabeledContent("mailbox", value: mailbox)
                }
                if let server = entry.serverUrl {
                    LabeledContent("server", value: server)
                }
            }

            Section("ACK") {
                if entry.acked {
                    Label("Acknowledged", systemImage: "checkmark.circle.fill")
                        .foregroundStyle(.blue)
                } else {
                    Button {
                        Task { await ack() }
                    } label: {
                        if busy {
                            ProgressView()
                        } else {
                            Text("ACK")
                        }
                    }
                    .disabled(busy || entry.mailbox == nil)
                    Text("Removes the page from its queue and reports the ACK when the sender asked for one.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
            }
        }
        .navigationTitle(entry.jobId ?? entry.project ?? "Page")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func ack() async {
        busy = true
        error = nil
        let ok = await AckService.ack(entry, ownUuid: Store.shared.uuid)
        busy = false
        if ok {
            entry.acked = true
        } else {
            error = "ACK failed; the page may already be gone or the server is unreachable."
        }
    }
}
