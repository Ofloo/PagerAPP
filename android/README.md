# PagerAPP for Android

Kotlin + Jetpack Compose. Intended layout (to be created):

- `app/src/main/java/.../ui/` — Compose screens (status config, active hours, settings, history)
- `app/src/main/java/.../poll/` — `WorkManager` polling worker for
  `GET /mailboxes/{uuid}/messages`
- `app/src/main/java/.../notify/` — local `NotificationManager` notifications
- `app/src/main/java/.../data/` — UUID storage + config (SharedPreferences)
- `app/src/main/AndroidManifest.xml`

Build from the host with `docker/build-android.sh` (see AGENTS.md).
