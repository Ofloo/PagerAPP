# PagerAPP for Android

Kotlin + Jetpack Compose app that polls PagerMCP and raises local notifications.

- `app/src/main/java/io/ofloo/pagerapp/data/` — `Store` (SharedPreferences) + `ConfigCodec`
- `app/src/main/java/io/ofloo/pagerapp/net/PagerClient.kt` — `HttpURLConnection` client for the
  RFC endpoints (`POST /mailboxes`, `GET /mailboxes/{uuid}/messages`, `POST /notify`)
- `app/src/main/java/io/ofloo/pagerapp/poll/PollService.kt` — foreground service, configurable
  interval (30–300s, default 60), wake lock for short intervals
- `app/src/main/java/io/ofloo/pagerapp/notify/` — channels, `Notifier`, `AckReceiver`, `BootReceiver`
- `app/src/main/java/io/ofloo/pagerapp/ui/` — Compose screens (Pages, Rules, Settings, Setup with
  QR scan) plus `MainActivity`

Decision logic is not duplicated here — it comes from `:shared`.

## Build

```
docker/build-android.sh            # assembleDebug -> app/build/outputs/apk/debug/app-debug.apk
docker/build-android.sh testDebugUnitTest
```

The Docker build mounts the **repo root** so `:app` can resolve `:shared` (`settings.gradle.kts`
maps `:shared` to `../shared`). The Gradle wrapper is committed; do not add one.
