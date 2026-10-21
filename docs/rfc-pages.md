# RFC-PagerAPP-0002 — Page semantics, levels and acknowledgements

Status: draft · Last updated: 2026-10-19

This document defines what a **page** is, which levels it can carry, and exactly which fields a
sender must include to request an acknowledgement or to send a plain message that needs no
acknowledgement. It is the authoritative description of page semantics for PagerAPP; the general
client structures are in [RFC-PagerAPP-0001](rfc-pagerapp.md).

Roles: **ProLOG** is a product that can optionally send pages through PagerMCP; **PagerMCP** is
the generic mailbox relay; **PagerAPP** is a client extension on top of PagerMCP used for system
alarms and chat. PagerMCP and PagerAPP work independently of ProLOG. Supporting the ProLOG profile
below is an optional compatibility path, not a dependency. The three are designed to complement
one another, but each remains generic and usable on its own.

Two sender profiles are defined:

- **Native PagerAPP profile** — numeric `status`, optional urgency in `prio`, native ACK through
  `ack_uuid` (status `6`).
- **ProLOG profile** — the five-level urgency ladder `debug < info < warning < alarm < critical`,
  and a sender-managed ACK through a reply mailbox plus a token.

A sender MUST use one ACK profile per page; it SHOULD NOT mix them. A receiver MUST accept both.

PagerMCP is an **unmodified, opaque relay**. It stores and delivers a JSON body without interpreting
it. Every rule below is therefore a client-side convention that senders and receivers must agree on.

---

## 1. Terminology

| Term | Meaning |
|---|---|
| **Mailbox** | A UUID that is both the address and the bearer credential. |
| **Sender** | The party that calls `POST /notify`. |
| **Recipient** | The mailbox owner that fetches the page. |
| **Origin** | The party that wants to know a page was seen; usually the sender. |
| **Page** | One queued message: transport fields plus the sender's payload fields, flattened. |
| **Outcome status** | The numeric or textual `status`: what happened (`success`, `failed`, ...). |
| **Urgency level** | The `prio` field: `debug`, `info`, `warning`, `alarm` or `critical`. |
| **Native ACK** | PagerAPP's `ack_uuid` report: the recipient removes the page and sends a `status: 6` page back. |
| **ProLOG ACK** | Sender-managed ACK: the recipient sends a plain `ack <token>` message to ProLOG's reply mailbox. |
| **Acknowledge (ACK)** | A human action confirming a page. It has two separate effects: removing the page from the recipient's queue and optionally reporting back to the origin. |
| **Plain message** | A page that does not ask for a report back. |

A page is **not** an email and has no body/headers split. All sender fields live at the top level of
the same JSON object as the transport fields.

---

## 2. Transport and queue lifecycle

| Purpose | Request | Credential |
|---|---|---|
| Send a page | `POST /notify`, JSON body | Bearer `<recipient-mailbox>` |
| List queued pages | `GET /mailboxes/<recipient>/messages` | Bearer `<recipient-mailbox>` |
| Remove one page | `POST /mailboxes/<recipient>/ack` with `{"id":"<page-id>"}` | Bearer `<recipient-mailbox>` |
| Remove the oldest page | `POST /mailboxes/<recipient>/ack` with no body | Bearer `<recipient-mailbox>` |
| Consume the oldest page | `POST /mailboxes/<recipient>/consume` | Bearer `<recipient-mailbox>` |
| Wait for a page | `GET /mailboxes/<recipient>/wait` | Bearer `<recipient-mailbox>` |
| Stream pages | `GET /mailboxes/<recipient>/events` (SSE) | Bearer `<recipient-mailbox>` |

Delivery is **at-least-once**:

- `notify`, `wait` and `events` hand a page to a client but do **not** remove it.
- A page leaves the queue only on `POST /ack` or `POST /consume`.
- A client that never confirms a page receives it again on every fetch.
- A repeated `id` is a **retry**, not a new event. Clients MUST deduplicate by `id`.

Limits: 64 KiB per message, 128 messages per mailbox, 7-day message retention and 30-day mailbox
retention. A full mailbox rejects new pages rather than dropping queued ones.

The mailbox UUID is a bearer secret: whoever holds it can read **and** write that mailbox.

---

## 3. Page fields

The list endpoint returns:

