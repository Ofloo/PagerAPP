# PagerAPP — Privacy Policy

_Last updated: 2026-10-16_

PagerAPP is a client for a PagerMCP server. This policy explains what the app does with your
data. In short: **PagerAPP does not collect, transmit, or share any personal data.**

## What the app stores on your device

PagerAPP stores the following locally, in the app's private storage, and nowhere else:

- A **mailbox UUID** that identifies your mailbox on the PagerMCP server you configure.
- Your **settings**: server URL, polling interval, per-status rules, active hours, and
  bypass/silence/watch preferences.
- A **local history** of pages you have received, and a set of already-seen page IDs used to
  avoid showing duplicates.

None of this is uploaded anywhere by PagerAPP. You can erase all of it at any time by clearing
the app's data or uninstalling the app.

## Network connections

PagerAPP communicates **only** with the PagerMCP server address that you enter yourself, over
the connection you configure (for example HTTPS). It uses that connection to create a mailbox,
fetch queued pages, and — only when you tap ACK on a page that opted in — to send an
acknowledgement back. It sends no other data, and no data to the developer or any third party.

## Permissions

- **Internet** — to reach your PagerMCP server.
- **Notifications** — to show pages as notifications.
- **Camera** — used only if you choose to scan a mailbox QR code; the image is processed on
  your device and is never uploaded.
- **Run at startup / foreground service** — to keep polling for pages in the background and to
  resume polling after a reboot.

## Analytics, ads, and tracking

PagerAPP contains **no** analytics, **no** advertising, and **no** tracking SDKs. There is no
account, no sign-in, and no telemetry.

## Children

PagerAPP is a developer/operations tool and is not directed at children.

## Changes

If this policy changes, the updated version will be published at this URL.

## Contact

For questions about this policy, contact the developer at the address listed on the Google Play
store page for PagerAPP.
