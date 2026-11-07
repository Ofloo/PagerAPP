# PagerAPP iOS parity draft

Status: draft, target **Android 0.1.135 / webclient 2026-11-01**  
Scope: bring the iOS app to behavioural parity with Android, not pixel parity.  
Note: this is an implementation draft, not an App Store Connect submission. The App Store draft
needs a macOS runner and App Store Connect credentials; CI can build and test, but publishing is a
separate, explicit step.

## 1. What iOS has today

- First-run mailbox creation, paste/copy of the UUID, primary mailbox.
- Foreground polling and opportunistic `BGAppRefreshTask` / background fetch.
- Page history, local notifications, notification tap to detail, native ACK action.
- ACK removes the page from the queue and reports through `ack_uuid` or the ProLOG reply mailbox.
- Extra page addresses under Settings.
- Rules, active hours, bypass/silence, watch mode setting, poll interval, server URL.
- Swift port of the shared decision/parser/ACK logic in `Models/PagerModels.swift`.
- rPGP bridge in `PgpBridge/` (RSA keygen, encrypt, decrypt), not yet used by the UI.

Not included: contacts, chat, quote replies, receipts, photos/files, share extension, webclient
mode/self-copy sync, P2P, APNs/FCM wake-ups, QR/NFC contact exchange, backup restore, watchOS
companion. The Swift model now carries reply/log fields, but no chat UI uses them.

## 2. Parity matrix

| Area | Android 0.1.184 | iOS today | iOS parity draft |
|---|---|---|---|
| Primary mailbox, page addresses | yes | yes | keep |
| Polling / SSE / background | foreground + FCM/APNs + alarm | foreground + opportunistic BGAppRefresh | add APNs wake-up; keep BGAppRefresh fallback |
| Rules, levels, schedules, bypass/silence | yes | yes | keep, share tests |
| Page notification, ACK, ProLOG ACK | yes | yes | keep |
| Repeat until ACK, per-level sound, channel actions | yes | partial/none | notification actions + repeat scheduling |
| Contacts, two-mailbox channel, QR/NFC | yes | none | contact model, QR scan, channel store |
| Chat send/receive, history, quote replies | yes | none | ChatStore + SwiftUI chat |
| Delivery/read receipts, pending clock | yes | none | receipt envelopes + UI states |
| Ordering by `sent_at`, dedupe by `mid` | yes | none | port ordering/dedupe rules |
| PGP encrypt/sign/verify, signature policy | yes | bridge only | wire bridge into chat and page decrypt |
| Photos/files/camera/share target | yes | none | share links, pickers, share extension |
| Webclient mode leases and self-copies | yes | none | lease renewal before ACK; self-copy handling |
| P2P BLE (have/want/ack, custody) | yes | none | CoreBluetooth transport, foreground first |
| P2P gateway fallback, `server`, `key_id`, 302 | yes | none | port relay policy and settings cache |
| `/settings` protocol policy cache | yes | none | per-server persisted cache, 5 min |
| P2P Telemetry notification | yes (Android FGS) | none | foreground-only telemetry, no permanent service |
| Push wake-ups | FCM/UnifiedPush/ntfy | none | APNs (or FCM->APNs) + server support |
| Backup / mailbox QR / contact QR | yes | none | encrypted backup + QR |
| Watch mode | Wear OS / watchOS setting | setting only | watchOS companion target (later) |
| Share target / browser console | Android share + webclient | none | share extension + webclient leases |
| Localization / theme | yes | partial | align strings/theme |

## 2.1 Current gap against Android 0.1.135

The core page stack is already there. What is still missing for full behavioural parity:

- **Chat and contacts:** contact model/store, two-mailbox channels, QR/NFC invite, compact/expanded
  contact list, sorted contacts, swipe-to-reply with quotes, chat history, read/delivery receipts,
  pending clock, archive/info/delete, chat notification actions.