```json
{ "messages": [ { "id": "...", "created_at": 1792385301.08, "age_seconds": 0, "status": 2, "...": "..." } ] }
```

Transport fields sit **beside** the sender's fields. There is no nested `payload` object.

### 3.1 Transport fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | string (UUID) | yes | Unique message id; deduplication key and target of queue confirmation. |
| `created_at` | number (Unix seconds) | yes | When the page was created. |
| `age_seconds` | number | yes | Age at list time. Clients MUST surface it; an old page is not a fresh event. |

### 3.2 Sender fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `status` | number or string | yes | Outcome code; see §4.1. Used for the notification decision when `prio` is absent. |
| `PROJECT` | string | no | Producing project or system, e.g. `PagerAPP`, `ProLog`. |
| `JOB_ID` | string | no | Correlates a start and finish page for one job. |
| `message` | string | no | Human-readable summary. |
| `logs` | string | no | Free text, e.g. the tail of a build log or the ProLOG ACK footer (§6.4). |
| `prio` | string | no | Urgency level: `debug`, `info`, `warning`, `alarm` or `critical`. Preferred for the notification decision when present; see §4.2. |
| `ack_uuid` | string (UUID) | no | Native ACK profile: requests an acknowledgement and names the mailbox the ACK report goes to. See §6.1. |
| `from` | string | no | Sender's per-channel id for chat; never a mailbox UUID. See §8. |
| `from_name` | string | no | Sender's display name for chat. |
| `mid` | string | no | Client-generated id of an outgoing chat message, for read receipts. |
| `read` | string | no | Receipt-only page: the `mid` the sender has just read. |
| `pub` | string | no | Sender's armored OpenPGP public key. |
| `enc` | string | no | Message body encrypted to the recipient's public key. |

Unknown fields MUST be ignored. Only the fields above are read by PagerAPP.

---

## 4. Levels: outcome status and urgency

Two different things travel in a page and must not be conflated:

- **`status`** is the **outcome code**: what happened (`success`, `failed`, ...). It is numeric by
  design, scriptable and extensible.
- **`prio`** is the **urgency level**: how loudly the page should be delivered. ProLOG uses the
  five-level ladder `debug < info < warning < alarm < critical`.

A sender SHOULD send `prio` when it has a severity. A client MUST use `prio` for the notification
decision when present and fall back to `status` otherwise.

### 4.1 Outcome status

| Status | Meaning | Default action | Default time bound |
|---|---|---|---|
| `0` | Success | silent | never notify |
| `1` | Warning | sound + vibrate | active hours |
| `2` | Failed | sound + vibrate | active hours |
| `3` | Critical | urgent | always |
| `4` | In Progress | silent | never notify |
| `5` | Queued | silent | never notify |
| `6` | Acknowledged | silent | never notify |
| `10` | Chat message (reserved, see §8) | sound + vibrate | active hours |

Rules:

- `0`, `4`, `5` and `6` are **history only** by default. They never produce a notification unless
  the user changes their rule.
- `3` is the only fixed status that ignores active hours by default.
- `6` is the status of an **ACK report**, not a request for one.
- `10` is reserved for two-way contact chat. It is not a page for a system.

### 4.2 Urgency ladder (ProLOG profile)

ProLOG assigns every alarm one of five urgency levels. The **rank** is, lowest first:

| Rank | Level | Meaning | ProLOG behaviour |
|---|---|---|---|
| 0 | `debug` | logger/monitor messages | special: a recipient whose threshold is `debug` receives **only** debug messages |
| 1 | `info` | informational | |
| 2 | `warning` | warning | |
| 3 | `alarm` | alarm | |
| 4 | `critical` | critical | |

The **display order** is different: ProLOG shows `info · warning · alarm · critical · debug`
(debug last). That is a UI choice, **not** the sender-side threshold rank. Threshold comparisons
always use the rank above; never infer severity from the order in a list. In particular, `debug`
ranks **lowest**, not highest, despite being displayed last. (Linux syslog also ranks debug below
info; only the display order here differs.)

PagerAPP lets the user move the five level cards up or down in **Rules**; the top card is their
chosen display rank. This client order is a preference and does not change ProLOG's threshold
order or another level's behaviour. The rank above is the fixed sender-side ladder ProLOG uses to
decide whether to send a page at all.

`debug` is special-cased: a threshold of `debug` means "**only** debug messages", not "debug and
above". Normal recipients never receive debug messages. This lets a logger/monitor feed be followed
without paging someone for real alarms, and keeps logger noise away from everyone else.

