# RFC-PagerAPP — PagerAPP client data structures

Status: draft · Last updated: 2026-10-16

This document defines the client-side structures PagerAPP uses on top of **PagerMCP**, so that
other applications can produce and consume the same pages, contacts and acknowledgements and
interoperate. PagerMCP itself is an unmodified, opaque relay and is described only as far as a
client needs it.

**Roles.** ProLOG is a product that can optionally send pages through PagerMCP. PagerMCP is the
generic mailbox relay. PagerAPP is a client extension on top of PagerMCP for system alarms and
chat; it supports the ProLOG page profile as an optional compatibility path, but neither PagerMCP
nor PagerAPP requires ProLOG. The components are designed to complement one another, while each
one remains generic and independently usable.

Page semantics — the level table and the exact fields that request an acknowledgement versus a
plain page — are defined in [RFC-PagerAPP-0002](rfc-pages.md), which is authoritative for §3 and §6
below.

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
| `prio` | string | no | Urgency level (`debug`, `info`, `warning`, `alarm`, `critical`); see RFC-0002 §4.2 |
| `ack_uuid` | string (UUID) | no | Mailbox an acknowledgement may be sent to (§6) |
| `from` | string | no | Sender's **per-channel** id (contact messaging, §5) — never a mailbox UUID |
| `from_name` | string | no | Sender's display name |
| `pub` | string | no | Sender's armored OpenPGP public key (§8) |
| `enc` | string | no | Message body encrypted to the recipient's public key (§8) |
| `mid` | string | no | Client-side id of the message, for read receipts (§7) |
| `read` | string | no | Receipt-only page: the `mid` the sender has just read (§7) |

Clients MUST surface `age_seconds`/`created_at`; an old queued page is not a fresh event.

---

## 3. Status model

Statuses are **numeric** by design (scriptable, user-defined ranges). An optional `prio` field
carries the five-level urgency ladder used by ProLOG (`debug < info < warning < alarm < critical`)
and takes precedence for the notification decision; the table below is the fallback when it is
absent. See [RFC-PagerAPP-0002 §4](rfc-pages.md) for the full model. Defaults:

| Status | Meaning | Default action | Default time bound |
|---|---|---|---|
| `0` | Success | silent | never notify |
| `1` | Warning | sound + vibrate | active hours |
| `2` | Failed | sound + vibrate | active hours |
| `3` | Critical | urgent | always |
| `4` | In Progress | silent | never notify |
| `5` | Queued | silent | never notify |
| `6` | Acknowledged | silent | never notify |
| `7`–`9` | custom | silent | never notify |
| `10` | Chat message (reserved) | sound + vibrate | active hours |
| `11`–`99` | custom | silent | never notify |

A page on an unconfigured custom level is still recorded and shown in history. See
[RFC-PagerAPP-0002 §4](rfc-pages.md) for the full level and decision rules.

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
  publicKey:  string   // the peer's armored OpenPGP key, learned automatically (§8)
  kind:       PERSON | SYSTEM   // chat partner, or alarm source (§5.4)
  level:      string   // which configured chat level this chat uses (§5.5)
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
- The same payload travels over **NFC**: written to a tag as an NDEF record of MIME type
  `application/io.ofloo.pagerapp.contact`, with the payload as the record body. A client should
  also accept a plain NDEF Text record, and declare an `NDEF_DISCOVERED` intent filter for that
  MIME type so a tap opens the app even when it is closed.

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

### 5.4 Contact kind

A contact is one of two things, and the kind decides how the client behaves:

- **PERSON** — a chat. Opening it shows the conversation; reading *is* the acknowledgement, so a
  person chat has **no manual ACK button** (the client acks inbound channel pages as it fetches
  them, and marks them read). Read receipts (§7) apply.
- **SYSTEM** — an alarm source. Messages stay in the notification list and offer a **manual ACK**
  (with an optional note): it removes the page from the queue and, when the page carries an
  `ack_uuid`, reports the acknowledgement back so the operator knows it was really seen.