- **OpenPGP:** wire `PgpBridge` into Swift, with Keychain-held keys, RSA key generation/extension,
  encrypt/decrypt, signature cutoff and pinned-key verification. Android and iOS fixtures must
  round-trip in both directions.
- **Webclient mode:** `/settings` and `/mode` leases for the private mailbox and contacts, ACK guard,
  `acked` read copies, self-copies/back-channel sync, page/read ordering, Clear web sessions.
- **Media:** encrypted photo/file share links, camera and file pickers, download/decrypt, local
  retention rules, iOS Share Extension. The webclient must not delete media links while active.
- **Backup / QR:** encrypted backup export/import and mailbox/main/page QR scanning; main mailbox
  renewal.
- **Push / background:** APNs (or FCM→APNs) registration and lease renewal. PagerMCP currently
  exposes Android-style FCM data messages; iOS needs a server-side APNs route or an FCM-to-APNs
  bridge. BGAppRefresh remains the fallback; it is opportunistic.
- **P2P:** CoreBluetooth transport, envelopes/custody, gateway relay and `/settings` policy cache.
  iOS cannot hold a permanent foreground service, so background P2P latency cannot match Android.
- **Platform polish:** watchOS companion, localization/theme, accessibility, App Store/TestFlight
  signing. Builds need macOS/Xcode or a macOS CI runner.

The page/notification/rules/ACK/page-address core is reusable and should not be rewritten.

## 3. Architecture

- SwiftUI app, one `Store` actor/serial queue around persisted state.
- Secrets (bearer UUID, PGP secret key, backup passphrase) go to **Keychain**, not UserDefaults.
  Non-secret settings/history/contacts stay in a Codable file store under Application Support.
- `PagerClient` becomes async/await and grows: settings, notify outcomes, subscriptions, SSE,
  webclient mode, subscriptions, P2P gateway (no bearer + 302 follow).
- `Poller` keeps the foreground timer and BGAppRefresh, but delegates chat/page handling to the
  same decision/parser code; no status-10 skip once chat exists.
- `ServerSettingsCache`: per-server raw `/settings`, persisted, 5-minute refresh, offline stale
  copy. Used for P2P keys/fingerprints, mesh max hops, SSE keepalive, push leases, webclient TTL.
- `PgpBridge` is wrapped in a Swift `Pgp` type; keys are the same profile as Android so the two
  platforms interoperate. Cross-platform fixtures are added to `ios/PagerAppTests`.
- P2P uses a `P2PTransport` protocol (`start`, `stop`, `queueChanged`, `snapshot`) with a
  CoreBluetooth implementation. The envelope/chunker/assembler/ack/custody logic is a Swift port
  of `shared/P2P.kt`.
- Notifications use `UNNotificationCategory`/actions; the P2P telemetry is a foreground-only
  status (iOS has no perpetual foreground service), presented in-app or on the lock screen while
  the app is active.

## 4. Delivery phases

### Phase 0 — foundation (no UI)
- Keychain migration for secrets.
- Codable file Store: contacts, chat history, outbox, P2P queue, settings cache.
- `/settings` cache and `sent_at`/`mid` ordering rules.
- Swift ports: Contact, buildOutgoing, receipts, P2PEnvelope, chunker, assembler, ack.
- Unit tests for every port; cross-platform PGP fixtures.

### Phase 1 — chat over the gateway
- Contact invite (two-mailbox QR), scan/paste, contact list with compact/expanded card.
- Chat UI: bubbles, day separators, time-only timestamps, quote replies, pending clock,
  delivered/read envelope states, info action, local delete.
- Send via gateway, offline outbox, self-copies while a web session is active.
- Receive chat pages: decrypt, verify signature, dedupe by `mid`, delivery/read receipts.
- Chat levels map to the existing rules.

### Phase 2 — PGP, backup, webclient
- Wire rPGP bridge into pages and chat; pin contact keys + signature cutoff.
- Encrypted backup export/import and mailbox QR.
- Webclient leases: enable/renew before ACK; Clear web sessions; self-copy sync.