ProLOG 1.42.x does **not** put the level in a structured field: it sends `status: "success"` and
prefixes the `message` title with the level in brackets, e.g. `[WARNING] ACTIEF · Slacht · 1234`.
PagerAPP's **ProLOG compatibility profile** reads that leading `[LEVEL]` and treats it as `prio`;
a top-level `prio`, when present, wins. The prefix is only interpreted for `PROJECT=ProLog`, so
another sender's title is never mistaken for a level. This adapter belongs in the client; ProLOG
does not need to change.

### 4.3 Mapping urgency to outcome status

When a sender can only set the numeric `status`, map the five urgency levels as follows:

| Urgency | `status` | Default notification |
|---|---|---|
| `debug` | `7` (custom, silent by default) | silent, history only |
| `info` | `0` | silent, history only |
| `warning` | `1` | sound + vibrate, active hours |
| `alarm` | `2` | sound + vibrate, active hours |
| `critical` | `3` | urgent, always |

`info` maps to Success; `debug` gets its own custom status so the logger channel can be configured
separately. A ProLOG `debug` page therefore shows as `7 Debug` and is silent unless the user changes
that rule.

### 4.4 Custom statuses

| Status | Meaning |
|---|---|
| `7`–`9` | Custom. Default is silent / never until the user configures a rule. |
| `11`–`99` | Custom. Same default. |
| any other number | Treated as an undefined custom status: silent / never unless configured. |

A sender MAY use a custom status for its own meaning, but MUST NOT assume the recipient has
configured it. A page on an unconfigured custom status is still recorded and shown in history.

### 4.5 Textual statuses

PagerMCP does not interpret the body, and real senders commonly use words. Clients MUST accept both
forms, case-insensitively:

| Text | Numeric |
|---|---|
| `success`, `ok`, `succeeded`, `done`, `completed`, `complete` | 0 |
| `warning`, `warn` | 1 |
| `failed`, `failure`, `fail`, `error` | 2 |
| `critical`, `fatal`, `urgent` | 3 |
| `in progress`, `in_progress`, `running`, `started` | 4 |
| `queued`, `pending` | 5 |
| `acknowledged`, `ack` | 6 |
| any other non-numeric text | 1 (surfaced as Warning, never dropped) |

A numeric string such as `"3"` is parsed as the number. A missing or unparseable status is treated as
`1` so the page still surfaces. A page without an `id` cannot be addressed or confirmed and SHOULD be
logged and skipped.

### 4.6 Decision logic

The recipient decides locally, using device local time and the user's configuration. The
**effective level** is `prio` when present, otherwise `status`:

1. **Silence mode** wins over everything: nothing is shown, history only.
2. **Bypass mode** (on-call) overrides the per-level time bound: every page is shown.
3. Otherwise the level's time bound decides: `never`, `always` or `active_hours`.

Per level the user configures:

- **action** (`silent`, `sound+vibrate`, `urgent`);
- **vibration pattern** (`short`, `long`, `repeated`);
- **schedule**: `always`, `general`, `custom` or `never`.
  - `general` uses the config's `activeWindows` (the general schedule from Settings);
  - `custom` uses the level's own windows, so different levels can cover different hours;
  - an empty custom list falls back to the general schedule rather than silently muting the level;
  - `always` ignores the app's active hours; `never` is history only.

Defaults are the tables in §4.1 and §4.3. "Always" never overrides the phone's silent mode or Do
Not Disturb.

A page that is delivered to the user stays queued until the user confirms it (§7). A page that the
rules keep silent is history only and is confirmed immediately.

> **Implementation status (PagerAPP 0.1.80+).** PagerAPP normalizes `prio` — or the leading
> `[LEVEL]` of a ProLOG page — onto the numeric status at parse time, so the existing per-status
> rules and schedule choices apply. ProLOG needs no change. The original `status` is still shown by
> senders that omit `prio`; for ProLOG the title prefix and the mapped status are authoritative.

---

## 5. Chat levels are not page levels

Chats use a named level stored on the contact, not a numeric `status` chosen per message:

| Level | Action | Time bound |
|---|---|---|
| `Important` | urgent | always |
| `Normal` | sound + vibrate | active hours |
| `Silent` | silent | never |

