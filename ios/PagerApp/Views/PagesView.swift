import SwiftUI

struct PagesView: View {
    @State private var entries: [HistoryEntry] = []

    var body: some View {
        NavigationStack {
            List(entries) { entry in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(entry.jobId ?? entry.project ?? "page").bold()
                        Spacer()
                        Text(statusLabel(entry.status))
                            .foregroundStyle(statusColor(entry.status))
                    }
                    Text(entry.message ?? "(no message)")
                    Text(subtitle(entry))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Pages")
            .onAppear { entries = Store.shared.history() }
        }
    }

    private func subtitle(_ entry: HistoryEntry) -> String {
        var parts = ["status \(entry.status)"]
        if !(entry.ackUuid ?? "").isEmpty { parts.append("ACK available") }
        if !entry.delivered { parts.append("not notified") }
        return parts.joined(separator: " · ")
    }

    private func statusLabel(_ status: Int) -> String {
        switch status {
        case 0: return "0 Success"
        case 1: return "1 Warning"
        case 2: return "2 Failed"
        case 3: return "3 Critical"
        case 4: return "4 In Progress"
        case 5: return "5 Queued"
        case 6: return "6 Acknowledged"
        default: return "\(status)"
        }
    }

    private func statusColor(_ status: Int) -> Color {
        switch status {
        case 2, 3: return .red
        case 6: return .blue
        default: return .secondary
        }
    }
}