### Phase 3 — media and sharing
- Photo/file share links, camera, attachment picker, downloads.
- iOS Share Extension target; Android share-target equivalent.
- Photo/file bubble sizing and metadata; local deletion rules.

### Phase 4 — push and background
- APNs device token registration; decide APNs directly vs FCM relay with an APNs payload.
- Server subscription renewal using `/settings` lease/warn values.
- Keep BGAppRefresh as fallback; measure real delivery latency.
- Notification actions: ACK, open chat, repeat-until-ACK.

### Phase 5 — P2P
- CoreBluetooth peripheral/central GATT, full 64-char route hashes, JSON frames as §13.
- Ownership hash match, contact-inbox first-message learning, custody ACK, delivery receipts.
- Gateway fallback: destination server, destination key/fingerprint, 302 follow, relay grace.
- iOS limitation: no continuous background BLE; P2P runs while the app is active/foreground and
  during short background windows. Do not promise Android's foreground-service latency.
- P2P telemetry as an in-app status when iOS cannot keep a notification service alive.

### Phase 6 — parity hardening
- WatchOS companion (silent vibration), localization/theme, accessibility.
- TestFlight; App Store draft only after device tests and credentials are provided.

## 5. iOS-specific constraints

- **No foreground service:** a permanent BLE/poll service is not allowed. APNs is the only
  reliable background wake-up; BGAppRefresh is opportunistic.
- **BLE background:** CoreBluetooth state restoration can wake the app for a connection, but it
  cannot advertise/scan forever. P2P latency in the background will be worse than Android.
- **Share extension:** a separate target/bundle id with an App Group for the encrypted link data.
- **APNs:** the server currently documents FCM as the iOS path; verify the server can send an
  APNs payload before promising wake-ups. If not, PagerMCP needs an APNs channel (server-side).
- **Keychain:** bearer and private keys must never land in UserDefaults or logs.
- **App Store:** background BLE, notifications and share extension need review; the draft release
  must not claim Android's delivery guarantees.

## 6. Protocol rules to carry over

- `sent_at` is the sender's clock; order chats by it, fall back to server `created_at`.
- Dedupe chat copies by `(mid, from)`; never show receipt-only pages as bubbles.
- A first P2P message on a contact inbox learns the sender id/key and becomes chat, not a page.
- P2P: `to` = full stable `sha256(lowercase(mailbox_uuid))`; ownership is delivery.
- Gateway P2P: destination server + `mailbox_encrypted` to that server's key + `key_id`
  fingerprint; follow 302 up to `mesh.max_hops`; relay fetches the key when the sender could not.
- Relay custody only after the BLE grace, once per process, keep until receipt/ACK.
- Protocol policy comes from `/settings`; personal preferences stay local.
- P2P delivery receipt clears the sender's clock even for a gateway-first duplicate.

## 7. Test plan

- Swift unit tests for all ports (decision, parser, ack, routing, chunker/assembler, receipts,
  settings cache, P2P relay policy).
- Cross-platform PGP fixtures: Android generates a key/message, iOS decrypts/verifies and
  vice versa; same for photo/file links.
- XcodeGen + `xcodebuild test` on `macos-latest` for every PR.
- Device matrix: iPhone (APNs), iPad (layout), two iOS devices on the same LAN for P2P, iOS <->
  Android chat and P2P, background/killed app latency.
- App Store draft: build/upload only after the phases above pass and credentials are available.

## 8. Open decisions

1. APNs directly, or FCM -> APNs? This determines server work and payload shape.
2. Minimum iOS version (currently 16.0) and whether iPad is in the first release.
3. Background P2P expectations: accept foreground-only, or add state-restoration experiments.
4. watchOS companion in the first parity release or a follow-up.
5. App Store draft/TestFlight ownership and credentials.
6. Whether iOS chat should use the same notification channel names/levels as Android (behavior
   yes, presentation native).
