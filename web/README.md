# PagerAPP web (Docker)

Stateless browser/PWA console for PagerMCP. The container has no database and no volume; the
mailbox UUID lives in this tab's `sessionStorage`, and transfer sessions live in process memory
with a 30-day lease.

```sh
docker compose -f docker-compose.web.yml up --build
# http://localhost:8088
```

The current web shell supports:
- PagerMCP mailbox connection (server + bearer UUID) through a same-origin proxy.
- Reading queued pages, status/urgency labels and ACK.
- The page immediately opens a pairing QR on load; PagerAPP supplies the mailbox/server. There is
  no manual server or UUID configuration in the web console.
- Creating/renewing/discarding an in-memory browser transfer session QR.
- Dark/light Salt-style console (Bootstrap 5, vendored CSS/JS).
- Session data is never written to disk by the container.

The browser-side PGP keypair, QR handoff from PagerAPP and chat UI are the next phase. Chat media is
never deleted by this web client; only the phone client may remove it after download.