A contact stores only the level **name**; retuning a level changes it for every chat using that name.
The wire message still carries `status: 10`; the named level exists only in the client configuration.
Do not confuse the two: `status` is what the sender says happened, the chat level is how loudly the
recipient wants to hear about it.

---

## 6. Requesting an acknowledgement

There are two ACK profiles in use, and they are alternatives, not layers:

- **Native ACK** (§6.1–§6.3): `ack_uuid` is present; the recipient removes the page and sends a
  `status: 6` report back. This is the PagerAPP profile.
- **ProLOG ACK** (§6.4): no `ack_uuid`; the page body names ProLOG's reply mailbox and a token, and
  the recipient sends a plain `ack <token>` message back. ProLOG matches it itself.

A receiver MUST detect which profile a page uses. A sender MUST use exactly one.

### 6.1 The native ACK flag: `ack_uuid`

There is **no** `ack`, `ack_required` or `require_ack` boolean in the native profile. Senders MUST
NOT rely on such fields, and receivers MUST ignore them.

A page requests an acknowledgement **if and only if** it carries a non-blank `ack_uuid`:

```json
{
  "status": 3,
  "PROJECT": "ProLog",
  "JOB_ID": "deploy-42",
  "message": "Deployment needs approval",
  "logs": "waiting for operator",
  "ack_uuid": "9d01837b-ecdc-4aad-bcaa-6e82daf75f51"
}
```

- `ack_uuid` MUST be a mailbox UUID that accepts `POST /notify` (it is used as the Bearer token).
- It SHOULD be the origin's mailbox, i.e. the mailbox that wants to know the page was seen.
- It MUST NOT be the recipient's own mailbox; that would create a self-addressed report.
- A blank, missing or `null` value means "no acknowledgement requested".
- `ack_uuid` is a **capability**: whoever receives the page can write to that mailbox. Only include
  it when the recipient is trusted, and never include it on pages sent to an untrusted party.

`prio` is not an acknowledgement flag. It carries urgency (§4.2) and says nothing about whether a
report is expected.

### 6.2 Native ACK: what the recipient does

An ACK has two independent effects. Keep them separate:

| Effect | Request | Credential | Result |
|---|---|---|---|
| **Queue confirmation** | `POST /mailboxes/<recipient>/ack` `{"id":"<page-id>"}` | recipient mailbox | page leaves the recipient's queue; no message is sent |
| **ACK report** | `POST /notify` to `ack_uuid` with `status: 6` | `ack_uuid` | origin learns the page was acknowledged |

For a page delivered to the user:

1. The page stays queued while it waits for a human.
2. When the user confirms it, the client **first** removes it from the queue (`/ack`).
3. If the page has `ack_uuid`, the client **then** sends the ACK report.
4. If the report fails, the client SHOULD retry or log it, but MUST NOT notify the user again.

For a page that the local rules keep silent (history only), the client confirms the queue entry
immediately and sends no report, even when `ack_uuid` is present. There was no human acknowledgement
to report.

For a page **without** `ack_uuid`, the client can still confirm the queue entry when the user acts on
it, but MUST NOT send a status-6 report: there is no address to report to.

ACK reports are never sent automatically for pages. Chat is the one exception to *queue
confirmation* only: reading a chat message confirms it (§8). A chat message does not request an ACK
report.

### 6.3 ACK report payload

The report is an ordinary `POST /notify` to the `ack_uuid`:

```json
{
  "PROJECT": "PagerAPP",
  "status": 6,
  "message": "Acknowledged",
  "acknowledged_by": "<the acknowledger's own mailbox uuid>",
  "JOB_ID": "<original JOB_ID, when present>",
  "in_reply_to": "<original page id>"
}
```

| Field | Required | Meaning |
|---|---|---|
| `PROJECT` | yes (by convention) | `PagerAPP` when sent by the app. |
| `status` | yes | `6` = acknowledged. |
| `message` | yes | The user's optional note, or `Acknowledged`. |
| `acknowledged_by` | yes | The acknowledger's own mailbox UUID, so the origin can correlate. |
| `JOB_ID` | when present on the original | Correlates request and acknowledgement. |
| `in_reply_to` | yes | The original page `id`. |

`acknowledged_by` is what lets the origin stop escalating. Escalation itself is a **sender-side**
policy: PagerMCP has no timers, retries or escalation logic. A sender that requires an ACK decides
what to do when none arrives (send again, escalate, give up).

