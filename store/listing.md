# PagerAPP — Play Store listing

Privacy policy URL: https://pager.ofloo.io/privacy

## App name (max 30)
PagerAPP

## Short description (max 80)
Turn PagerMCP pages into phone notifications, with rules, active hours and ACK.

## Full description (max 4000)
PagerAPP turns pages from your own PagerMCP server into native phone notifications.

It polls a PagerMCP mailbox that you control and raises a local notification when a page
arrives — like a pager, but on your phone. No push backend, no account, no cloud: the app
talks only to the PagerMCP server you configure.

FEATURES

• Per-status rules — decide what each numeric status does: silent, sound + vibrate, or urgent.
• Active hours — let lower-priority statuses stay quiet outside a time window you choose
  (device local time).
• On-call bypass — show every page regardless of rules, for when you are on call.
• Silence — show nothing, keep history only.
• ACK — when a page opts in, acknowledge it straight from the notification so the sender
  can stop escalating.
• Configurable interval — poll every 30 seconds to 5 minutes.
• Watch mode — optional silent vibration on a paired smartwatch.
• Runs in the background — a foreground service keeps polling while you use your phone.

HOW IT WORKS

PagerAPP creates a mailbox UUID on the PagerMCP server you configure. Scripts and CI jobs send
pages to that UUID. PagerAPP polls the mailbox and notifies you. The UUID is stored only on your
device and is yours to back up (QR code or copy it out); the app never uploads it anywhere.

PRIVACY

PagerAPP collects no personal data, has no analytics and no ads, and sends nothing anywhere
except to the PagerMCP server address that you enter yourself. See the privacy policy.

NOTE

PagerAPP is a client for PagerMCP. It works with any PagerMCP server; you provide the server URL.