### 5.5 Chat levels

The notification level of a chat is a name stored on the contact, resolved against the levels in
the client's configuration:

```
config.chatLevels = { "<name>" -> { action: silent | sound+vibrate | urgent,
                                    timeBound: always | active_hours | never } }
```

Defaults: `Important` (urgent, always), `Normal` (sound+vibrate, active hours), `Silent` (never).
Because the contact stores only the name, retuning a level changes it for every chat using it.

"Always" means *this app* ignores its own active hours. It never overrides the phone's silent
mode or Do Not Disturb; clients MUST NOT bypass those.

---

## 6. Acknowledgement

An ACK has two independent effects, and clients must keep them apart:

1. **Queue confirmation** — `POST /mailboxes/<recipient>/ack` with `{"id":"<page-id>"}` removes
   the page from the recipient's mailbox. This is transport hygiene and is possible for **every**
   page, with or without an `ack_uuid`.
2. **ACK report** — an ordinary `POST /notify` to the page's `ack_uuid` (that UUID is the bearer),
   which tells the origin a human saw the page. This is **opt-in per page** and exists only when
   the page carries an `ack_uuid`.

A page that is delivered to the user stays queued until the user confirms it; the client then
removes it and, when `ack_uuid` is present, sends the report below. A page that the rules keep
silent is history only and is confirmed immediately without a report. ACK reports are never
automatic for pages. ProLOG pages use a different, sender-managed ACK (a reply mailbox plus an
`ack <token>` message); see [RFC-PagerAPP-0002 §6.4](rfc-pages.md).

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

## 7. Read receipts

An ACK (§6) is transport: it removes a page from the server queue. A **read receipt** is social:
it tells the peer their message was actually seen, and drives the ✓ / ✓✓ marks in a chat.

- An outgoing chat message carries a client-generated id in `mid`.
- When the recipient opens (reads) it, the client sends a receipt-only page back to the same
  channel: `{ "status": 10, "from": "<my sender id>", "read": "<mid>" }`. A receipt carries no
  body and is not itself a message.
- The original sender matches `read` against the `mid` it stored and marks that message read.
- Receipts are for **person** contacts. System contacts use the manual ACK instead (§5.4).

## 8. Encryption

Message bodies can be end-to-end encrypted with OpenPGP; PagerMCP stays a dumb relay and never
sees the plaintext.

- A client generates a key ring on first use: a signing primary key plus an encryption subkey,
  **expiring between 6 and 12 months**. The expiry is part of the self-signature, so `gpg` sees it.
- Every outgoing message carries the sender's public key in `pub`, so the peer learns it on the
  **first message** with no separate exchange step — and again whenever the key is rotated.
- Once the peer's key is known, the body is sent as `enc`, encrypted to it (and signed). Until
  then the body travels as plain `message`, with `pub` attached so the peer can answer encrypted.
- A client that cannot decrypt a page must surface that plainly rather than showing ciphertext.
- An expired key is replaced and the replacement propagates the same way; a peer that stops
  receiving a usable key eventually sees plaintext again rather than silence.

Only the body is encrypted. The transport fields PagerMCP itself needs (`id`, `status`, `from`,
`mid`, `read`) stay in the clear.

### 8.1 Key lifecycle

Keys expire — 6 to 12 months — and there are two ways to deal with that. They are **not** the same
operation and clients must not conflate them:

- **Extend** (default). Re-sign the *same* key with a later `KeyExpirationTime`. The fingerprint
  does not change, so contacts do not have to relearn anything and no "key changed" warning is
  raised. Nothing is re-encrypted.
- **Renew** (deliberate). Generate a new key. The fingerprint changes, every contact has to adopt
  the new key, and anything encrypted to the old one has to be dealt with first.

Renewing runs in a fixed order so the user can never lock themselves out:

1. **Generate** the new key.
2. **Announce** it: send a key-only message (`pub`, no body) to **every** contact channel. Do not
   wait for the next chat message — distribution must be deterministic.
