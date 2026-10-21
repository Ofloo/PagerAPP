import SwiftUI

struct PagesView: View {
    @EnvironmentObject private var poller: Poller
    @State private var entries: [HistoryEntry] = []

    var body: some View {
        NavigationStack {
            List {
                if entries.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "bell.slash")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("No pages yet").bold()
                        Text("Pages fetched from PagerMCP will appear here.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
                }
                ForEach(entries) { entry in
                    NavigationLink {
                        PageDetailView(entry: entry)
                    } label: {
                        row(entry)
                    }
                }
            }
            .navigationTitle("Pages")
            .refreshable { await poller.refreshOnce() }
            .onAppear(perform: reload)
            .onChange(of: poller.revision) { _ in reload() }
        }
    }

    @ViewBuilder
    private func row(_ entry: HistoryEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(entry.jobId ?? entry.project ?? "page").bold()
                Spacer()
                Text(pageStatusLabel(entry.status, entry.prio))
                    .foregroundStyle(pageStatusColor(entry.status))
            }
            Text(entry.message ?? "(no message)")
                .lineLimit(2)
            HStack {
                Text(pageDate(entry.createdAt))
                if !entry.delivered { Text("· history") }
                if entry.acked { Text("· acknowledged") }
                if !(entry.ackUuid ?? "").isEmpty || !(entry.replyUuid ?? "").isEmpty {
                    Text("· ACK available")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func reload() {
        entries = Store.shared.history()
    }
}
