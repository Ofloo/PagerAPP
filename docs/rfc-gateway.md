# RFC-PagerAPP-0003 — Gateway / email message construction

Status: draft · Last updated: 2026-10-25

This document is for a **sender-side gateway** (for example an SMTP-to-PagerMCP bridge) that needs
to turn a mail message into a PagerAPP page. It is a profile on top of
[RFC-PagerAPP-0002](rfc-pages.md), which remains authoritative for levels and acknowledgements.

PagerMCP is an opaque relay. It accepts the JSON body of `POST /notify` and returns that body
flattened beside its transport fields. PagerAPP reads the flattened fields; it has no email
headers, MIME parser or `payload` object.

---

## 1. The one request that matters

```bash
curl -fsS -X POST 'https://pager.ofloo.io/notify' \
  -H 'Authorization: Bearer <recipient-mailbox-uuid>' \
  -H 'Content-Type: application/json' \
  --data-binary @- <<'JSON'
{
  "status": 1,
  "PROJECT": "Mail gateway",
  "JOB_ID": "<mail Message-ID>",
  "message": "<mail subject>",
  "logs": "<plain-text mail body>"
}
JSON
```

- The bearer token is the **recipient mailbox UUID**, not the sender's mailbox.
- The request body MUST be one JSON object. Do not send MIME, form-encoded data, a JSON array or a
  raw email body.
- `message` is the field PagerAPP displays as the page title. If it is missing or empty the page
  has no readable summary, even though it is queued.
- `logs` is optional detail shown in the page detail view.
- PagerMCP adds `id`, `created_at` and `age_seconds` when it stores the page. Do not try to supply
  those fields yourself.

---

## 2. Canonical page fields

| Field | Type | Required | Gateway meaning |
|---|---|---|---|
| `status` | number or string | recommended | Outcome: `1` warning (normal mail), `2` failed, `3` critical. Missing currently falls back to warning. |
| `PROJECT` | string | recommended | Producer, e.g. `Mail gateway`, `CI`, `ProLOG`. |
| `JOB_ID` | string | optional | Correlation id, ideally the mail `Message-ID`. |
| `message` | string | **yes for readable text** | Human-readable summary, normally the subject. |
| `logs` | string | optional | Plain-text body or a short excerpt. |
| `prio` | string | optional | Urgency: `debug`, `info`, `warning`, `alarm`, `critical`; wins over `status` for notification level. |
| `ack_uuid` | UUID string | no | Only for native PagerAPP ACK; omit for ordinary mail. |

A sender MUST use one ACK profile per page; mail gateways normally use **no ACK profile**. Do not
invent `ack_uuid`, `reply_uuid` or `ack_token` unless you implement the corresponding reply flow.

Unknown fields are ignored. The complete 64 KiB request limit applies to the entire JSON body, so
truncate large mail bodies to a short plain-text excerpt in `logs`.

---

## 3. Recommended email mapping

| Email field | Page field | Notes |
|---|---|---|
| Subject | `message` | Keep it short; this is the notification title. |
| Plain-text body | `logs` | Strip quoted history and signatures if desired. |
| `Message-ID` | `JOB_ID` | Useful for correlating follow-ups. |
| From / envelope sender | `PROJECT` or leave as part of `logs` | `from` is reserved for chat sender ids; do not use it for email addresses. |
| Importance / X-Priority | `prio` or `status` | Map urgent to `critical`/`3`, normal to `warning`/`1`. |
| HTML body | converted plain text | Do not send raw HTML as `message`; it renders as tags. |

Recommended minimal mapping:

```json
{
  "status": 1,
  "PROJECT": "Mail gateway",
  "JOB_ID": "<Message-ID>",
  "message": "<Subject>",
  "logs": "<plain-text body>"
}
```

If the subject is empty, use a non-empty body excerpt as `message`; otherwise the client shows
an empty page.

---

## 4. Compatibility aliases

PagerAPP 0.1.131+ accepts these aliases to make simple gateways less brittle:

| Canonical | Accepted fallbacks |
|---|---|
| `message` | `subject`, then `body` |
| `logs` | `body`, `text`, `content` (only when `message` was absent) |
| `PROJECT` | `project` |
| `JOB_ID` | `job_id`, `job` |

`message` remains authoritative: if it is present, it is not replaced by an alias. Aliases are a
compatibility aid, not the preferred wire format. In particular, do not rely on `from` for mail
metadata: it is the chat sender id and has different semantics.

The parser treats blank strings as absent. A missing `status` is treated as warning (`1`).

---

## 5. Why a page can arrive with empty text

Check these in order:

1. **Wrong field name.** The page has `body`/`text`/`subject` but no `message`; old clients show
   empty text. Map the subject to `message` explicitly.
2. **Nested payload.** `{"payload":{"message":"..."}}` is not flattened by PagerAPP. Put sender
   fields at the top level.
3. **Raw email or MIME.** `text/plain` mail content is not JSON; PagerMCP queues it, but PagerAPP
   drops pages it cannot parse as JSON.
4. **Empty `message`.** A subject-only mail with an empty subject and no body has nothing to show.
5. **HTML body.** If the plain-text part is empty and only HTML remains, convert it to text or set
   a non-empty `message`.
6. **Oversized body.** PagerMCP rejects bodies over 64 KiB; the gateway must truncate/summarize.
7. **Malformed JSON.** A trailing comma or unescaped newline produces no usable page.

Inspect the queue exactly as the client does:

```bash
curl -fsS 'https://pager.ofloo.io/mailboxes/<uuid>/messages?order=newest' \
  -H 'Authorization: Bearer <uuid>'
```

Each returned message should contain the sender fields **beside** `id`, `created_at` and
`age_seconds`:

```json
{
  "id": "b0f15e15-…",
  "created_at": 1792931675.05,
  "age_seconds": 1,
  "status": 1,
  "PROJECT": "Mail gateway",
  "JOB_ID": "<mail@example>",
  "message": "Deployment failed",
  "logs": "The deployment job exited with status 2."
}
```

If `message` is absent there, the gateway has not sent it; the client cannot recover it.

---

## 6. Minimal Python gateway example

```python
import json
import urllib.request

def page(uuid: str, subject: str, body: str, message_id: str) -> None:
    payload = {
        "status": 1,
        "PROJECT": "Mail gateway",
        "JOB_ID": message_id,
        "message": subject.strip() or body.strip()[:120] or "(no subject)",
        "logs": body[:8000],
    }
    request = urllib.request.Request(
        "https://pager.ofloo.io/notify",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {uuid}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        response.read()
```

Use the recipient mailbox UUID from PagerAPP. Never put the UUID in logs or a public URL.

---

## 7. Limits and ACK

- 64 KiB per message, 128 messages per mailbox, 7-day message retention, 30-day mailbox retention.
- Delivery is at-least-once; clients deduplicate by `id`.
- Ordinary mail should **not** request an ACK. If a mail must be acknowledged, implement the native
  profile in [RFC-PagerAPP-0002 §6](rfc-pages.md): put the origin mailbox in `ack_uuid` and expect a
  separate `status: 6` page back. That is a capability handoff, so only do it when intended.