### 6.4 ProLOG ACK profile (reply mailbox + token)

ProLOG does not use `ack_uuid`. Its pager keeps its own **reply mailbox** and puts a short token in
the page body. This is a **sender-managed** acknowledgement: ProLOG polls the reply mailbox and
matches the token itself. It is what ProLOG 1.42.x uses when **Ack verplicht** is enabled.

An ACK-required ProLOG page has the same fields as a plain page, plus a footer at the end of
`logs`:

```
------------------------
ACK verplicht. Antwoord met een bericht naar mailbox:
<reply_uuid>
met in de tekst: ack <token>
```

- `reply_uuid` is ProLOG's own mailbox (a PagerMCP UUID) and is also the Bearer token for the reply.
- `<token>` is a short hex token (8 characters in ProLOG 1.42.x), unique per pending page.
- The recipient acknowledges by sending a **plain page** to `reply_uuid`:

```json
{ "message": "ack <token>" }
```

  This is a normal `POST /notify` to `reply_uuid`. It carries **no** `ack_uuid` and **no**
  `status: 6`. ProLOG also accepts the token in `JOB_ID` (with an optional `ack` prefix).
- ProLOG reads `GET /mailboxes/<reply_uuid>/messages`, matches the token, marks the pending page
  acknowledged, and consumes the reply. Until then it re-sends `[HERINNERING n]` pages at the
  configured interval, up to the configured maximum, or until the alarm recovers.
- The original page must still be queue-confirmed in the recipient's mailbox (`POST /ack`); that is
  independent of the reply and is what stops the recipient from fetching it again.

In ProLOG 1.42.x, `reply_uuid` and the token are **not** top-level fields; they live only in the
`logs` footer. A sender that wants structured parsing SHOULD add `reply_uuid` and `ack_token` as
top-level fields. A client that sees the footer MUST treat the page as ACK-required and offer a way
to send the `ack <token>` reply; it MUST NOT send a status-6 report to `reply_uuid`, because ProLOG
matches the token, not the status.

A plain ProLOG page has no footer and therefore requests no ACK.

### 6.5 Summary: ACK profiles vs plain page

| | Native ACK | ProLOG ACK | Plain page |
|---|---|---|---|
| Request marker | `ack_uuid` present | `reply_uuid` + `ack <token>` in the `logs` footer | none |
| Recipient confirms queue | yes, on human action | yes, on human action | yes, on human action, or immediately when silent |
| Recipient reports back | `status: 6` to `ack_uuid` | plain `ack <token>` to `reply_uuid` | no report |
| Origin correlates by | `acknowledged_by`, `in_reply_to` | token, `JOB_ID` | nothing |
| Origin can stop escalation | yes, on the report | yes, on the reply | no; it has no signal |
| Automatic report | never for pages | never for pages | not applicable |

A sender that **needs** to know a page was seen MUST use one of the two ACK profiles. Without either,
silence is indistinguishable from "not seen yet".

---

## 7. Plain pages and delivery state

A plain page is the normal case for CI jobs, scripts and `pager.sh`:

```json
{
  "status": "success",
  "prio": "info",
  "PROJECT": "ProLog",
  "JOB_ID": "prolog-test",
  "message": "ProLog testmelding",
  "logs": "Dit is een testmelding vanuit ProLog."
}
```

Rules:

- No `ack_uuid` and no ProLOG ACK footer: the sender does not expect a report.
- The client resolves the effective level (§4.6). `debug`/`info` and statuses 0/4/5 are history only;
  the client confirms the queue entry immediately.
- `warning`/`alarm`/`critical` and statuses 1/2/3 notify according to the user's rule and keep the
  page queued until the user confirms it. The confirmation is local queue hygiene, not a report.
- `age_seconds` and `created_at` MUST be surfaced. A page that is hours old is shown as old, not as a
  fresh event.

> ProLOG 1.42.x does **not** send `prio` yet: it sends `status: "success"` and puts the urgency only
> in the title (`[WARNING] ...`). PagerAPP's ProLOG compatibility profile reads that prefix and maps
> it to the status in §4.3, so the page is classified by its real urgency. No ProLOG change is
> required.

A page that never gets a human confirmation remains in the queue until retention removes it. That is
why the client exposes a confirmation action even when there is no `ack_uuid`: otherwise the same
page would be delivered again forever.

