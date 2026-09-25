# PagerAPP for iOS

Swift + SwiftUI port of the Android app. Polls PagerMCP while foreground and raises local
notifications via `UNUserNotificationCenter`.

- `PagerApp/Models/PagerModels.swift` — port of `shared/`: `Page`, `PagerConfig`,
  `defaultRuleFor`, `decide`, `Deduplicator`, `buildAck`, `PageParser`. Keep in sync with
  `shared/` (the Kotlin module is the source of truth and holds the JVM tests).
- `PagerApp/Data/Store.swift` — `UserDefaults` persistence (UUID, config, dedupe, history)
- `PagerApp/Net/PagerClient.swift` — async/await URLSession client
- `PagerApp/Poll/Poller.swift` — foreground polling loop
- `PagerApp/Notify/Notifier.swift` — notification category + ACK action
- `PagerApp/Views/` — Setup, Pages, Rules, Settings
- `PagerAppTests/LogicTests.swift` — XCTest mirror of the shared logic tests

## Build / test (macOS only)

There is **no committed `.xcodeproj`** — generate it with XcodeGen:

```
brew install xcodegen
cd ios && xcodegen generate
xcodebuild test -project PagerApp.xcodeproj -scheme PagerApp \
  -destination 'platform=iOS Simulator,name=iPhone 15'
```

CI runs exactly this on a `macos-latest` runner (`.github/workflows/ios.yml`). It cannot run on
the Ubuntu host. Known gap: QR scanning on iOS is still a TODO (the Android app has it).
