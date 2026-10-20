## Shared

Platform-neutral PagerAPP logic reused by Android and iOS. This is a plain Kotlin/JVM Gradle
module with no Android/iOS SDK imports, so its unit tests run on any JDK (and in Docker).

- `Model.kt` — status model, `StatusRule` (action, vibration, general/custom schedule),
  `ActiveWindow`, `PagerConfig`, `Page`, `Decision`, and the `NotifyAction` / `TimeBound` /
  `WatchMode` / `Channel` / `VibrationPattern` enums.
- `Defaults.kt` — `defaultRuleFor(status)`, the default 0–5 behaviour table, and
  `statusForUrgency` (ProLOG's debug/info/warning/alarm/critical ladder).
- `Decision.kt` — `decide(page, config, now)`: the pure precedence rules (silence > bypass >
  schedule), using the level's custom windows when it does not follow the general schedule.
- `Deduplicator.kt` — at-least-once per message `id` (a repeat is a retry, not a new page).
- `Ack.kt` — `buildAck(page, ownUuid)` for the native `ack_uuid` profile and
  `buildPrologAck(page)` for ProLOG's reply-mailbox `ack <token>` profile.
- `Contact.kt` / `ContactQr.kt` — the contact record and its `pagerapp://contact?…` QR payload.
- `Send.kt` — `buildOutgoing(...)`, `buildOutgoingPhotoChunks(...)`, `contactFor(...)`,
  `conversationWith(...)` for two-way messaging and 1 MB photo chunking.
- `PhotoLink.kt` — the `pagerapp://photo?…` share link for larger photos.
- `PageParser.kt` — reads the fields PagerAPP uses from an already-decoded payload map; accepts
  numeric and textual `status`, maps ProLOG's `[LEVEL]` title and reply-token ACK footer, and
  applies `prio`/urgency to the numeric status.
- `src/test/kotlin/.../LogicTest.kt` — the tests.

Run the tests (canonical): `docker/test-shared.sh`. Do not install a JDK/Gradle on the host.

The Android and iOS apps should consume this logic rather than re-implement it; the platform
layer only supplies the JSON decoder, storage, and the actual notification/vibration APIs.