---

## 8. Chat messages (status 10)

A chat message is a plain message on a shared contact channel. It does **not** use `ack_uuid`.

```json
{
  "status": 10,
  "message": "ben je er?",
  "from": "b7c1f0d2-...",
  "from_name": "Ofloo",
  "mid": "out-9f3c..."
}
```

- `from` MUST be the sender's **per-channel** id, never a mailbox UUID.
- `mid` is the client-side id used for read receipts.
- `pub` is attached to every outgoing message so the peer learns the key.
- `enc` replaces `message` once the peer's key is known.
- Reading is the acknowledgement: the recipient confirms inbound chat pages as it shows them and does
  not send a status-6 report.
- A read receipt is a separate page: `{ "status": 10, "from": "<my id>", "read": "<mid>" }`.
  A receiver MUST queue-confirm a receipt-only page and MUST NOT show, notify or count it as a
  chat message; it only marks the matching outgoing message read (✓ → ✓✓).
- Only the receiver confirms a shared channel's page. Confirming your own message would remove it
  before the peer sees it: a client MUST NOT do that.

Chat levels (`Important`, `Normal`, `Silent`) are local preferences; they are never transmitted.

### 8.1 Chat photos

Two transfer profiles exist; both keep the image encrypted to the peer, so PagerMCP and the share host
never see it.

**Direct (legacy, receive-only).** Older clients encrypted a JPEG (up to 1,000,000 bytes) to the
peer's OpenPGP key, split the armored text into chunks of at most 30,000 characters, and sent each
chunk as an ordinary `status: 10` chat page. Current PagerAPP sends every new photo through the
share-link profile below, but retains this receiver for photos already in flight:

| Field | Meaning |
|---|---|
| `image_id` | Shared id for all chunks of this photo. |
| `image_part` | 1-based part number. |
| `image_total` | Total number of parts (at most 80). |
| `image_chunk` | One slice of the armored PGP ciphertext. |
| `image_sha256` | SHA-256 of the complete armored ciphertext; every part carries it. |

The receiver stores each part, confirms its queue entry, and once all parts are present concatenates
them in part order, verifies `image_sha256`, decrypts, saves the JPEG and creates **one** history and
notification entry. A missing or corrupt part means no image; nothing is displayed as ciphertext.
The first part may carry `pub` so the peer learns the sender's key.

**Share link (all new photos).** The sender compresses the photo to at most 8 MB, encrypts it to the
peer, uploads the armored ciphertext to the configured share host (default `share.h4ck.io`, a
transfer.sh instance), and sends an ordinary encrypted chat message containing:

```
pagerapp://photo?u=<url-encoded https URL>&h=<sha256 of the armored file>&d=<retention days>
```

The host's retention is fetched from its landing page (currently 3 days; the upload sets
`Max-Days`), and the same value is shown to the sender before sharing and carried as `d`. The
receiver accepts the configured share host (both devices must use the same one), verifies the
SHA-256, decrypts with its own secret key, and **downloads the photo on receipt** so it is on the
device before the chat is opened. A download failure leaves the link visible as a retry action. The
share host sees only the encrypted
blob; the URL itself travels inside the encrypted chat body.

Direct photos are capped at 1 MB because PagerMCP allows 64 KiB per page and 128 queued pages per
mailbox. Larger photos therefore use the share-link profile rather than chunking without bound.
PagerAPP currently uses the **share-link profile for all outgoing photos**; the direct chunk profile
is retained for compatibility with older clients. The share host (default `share.h4ck.io`) is
configurable in Settings and both sides should use the same one.

---

## 9. Security considerations

- The mailbox UUID is a bearer credential for both reading and writing. `ack_uuid` and ProLOG's
  `reply_uuid` hand the recipient a write capability for that mailbox; treat both as sensitive.
- `from` and `from_name` are self-asserted. Only a valid OpenPGP signature over the body proves
  authorship; the transport does not.
- `status` and `prio` are self-asserted too. A sender can mark anything `0` or `debug` (silent).
  Receivers that need guaranteed attention should use a level they have configured to be loud.
- A malicious sender can request an ACK to a mailbox it controls; the report reveals the
  acknowledger's UUID in `acknowledged_by`. Only exchange pages with parties you trust.
