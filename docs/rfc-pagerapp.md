# RFC-PagerAPP — PagerAPP client data structures

Status: draft · Last updated: 2026-10-16

This document defines the client-side structures PagerAPP uses on top of **PagerMCP**, so that
other applications can produce and consume the same pages, contacts and acknowledgements and
interoperate. PagerMCP itself is an unmodified, opaque relay and is described only as far as a
client needs it.

---

## 1. Transport (PagerMCP recap)

PagerMCP is a mailbox relay. A mailbox is identified by a UUID that is also the bearer credential.

| Purpose | Request |
|---|---|
| Create a mailbox | `POST /mailboxes` → `{ "uuid": "..." }` |
| Send a page | `POST /notify` with `Authorization: Bearer <target-uuid>`, opaque JSON body |
| List queued pages | `GET /mailboxes/<uuid>/messages` with `Authorization: Bearer <uuid>` |
| Acknowledge one page | `POST /mailboxes/<uuid>/ack` with `{"id": "<message-id>"}` (no body ⇒ oldest) |
| Consume oldest | `POST /mailboxes/<uuid>/consume` with `Authorization: Bearer <uuid>` |
| Block until a page | `GET /mailboxes/<uuid>/wait` (optional `max_age_seconds=<n>`, `timeout_seconds=<n>`) |
| Stream pages (push) | `GET /mailboxes/<uuid>/events` — Server-Sent Events, `event: page` |

Notes:
- Delivery is **at-least-once**: `wait`, `events` and `notify` hand a page to a client but do
  **not** remove it from the queue. A page leaves the queue only when the client acknowledges it
  (`POST /ack`) or consumes it. **A client that never acks receives the same page on every
  fetch.** Clients MUST therefore ack each page after processing it, and MUST make processing
  idempotent: a repeated `id` is a retry, not a new event.
- `/wait` and `/events` are **interchangeable transports** for the same messages. SSE is one idle
  connection (`: keepalive` every ~15s) instead of repeated long-poll reconnects, and replays the
  unacknowledged backlog when it (re)connects, so a dropped connection loses nothing. A mobile
  client that only polls on open / pull-to-refresh is equally valid.
- `GET /messages` does not consume; queued pages remain until acked/consumed.
- Limits: request size 64 KiB, 128 messages per mailbox, 7-day message retention, 30-day mailbox
  retention. A full mailbox rejects new pages rather than dropping existing ones.
- The mailbox UUID is a bearer secret. Whoever holds it can both read from and send to that
  mailbox; there is no send-only key.

---

## 2. Page (inbound structure)

`GET /mailboxes/<uuid>/messages` returns `{ "messages": [ ... ] }`. Each message is **flattened**:
the transport fields sit beside the sender's payload fields — there is **no** nested `payload`
object.

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string (UUID) | yes | Unique message id; the deduplication key |
| `created_at` | number (Unix seconds) | yes | When the page was created |
| `age_seconds` | number | yes | Age at list time |
| `status` | number **or** string | yes | Outcome code; see §3 |
| `PROJECT` | string | no | Producing project/system (e.g. `PagerAPP`, `ProLOG`) |
| `JOB_ID` | string | no | Correlates a start and finish page for one job |
| `message` | string | no | Human-readable summary |
| `logs` | string | no | Free text (e.g. tail of a build log) |
| `prio` | string | no | Sender priority hint (not used by the decision logic) |
| `ack_uuid` | string (UUID) | no | Mailbox an acknowledgement may be sent to (§6) |
| `from` | string | no | Sender's **per-channel** id (contact messaging, §5) — never a mailbox UUID |
| `from_name` | string | no | Sender's display name |

Clients MUST surface `age_seconds`/`created_at`; an old queued page is not a fresh event.

---

## 3. Status model

Statuses are **numeric** by design (scriptable, user-defined ranges). Defaults:

| Status | Meaning | Default action | Default time bound |
|---|---|---|---|
| `0` | Success | silent | never notify |
| `1` | Warning | sound + vibrate | active hours |
| `2` | Failed | sound + vibrate | active hours |
| `3` | Critical | urgent | always |
| `4` | In Progress | silent | never notify |
| `5` | Queued | silent | never notify |
| `6` | Acknowledged | silent | never notify |
| `7`–`99` | custom | user-defined | user-defined |

### 3.1 Textual statuses (interoperability)

PagerMCP does not interpret the body, and real senders — including the bundled `pager.sh` wrapper
and other projects — commonly send a **textual** status. Clients MUST accept both forms and map
text as follows (case-insensitive):

| Text | Numeric |
|---|---|
| `success`, `ok`, `succeeded`, `done`, `completed`, `complete` | 0 |
| `warning`, `warn` | 1 |
| `failed`, `failure`, `fail`, `error` | 2 |
| `critical`, `fatal`, `urgent` | 3 |
| `in progress`, `in_progress`, `running`, `started` | 4 |
| `queued`, `pending` | 5 |
| `acknowledged`, `ack` | 6 |
| any other non-numeric text | 1 (surfaced as a Warning, never dropped) |

A numeric string (e.g. `"3"`) is parsed as its number. A page that cannot be parsed at all
(missing `id`, missing status) SHOULD be logged and skipped rather than crash the client.

---

## 4. Decision model (what to do with a page)

Pure, local, device-time logic. Given a page, the user config and the current device **local**
time, the client decides whether to notify and how.

Per status the user configures a `StatusRule`:

| Field | Values |
|---|---|
| `action` | `silent` · `sound+vibrate` · `urgent` |
| `timeBound` | `always` · `active_hours` · `never` |
| `vibration` | `short` · `long` · `repeated` (watch only) |

Global config:

| Field | Meaning |
|---|---|
| `activeWindows` | list of local-time windows, default `07:00–23:00`. A window whose end is not after its start wraps midnight (e.g. `22:00–06:00`) |
| `bypassMode` | on-call: show every page regardless of `timeBound` |
| `silenceMode` | show nothing; history only |
| `watchMode` | `off` · `phone+watch` · `watch_only` |

Precedence (highest first):

1. **Silence** wins over everything — nothing is shown.
2. **Bypass** overrides the per-status time bound — every page is shown.
3. Otherwise the status rule's time bound decides.

Active-hours evaluation uses **device local time**, never server time. Watch delivery uses the
platform companion link, not a push backend.

---

## 5. Contacts and message exchange

A *contact* is a person you talk to over a **dedicated channel mailbox**.

**The private mailbox is never shared.** The mailbox you poll for your own systems' pages (CI,
alerts, `pager.sh`) is your credential to everything you receive; handing it out would give that
person read access to all of it. Instead each relationship gets its own channel: a fresh mailbox
that only the two of you know. Throwing a spammed channel away is simply dropping the contact and
issuing a new invite.

```
Contact {
  name:       string   // display label
  server:     string   // PagerMCP base URL
  uuid:       string   // the CHANNEL mailbox (shared by both sides)
  senderId:   string   // the peer's per-channel sender id
  mySenderId: string   // our per-channel sender id
}
// local identity key: server + "|" + uuid
```

`senderId`/`mySenderId` are random ids generated per channel. They exist so messages can be
attributed **without ever putting a mailbox UUID in a payload** — a `from` of your private mailbox
would leak it to the peer.

### 5.1 Contact QR payload

```
pagerapp://contact?u=<channel>&s=<server>&n=<name>&i=<my-sender-id>
```

- `u` is the inviter's **channel** mailbox (never their private mailbox); `i` is the inviter's
  per-channel sender id. `n` and `i` may be omitted.
- Values are percent-encoded (unreserved `A–Z a–z 0–9 - _ . ~`; everything else `%XX` of the
  UTF-8 bytes).
- Decoders MUST also accept a **bare UUID** (server taken from local config) or a URL whose last
  path segment is the UUID, and MUST reject input with no usable UUID.
- The scanner generates its **own** fresh `mySenderId` for the new channel.

### 5.2 Sending a message

An ordinary `POST /notify` to the **channel** UUID (which is its own bearer):

```json
{
  "status": 10,
  "message": "hello",
  "from": "<my per-channel sender id>",
  "from_name": "My display name"
}
```

- `status` `10` is reserved for a plain chat message; its default rule is sound + vibrate during
  active hours (a message should reach you), and the user may override it like any status.
- `from` MUST be the sender's per-channel id, never a mailbox UUID.

### 5.3 Acknowledging on a shared channel

Both sides read the same mailbox, so **only the receiver acks**: ack a page only when
`page.from != contact.mySenderId`. Acking your own message would remove it before the peer ever
sees it.

On the peer's first message, the recipient learns `senderId` (and may take `from_name`) and stores
it on the contact; until then the contact is marked "awaiting reply".

A receiver groups a channel's pages by comparing `page.from` with `senderId` (theirs) and
`mySenderId` (ours), ordered by `created_at`.

---

## 6. Acknowledgement

An ACK is an ordinary `POST /notify` to the page's `ack_uuid` (that UUID is the bearer), and is
**opt-in per page** — it exists only when the page carries an `ack_uuid`.

```json
{
  "PROJECT": "PagerAPP",
  "status": 6,
  "message": "Acknowledged",
  "acknowledged_by": "<the acknowledger's own mailbox uuid>",
  "JOB_ID": "<original JOB_ID, when present>",
  "in_reply_to": "<the acknowledged page id>"
}
```

`acknowledged_by` lets the origin correlate and stop escalating. ACK is never automatic.

---

## 7. Compatibility and versioning

- The QR scheme carries no explicit version. Adding an optional query parameter is
  backward-compatible (unknown parameters MUST be ignored).
- Status semantics for `0`–`6` are fixed; `7`–`99` are user-defined. A client that meets an
  unknown numeric status SHOULD apply the "custom, undefined" default (silent, never).
- Unknown JSON fields in a page MUST be ignored; clients read only the fields in §2.
- `from`/`from_name`/`ack_uuid` are additive: a client that ignores them simply sees ordinary
  pages.

---

## 8. Worked examples

Inbound text-status page (as sent by `pager.sh`):

```json
{
  "id": "b18c0cb1-d29f-48bd-a2d4-48fb17f0d88a",
  "created_at": 1792174462.149,
  "age_seconds": 12,
  "status": "success",
  "PROJECT": "ProLOG",
  "JOB_ID": "prolog-test",
  "message": "ProLOG testmelding",
  "logs": "Dit is een testmelding vanuit ProLOG."
}
```

→ status maps to `0` (Success) → recorded in history, not notified.

Two-way chat message on a shared channel:

```json
{
  "status": 10,
  "message": "ben je er?",
  "from": "b7c1f0d2-…",
  "from_name": "Ofloo"
}
```

Contact invite QR (a fresh channel plus the inviter's per-channel id):

```
pagerapp://contact?u=9d01837b-ecdc-4aad-bcaa-6e82daf75f51&s=https%3A%2F%2Fpager.ofloo.io&n=Ofloo&i=b7c1f0d2-…
```
