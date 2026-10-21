import Combine
import Foundation

/// Small app-level router for notification taps: a page notification opens its detail sheet.
final class AppRouter: ObservableObject {
    static let shared = AppRouter()

    @Published var page: HistoryEntry?

    @MainActor
    func open(pageId: String) {
        page = Store.shared.history().first { $0.id == pageId }
    }
}