- Clients MUST NOT auto-send ACK reports or `ack <token>` replies. Either is evidence that a human
  saw the page.

---

## 10. Compatibility

- Status meanings `0`–`6` are fixed. `10` is reserved for chat. Other numbers are custom and may be
  configured, but an unconfigured custom status is silent.
- Unknown JSON fields MUST be ignored.
- `prio` is additive: a client that ignores it falls back to `status`. A sender MUST NOT rely on
  `prio` alone when old clients must still classify the page correctly.
- `ack_uuid` is additive: a client that does not understand it still sees and processes the page,
  but sends no report. A sender that requires an ACK must tolerate such clients and escalate on its
  own side.
- The ProLOG `logs` footer is additive: a client that ignores it sees a normal page. A client that
  understands it MUST send `ack <token>` to the named reply mailbox, not a status-6 report.
- A page that cannot be parsed is logged, not silently discarded. A page with no `id` cannot be
  confirmed and SHOULD be skipped with a log entry.

---

## 11. Worked examples

### 11.1 Plain page without ACK

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $MAILBOX" \
  -H "Content-Type: application/json" \
  -d '{
    "status": 2,
    "prio": "alarm",
    "PROJECT": "PagerAPP",
    "JOB_ID": "android-build-1792385249",
    "message": "Android build failed",
    "logs": "Gradle exit 1"
  }'
```

No report is ever sent back. The recipient notifies at level 2, keeps the page queued until the user
confirms it, then removes it with `POST /mailboxes/<mailbox>/ack {"id":"<page-id>"}`.

### 11.2 Page requesting ACK

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $RECIPIENT" \
  -H "Content-Type: application/json" \
  -d '{
    "status": 3,
    "prio": "critical",
    "PROJECT": "ProLog",
    "JOB_ID": "deploy-42",
    "message": "Deployment needs approval",
    "ack_uuid": "'"$ORIGIN"'"
  }'
```

The recipient confirms the queue entry and sends:

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $ORIGIN" \
  -H "Content-Type: application/json" \
  -d '{
    "PROJECT": "PagerAPP",
    "status": 6,
    "message": "Acknowledged",
    "acknowledged_by": "'"$RECIPIENT"'",
    "JOB_ID": "deploy-42",
    "in_reply_to": "<page-id>"
  }'
```

### 11.3 Chat message without ACK

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $CHANNEL" \
  -H "Content-Type: application/json" \
  -d '{
    "status": 10,
    "message": "ben je er?",
    "from": "b7c1f0d2-...",
    "from_name": "Ofloo",
    "mid": "out-9f3c..."
  }'
```

No `ack_uuid`. The peer's reply is a separate message with its own `from` and `mid`. Reading it
confirms the queue entry; a read receipt may follow as `{"status":10,"from":"...","read":"out-9f3c..."}`.

### 11.4 ProLOG plain page (no ACK)

As ProLOG 1.42.x sends it today: the urgency is only in the title, and there is no `prio` field yet.

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $RECIPIENT" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "success",
    "PROJECT": "ProLOG",
    "JOB_ID": "prolog-2-1234",
    "message": "[WARNING] ACTIEF · Slacht · 1234",
    "logs": "Oliepeil laag\n(2026-10-19 05:00:00 UTC)"
  }'
```

No ACK footer. The recommended form adds `"prio": "warning"`; without it a native client sees only
`status: "success"` and stays silent.

### 11.5 ProLOG page with ACK

When **Ack verplicht** is on, ProLOG appends the reply mailbox and token to `logs`:

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $RECIPIENT" \
  -H "Content-Type: application/json" \
  -d '{
    "status": "success",
    "PROJECT": "ProLOG",
    "JOB_ID": "prolog-2-4321",
    "message": "[CRITICAL] ACTIEF · Slacht · 4321",
    "logs": "Noodstop actief\n(2026-10-19 05:00:00 UTC)\n\n------------------------\nACK verplicht. Antwoord met een bericht naar mailbox:\n<reply_uuid>\nmet in de tekst: ack 1a2b3c4d"
  }'
```

The recipient acknowledges with a plain page to `reply_uuid` — no `ack_uuid`, no status 6:

```sh
curl -fsS -X POST https://pager.example/notify \
  -H "Authorization: Bearer $REPLY_UUID" \
  -H "Content-Type: application/json" \
  -d '{ "message": "ack 1a2b3c4d" }'
```
