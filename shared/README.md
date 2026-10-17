## Shared

Platform-neutral PagerAPP logic reused by Android and iOS. This is a plain Kotlin/JVM Gradle
module with no Android/iOS SDK imports, so its unit tests run on any JDK (and in Docker).

- `Model.kt` — status model, `StatusRule`, `ActiveWindow`, `PagerConfig`, `Page`, `Decision`,
  and the `NotifyAction` / `TimeBound` / `WatchMode` / `Channel` / `VibrationPattern` enums.
- `Defaults.kt` — `defaultRuleFor(status)`, the default 0–5 behaviour table.
- `Decision.kt` — `decide(page, config, now)`: the pure precedence rules (silence > bypass >
  time bound).
- `Deduplicator.kt` — at-least-once per message `id` (a repeat is a retry, not a new page).
- `Ack.kt` — `buildAck(page, ownUuid)`: constructs the ACK `POST /notify` target + payload
  when the page carries an `ack_uuid`; returns null otherwise.
- `Contact.kt` / `ContactQr.kt` — the contact record and its `pagerapp://contact?…` QR payload.
- `Send.kt` — `buildOutgoing(...)`, `contactFor(...)`, `conversationWith(...)` for two-way messaging.
- `PageParser.kt` — reads the fields PagerAPP uses from an already-decoded payload map; accepts
  both numeric and textual `status`.
- `src/test/kotlin/.../LogicTest.kt` — the tests.

Run the tests (canonical): `docker/test-shared.sh`. Do not install a JDK/Gradle on the host.

The Android and iOS apps should consume this logic rather than re-implement it; the platform
layer only supplies the JSON decoder, storage, and the actual notification/vibration APIs.