3. **Final sweep**: fetch every mailbox once more and decrypt anything still addressed to the old
   key, so nothing in flight is lost.
4. **Re-encrypt** whatever was stored under the old key (only necessary if the archive is kept
   under the OpenPGP key; an archive held under a device key is already immune). Do it only after
   step 3 succeeded.
5. **Discard** the old key, only after 3 and 4 have completed.

The steps are journalled: if the app is stopped or the network fails half-way, the client resumes
where it left off rather than stranding a half-migrated archive.

**Key updates are automatic on both sides.** A client learns the peer's key from the `pub` field of
any message it receives, so a contact never has to accept or confirm anything. Extending keeps the
same key; renewing changes it, which a client should surface as *"their key changed"* — with the
fingerprint shown for a one-time comparison — rather than silently accepting it.

### 8.2 Unreadable messages

A message encrypted to a key the client no longer holds must be marked as unreadable (never shown
as ciphertext), and the client then **automatically** sends a short notice back on the channel:
"your message could not be read", carrying its own current `pub`. The sender learns the new key
from that notice and may **choose** to re-send — the text is still in its history, so this is one
action. Resending after the automatic key update means the retry is encrypted to the current key
and arrives readable.

Repeated unreadable notices are a signal that key distribution is not working for that contact
(for instance, one side has been offline throughout), so they are surfaced rather than hidden.

### 8.3 Rotation policy and crypto agility

Modulus size and algorithm are **policy**, not constants: the key lifetime is the moment to change
them. A client may double the modulus on a schedule (for example every five years) or move to a
different algorithm, because every key expires and is re-issued anyway.

Note what this does and does not buy: against a classical attacker, 2048 bits is already
infeasible, and raising it further costs generation time without adding practical security. Against
a future quantum computer, no RSA size helps — Shor's algorithm scales with qubits, not with the
modulus.

For a long horizon — messages stored today and decrypted years later — RSA is therefore the wrong
foundation, and clients should move to a **hybrid post-quantum** layer: per-message X25519 +
ML-KEM key agreement feeding AES-256-GCM, with the OpenPGP body nested inside so `gpg` can still
read the inner layer. The payload reserves the room for it: an algorithm/version marker travels
with the message, so old and new messages stay readable side by side and a migration never breaks
the format.

---

## 9. Compatibility and versioning

- The QR scheme carries no explicit version. Adding an optional query parameter is
  backward-compatible (unknown parameters MUST be ignored).
- Status semantics for `0`–`6` are fixed; `7`–`99` are user-defined. A client that meets an
  unknown numeric status SHOULD apply the "custom, undefined" default (silent, never).
- Unknown JSON fields in a page MUST be ignored; clients read only the fields in §2.
- `from`/`from_name`/`ack_uuid`/`pub`/`enc`/`mid`/`read` are additive: a client that ignores them
  simply sees ordinary pages, and one that cannot decrypt shows the page as undecryptable rather
  than dropping it.

---

## 10. Worked examples

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
  "from_name": "Ofloo",
  "mid": "out-9f3c…"
}
```

The same message once the peer's key is known (`pub` still travels, `enc` replaces `message`):

```json
{
  "status": 10,
  "from": "b7c1f0d2-…",
  "from_name": "Ofloo",
  "mid": "out-9f3c…",
  "pub": "-----BEGIN PGP PUBLIC KEY BLOCK-----\n…",
  "enc": "-----BEGIN PGP MESSAGE-----\n…"
}
```

Read receipt for that message:

```json
{ "status": 10, "from": "a1d4…", "read": "out-9f3c…" }
```

Contact invite QR (a fresh channel plus the inviter's per-channel id):

```
pagerapp://contact?u=9d01837b-ecdc-4aad-bcaa-6e82daf75f51&s=https%3A%2F%2Fpager.ofloo.io&n=Ofloo&i=b7c1f0d2-…
```
