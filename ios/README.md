# PagerAPP for iOS

Swift + SwiftUI. Intended layout (to be created):

- `PagerApp/Views/` — status config, active hours, settings, history
- `PagerApp/Poll/` — Background Tasks polling of `GET /mailboxes/{uuid}/messages`
- `PagerApp/Notify/` — `UNUserNotificationCenter` local notifications
- `PagerApp/Data/` — UUID storage + config (UserDefaults)
- `PagerApp.xcodeproj`

Xcode is macOS-only; builds run on a GitHub Actions `macos-latest` runner (see AGENTS.md).
