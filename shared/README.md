# Shared

Platform-neutral PagerAPP logic intended to be reused by Android and iOS.

Candidates (to be created):

- `status.*` — the numeric status model and default action/time-bound map
- `page.*` — parsing of the opaque PagerMCP page payload (`status`, `PROJECT`, `JOB_ID`,
  `message`, `logs`, `prio`)
- `config.*` — serialization of user config (per-status actions, active hours, bypass/silence,
  watch mode, polling interval)
- `decision.*` — pure function: given (page, config, local time, watch state) return the
  notification action. This is the piece with the most edge cases and the best unit-test target.

Keep shared code free of Android/iOS SDK imports so both apps can consume it.
