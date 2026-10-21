# PagerAPP for iOS

SwiftUI counterpart of the Android app. This first iOS build covers the **page/notification
core**; chat, photos, sharing and push wake-ups are deliberately not in it yet.

## Included

- First-run mailbox creation, paste/copy of the UUID, and the primary mailbox.
- Polling while the app is active, plus opportunistic `BGAppRefreshTask` / background fetch.
- Pages history with server `created_at`/`received_at`, status/urgency, id, mailbox and server.
- Local notifications, notification tap → page detail, and the **ACK** notification action.
- ACK removes the page from its mailbox queue and reports through `ack_uuid` or the ProLOG
  reply-mailbox/token profile.
- Additional **Page addresses** (separate PagerMCP mailboxes) under Settings; the poller and
  notifications cover all of them.
- Rules (status action and time bound), bypass/silence, watch mode, poll interval and server URL.
- Same Swift port of the shared decision logic in `PagerApp/Models/PagerModels.swift` (keep it in
  sync with `shared/`, which remains the source of truth and holds the JVM tests).

## Not yet on iOS

- Two-way chat/read receipts, photo/attachment share links, camera and Android share target.
- Push wake-ups (APNs/FCM); iOS background refresh is opportunistic, so delivery is not as fast
  as Android's foreground service.
- QR scanning of a mailbox/contact link.

## Build / test (macOS only)

There is **no committed `.xcodeproj`** — generate it with XcodeGen:

```
brew install xcodegen
cd ios && xcodegen generate
xcodebuild test -project PagerApp.xcodeproj -scheme PagerApp \
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

CI runs the same on a `macos-latest` runner (`.github/workflows/ios.yml`). It cannot run on the
Ubuntu host. The target is universal (`TARGETED_DEVICE_FAMILY = 1,2`), so an iPad running iPadOS
16+ is a valid test device; building still needs macOS/Xcode or a macOS CI runner.

The app uses `BGTaskScheduler` identifier `io.ofloo.pagerapp.refresh` (declared in
`PagerApp/Info.plist` and `project.yml`). The bundle id is `io.ofloo.pagerapp`, version
**0.1.105** (build 105).
