# PagerAPP P2P transport protocol

Status: draft v0.2 — §1–§12 are the target protocol; §13 is the profile the Android client
actually ships today (0.1.165: JSON over GATT, full route hashes, ownership-is-delivery).  
Scope: phone app first (Android), BLE first; Wi-Fi Direct next; LoRa/T-Deck later  
Encoding: target is compact CBOR; the shipped BLE transport uses UTF-8 JSON (see §13)

## 1. Design rules

- P2P mode is opt-in and off by default.
- The P2P queue contains only P2P envelopes; it is not an index of all mailbox messages.
- `p2p_id` is the stable envelope identity and the dedupe key.
- A mailbox UUID/bearer is never advertised in plaintext.
- Full SHA-256 route hashes stay local; only the first **8 bytes** go on the wire.
- Payloads are end-to-end encrypted with the recipient's mailbox key.
- Compression happens **before** encryption: zlib/Deflate, or OpenPGP compression.
- No attachments in v1; text-only.
- The receiver persists an envelope before sending a positive ACK.

## 2. Identifiers

| Name | Size | Meaning |
|---|---:|---|
| `mailbox_uuid` | 16 bytes | PagerMCP mailbox address and bearer; never advertised |
| `route_hash` | 32 bytes local | `SHA-256(lowercase(mailbox_uuid))`; full value stays local |
| `route_prefix` | 8 bytes wire | First 8 bytes of `route_hash`; 64 bits |
| `p2p_id` | 16 bytes | Stable UUID of one envelope; used for HAVE/WANT/dedupe/ACK |
| `device_id` | 16 bytes random | Ephemeral/rotating transport identity |
| `key_fingerprint` | 32 bytes | Public-key fingerprint; only after authentication |

`route_prefix` is for discovery/routing only. The full hash is used for local lookup and
gateway mapping. An 8-byte prefix is a deliberate bandwidth compromise; collision handling
uses the full hash in an `IS_AT` response if requested.

## 3. Envelope

```text
Envelope {
  v: uint,                 // 1
  id: bstr(16),            // p2p_id
  from: bstr(8),           // sender route_prefix (optional)
  to: bstr(8),             // recipient route_prefix
  created_at: uint,        // Unix seconds, sender clock
  expires_at: uint,        // absolute Unix seconds
  payload: bstr,           // PagerMCP chat payload JSON, encrypted
  sig: bstr?,              // optional outer signature; prefer inner payload signature
  hops: uint               // default 0
}
```

- The payload is the existing PagerMCP chat JSON (status, from, mid, enc/message, sig, ...).
- `to` is a route prefix, not a mailbox UUID.
- `expires_at` is authoritative for expiry; expired envelopes are deleted, not relayed.
- The full envelope is hashed for transfer integrity; the payload stays E2E encrypted.

## 4. Frame types

All frames carry `v:1` and `type`. Integer map keys may be used to reduce overhead.

### 4.1 Discovery

```text
ANNOUNCE {
  device: bstr(16),
  caps: [ "ble", "wifi_direct", "gateway", "relay" ],
  key: bstr(32)?,          // fingerprint, optional until authenticated
  gateway: bool,
  internet: bool,
  queue_bytes: uint,
  battery: uint?
}
```

```text
WHO_HAS {
  req: bstr(8),
  route_prefix: bstr(8)
}

IS_AT {
  req: bstr(8),
  route_prefix: bstr(8),
  full_hash: bstr(32)?,    // only when collision resolution is needed
  proof: bstr?             // authenticated response
}

NOT_AT {
  req: bstr(8),
  route_prefix: bstr(8),
  proof: bstr?
}
```

- `WHO_HAS` is broadcast to reachable peers.
- The target answers `IS_AT`; any peer MAY answer `NOT_AT` in a small trusted mesh.
- A positive answer wins before the timeout; if a `NOT_AT` arrives first, the sender may go
  straight to the gateway. Late answers after the gateway decision are ignored.
- `NOT_AT` must be authenticated when possible; an untrusted peer must not be able to block
  a direct route. In the first closed test group, any authenticated peer may answer negatively.

### 4.2 Gateway

```text
WHO_IS_GATEWAY { req: bstr(8) }

GATEWAY_IS_AT {
  req: bstr(8),
  endpoint: tstr,
  internet: bool,
  server: tstr,
  ttl: uint,
  caps: [...]
}
```

- A gateway advertises/proactively announces `gateway:true, internet:true`.
- The gateway's mailbox UUID/bearer is never included.
- Multiple gateways are allowed; clients choose one preferred gateway. Server-side P2P dedupe
  handles races and retries.

### 4.3 Queue sync

```text
HAVE { ids: [ bstr(16) ] }
WANT { ids: [ bstr(16) ] }

START {
  transfer: bstr(8),
  envelope: bstr(16),      // p2p_id
  total: uint,
  sha256: bstr(32)         // hash of the complete envelope bytes
}

CHUNK {
  transfer: bstr(8),
  seq: uint,
  data: bstr,
  crc32: uint
}

DONE { transfer: bstr(8), sha256: bstr(32) }

ACK {
  transfer: bstr(8),
  envelope: bstr(16),
  sha256: bstr(32),
  stored: bool,
  error: tstr?
}
```

- `HAVE`/`WANT` exchange only IDs; no payloads.
- For large queues, send IDs in batches. A Merkle/Bloom summary may precede the ID batches.
- Sender transfers only requested envelopes.
- `CHUNK.data` is one fragment of the serialized envelope bytes; CRC32 covers exactly `data`.
- Receiver reassembles, verifies SHA-256, stores the envelope, then sends `ACK stored:true`.
- Sender removes the envelope from its queue only after a matching ACK.
- `ACK stored:false` means retry the transfer; no delivery was recorded.

## 5. Queue states

```text
queued -> in-flight -> stored (custody) -> delivered
                    \-> expired / failed
```

- `stored` means a peer has persisted it; it is not final delivery unless that peer is the target.
- `delivered` means the target accepted it locally, or a gateway/PagerMCP accepted it.
- In the shipped implementation the ownership test is decisive: a phone only answers
  `delivered:true` when the envelope's route hash belongs to one of its own mailboxes (see §13.4).
  A relay answers `delivered:false`, keeps the envelope in custody, and the sender keeps offering
  it.
- A relay ACK is custody, not final delivery.
- Duplicate `p2p_id` never increments delivery twice.

## 6. Routing

Precedence per envelope:

```text
1. route_prefix belongs to a local mailbox? -> local delivery
2. known neighbor entry?                    -> direct peer
3. known gateway?                           -> WHO_HAS first, then gateway
4. no gateway                               -> sync all P2P queues (diff only)
5. no peer/gateway                          -> keep queued
```

- When a gateway is known/reachable, do **not** do full peer-to-peer replication.
- When no gateway is reachable, peers exchange `HAVE/WANT` and replicate missing P2P envelopes.
- A `WHO_HAS` lookup times out after **3 seconds**.
- A negative lookup is cached for **30 seconds**; neighbor entries for **300 seconds**;
  gateway advertisements for **60 seconds**.
- Local delivery always wins, even if a gateway is available.

## 7. Gateway upload

- When a gateway is reachable, the client uploads P2P envelopes to PagerMCP.
- Only envelopes that came from P2P are tagged with:

```json
{
  "p2p": true,
  "p2p_id": "<uuid>",
  "p2p_created_at": 1790000000,
  "p2p_expires_at": 1790604800
}
```

- Normal locally composed messages are never tagged as P2P.
- Server-side dedupe is authoritative; `duplicate:true` means delivered to the gateway.
- `410 expired`, `409 conflict` and `403 disabled` are dropped; network/429 errors are retried.
- The gateway does not automatically know every mailbox UUID; it uses a local mapping,
  encrypted route data, or a gateway mailbox. Bearers are never broadcast.

## 8. Compression

- Compress plaintext before OpenPGP encryption.
- Preferred: OpenPGP compressed-data packet (ZIP/Deflate).
- Alternative: zlib/Deflate with an explicit `compressed` marker.
- Never compress ciphertext.
- Skip compression for short messages when it does not reduce size.
- Limit decompressed size to prevent zip bombs.

## 9. Security

- E2E encryption protects payloads; transports see only ciphertext.
- Do not advertise mailbox UUIDs, bearers, private keys, or raw public keys.
- Advertise only a route prefix/device ID; authenticate `IS_AT`/`NOT_AT`.
- Prefer inner-payload signatures; an outer signature exposes the signing key ID to relays.
- Validate expiry, message size and p2p_id before storing.
- Rate-limit discovery and transfers to protect BLE/LoRa airtime and battery.

## 10. Transport mappings

### BLE
- GATT service with RX write and TX notify characteristics.
- Both devices may advertise/scan; deterministic device-ID comparison chooses one connector.
- Request MTU up to 512; transport splits serialized CBOR frames with a 2-byte length prefix.
- Foreground service + Android 12+ Bluetooth permissions.

### Wi-Fi Direct
- BLE is used for discovery/negotiation; Wi-Fi Direct creates the group without internet.
- The same CBOR frames run over a TCP stream with a length prefix.
- Bulk queue sync uses Wi-Fi Direct; BLE remains the control/fallback channel.

### LoRa/Meshtastic
- Carry one CBOR frame or fragment in a private `MeshPacket` portnum.
- Much smaller chunks; airtime budget and duty cycle; no bulk sync.
- Same envelope, queue, ACK, dedupe and routing semantics.

## 11. Timeouts and limits

```text
route lookup            3 s
negative route cache    30 s
neighbor entry          300 s
gateway advertisement   60 s
transfer ACK            10 s
sync session           30–60 s, resumable
max message             PagerMCP limit (64 KiB)
```

## 12. Compatibility

- Old clients never send P2P frames; normal PagerMCP behavior is unchanged.
- Envelopes with an unknown version are ignored and reported.
- A server/transport without P2P support falls back to normal notification.
- The wire format is versioned; additive fields are ignored by older readers.

## 13. Implemented transport (Android 0.1.165)

This is what the Android client actually puts on the air. It keeps the envelope/queue semantics
of §3–§5 but uses JSON frames and full route hashes instead of CBOR and 8-byte prefixes.

### 13.1 Link

- GATT service `0000f00d-0000-1000-8000-00805f9b34fb`; RX `…71` (write), TX `…72` (notify),
  standard CCCD. MTU up to 512 is requested; both test phones negotiate 517.
- The lower device id opens the link; the higher id stays peripheral. One link per pair; a second
  link to the same address is ignored. Every link has its own reassembly buffer, so frames from
  one phone can never be parsed at another phone's offset.
- Framing: **2-byte big-endian length + UTF-8 JSON**, split into `MTU-3` fragments. Buffers are
  reset on connect/disconnect, so a frame lost mid-link cannot corrupt the next session.

### 13.2 Frames

```json
{"t":"hello"}
{"t":"have","ids":["<envelope id>", "..."]}
{"t":"want","ids":["<envelope id>", "..."]}
{"t":"start","transfer":"<uuid>","envelope":"<id>","total":38,"sha256":"<hex>"}
{"t":"chunk","transfer":"<uuid>","seq":7,"total":38,"crc32":123,"data":"<base64>"}
{"t":"done","transfer":"<uuid>"}
{"t":"ack","envelope":"<id>","sha256":"<hex>","stored":true,"delivered":true}
{"t":"nack","envelope":"<id>"}
```

- `hello` is answered with `have` on the same link.
- `have` only lists ids; the receiver answers `want` with the difference.
- `want` is answered with one envelope at a time (`start`/`chunk`*/`done`).
- `ack.stored:false` or `nack` means the receiver could not assemble the envelope; the sender
  retries it (max three attempts per link, then parks it until a later connection).
- `ack.delivered` is the important flag, see §13.4.

### 13.3 Envelope (JSON)

```json
{"v":1,"id":"<uuid>","from":"<sha256 hex>","to":"<sha256 hex>",
 "created_at":1790000000,"expires_at":1790604800,"payload":"<PagerMCP chat JSON>"}
```

- `to` is the **full** `sha256(lowercase(mailbox_uuid))` hex string (64 chars), not a prefix.
- A phone's BLE id is the first 8 hex chars of its own route hash.
- `expires_at` is authoritative: expired envelopes are not stored, relayed or delivered.
- The payload is the normal chat JSON (status/from/mid/enc/sig/…) and stays E2E encrypted.

### 13.4 Delivery: ownership is delivery

- The phone checks whether `envelope.to` equals the route hash of any mailbox it owns (private
  mailbox, page addresses, contact inboxes). If so, it stores the envelope, decrypts with that
  mailbox's key (legacy key as fallback), delivers locally, and replies
  `ack {stored:true, delivered:true}`.
- Any other phone stores custody and replies `ack {stored:true, delivered:false}`. Custody is
  **not** delivery: the sender keeps the envelope in its queue (clock icon) and offers it to every
  peer it meets until an owner confirms or the envelope expires.
- The sender removes the envelope on `delivered:true`; that is "delivered by definition" and no
  separate receipt round-trip is required. A plaintext delivery-receipt page is still sent to the
  sender's mailbox as a backstop for the gateway path.
- A peer merely advertising the id in `have` is custody, not delivery; the clock stays.

### 13.5 Transfer scheduling and retries

- One envelope at a time; chunks are 240 bytes when the MTU is ≥200, otherwise 120.
- ACK timeout 20 s; `nack` triggers an immediate resend; up to three attempts per link, after
  which the envelope is parked until a later connection (its `expires_at` still applies).
- A failed GATT write is retried after 200 ms; two failures in a row recycle the link.
- A write callback that never arrives twice in a row (5 s watchdog each) also recycles the link,
  so a dead link cannot burn a whole transfer or leave a message "under way" forever.
- Service discovery must complete within 6 s or the link is recycled (Android's stale GATT cache
  is refreshed and discovery is retried twice before giving up).
- An idle link is kept warm with `hello` every 15 s; it is recycled after 60 s of silence (45 s
  when another peer is visible), and after 20 s when there is still outgoing data.
- Only the lower device id opens a link; a second link to the same address is ignored. This avoids
  two byte streams to one phone and the resulting wrong-peer replies.

### 13.6 Gateway hand-off

- Locally composed envelopes whose owner is not reachable are uploaded to PagerMCP when the
  network returns (`flushP2PQueue`), so the message still reaches the recipient's mailbox.
- Envelopes already confirmed with a delivery receipt are removed instead of uploaded.
- P2P and gateway copies of the same chat message are deduplicated by `(mid, from)`.

### 13.7 Push identity (FCM)

- `GET /settings` may advertise `subscriptions.identity` (`uuid` or `hash`),
  `identity_period_seconds`, and `server_time` (Unix seconds).
- In `hash` mode the subscription identity is `sha256(lowercase(uuid))` — the same value as the
  BLE route hash — optionally rotating per period. The client sends both the current `route` and
  `routes: [previous, current, next]`, so a clock skew or a period boundary cannot lose a push:

  ```json
  {"channel":"fcm","token":"<device token>",
   "route":"<sha256>",
   "routes":["<previous>","<current>","<next>"]}
  ```

  Renewal uses the same fields with `{"id":"<subscription id>"}`.
- Buckets are computed from `server_time` adjusted for the device clock offset, never from the
  raw phone clock. A subscription is renewed when its bucket changes, in addition to the normal
  lease renewal.
- While the server reports `uuid` (default), no `route`/`routes` fields are sent.

### 13.8 Deviations from §1–§12 (current client)

| Target | Shipped in 0.1.165 |
|---|---|
| CBOR frames | UTF-8 JSON, 2-byte big-endian length prefix |
| 8-byte route prefixes | full 64-char SHA-256 hex route hashes |
| WHO_HAS / IS_AT / NOT_AT route lookup | `have`/`want` flooding; no route lookup frames yet |
| ACK is custody only | ACK carries `delivered`, owned target = delivered |
| zlib/OpenPGP compression | not implemented (payload is already signed/encrypted) |
| Outer envelope signature | not implemented; the payload signature is verified on delivery |
| Wi-Fi Direct and LoRa transports | BLE GATT only |
| Queue sync batches / Merkle summary | full id list in one `have` frame, capped by the local queue size |

The target sections remain the plan for a future wire version; old readers ignore unknown JSON
fields and unknown frame types.

### 13.9 Client lifecycle (Android)

- **One host per transport.** With reliable delivery on, `PollService` (foreground, `dataSync`)
  hosts BLE and shows the single PagerAPP notification. Only in the quiet modes does `P2PService`
  (foreground, `connectedDevice`) host it. A sticky restart or app update cannot resurrect the
  second notification.
- Both hosts start after boot when P2P is enabled, and take a partial wake lock while BLE is
  active: with the screen off the CPU otherwise suspends and the GATT handshake stalls.
- `Battery → Not restricted` is still recommended on aggressive OEM builds (OnePlus/HANS); the
  app exposes a shortcut under Settings → Delivery.
- Tapping a page notification carries the page id (and, for chat, the peer id) in the intent; the
  activity is expected to reuse its existing instance (`singleTop` + `onNewIntent`), not open a
  second session.

### 13.10 Anonymity: what the route hash hides and what it does not

- **A route hash is a pseudonym, not a secret.** `sha256(lowercase(uuid))` is computable by
  anyone who knows the mailbox UUID, and the UUID *is* the bearer. The hash keeps the UUID out of
  logs, topics and push bookkeeping; it does not protect against someone who already holds the
  UUID.
- **Rotation gives unlinkability over time, not secrecy.** With
  `sha256("<bucket>|<uuid>")` an observer who does *not* know the UUID cannot correlate the
  buckets with each other or with the stable BLE hash. With previous/current/next registration a
  clock skew or a period boundary cannot cause missed wake-ups.
- **The PagerMCP server still learns the UUID at registration** (path plus `Authorization`
  bearer). Hiding it from the server itself would require an unauthenticated, hash-only
  registration endpoint, with its own abuse/rate-limit design; that is not part of this profile.
- **Keep the payload content-less.** FCM/APNs/UnifiedPush wake-ups carry no page content; the app
  fetches over TLS after waking. The push provider only sees a token/topic and timing.
- **Do not log identifiers.** The client logs at most an 8-hex-character route prefix for
  diagnostics, never the UUID, bearer, FCM token or key material.
- **BLE metadata is visible to nearby peers.** `have`/`want`/`ack` expose envelope ids, queue
  sizes, timing and route hashes; the payload itself stays end-to-end encrypted. No bearer or raw
  public key is advertised. An 8-hex prefix is enough to route, and the full 64-char hash never
  leaves the device over BLE.

## 14. v2 envelope: the UUID is never readable on the wire

Status: proposal, not implemented.

**Threat model: the UUID is the only secret.** Everything else in the header — the envelope id,
`created_at`, `expires_at`, sizes, timing, even a stable `sha256(uuid)` route — is considered
public or trivial. The consequence is deliberately small: GPG has exactly one job, keeping the
**UUID** unreadable on the wire. The rest stays plaintext; do not over-engineer header secrecy.

### 14.1 Fields

```json
{"v":2,
 "id":"<envelope uuid>",
 "to":"<sha256("<bucket>|<uuid>") hex>",
 "to_enc":"<OpenPGP(uuid, server public key)>",
 "created_at":1790000000,
 "expires_at":1790604800,
 "payload":"<PagerMCP chat JSON, E2E encrypted to the recipient>"}
```

- `bucket = created_at / period` (period comes from the server's `/settings`, e.g. 3600). The
  sender computes `to` from the same `created_at` it puts in the envelope, so no clock alignment
  is needed.
- `to` is a per-bucket pseudonym. The recipient hashes **each mailbox it owns** (private mailbox,
  page addresses, contact inboxes) with the envelope's bucket and compares. A match means "this
  is mine"; no decryption. Rotation is **optional**: a stable `sha256(uuid)` works just as well
  for ownership; the bucket only limits linkability for relays, which this threat model treats
  as a nicety, not a requirement.
- `mailbox_encrypted` is only the UUID, OpenPGP-encrypted to the **server's** public key
  (`/settings → p2p.public_key`, identified by `p2p.key_id`). A relay cannot read it; the PagerMCP
  server decrypts it when the envelope reaches the gateway, so routing works without the relay
  knowing the destination. The gateway upload (`POST /notify`) for a P2P envelope carries **no
  Authorization header** and no plain `uuid`/`destination`; the server routes solely by the
  decrypted field.
- The payload is unchanged: already signed/encrypted to the recipient's key.

### 14.2 What each party learns

| Party | Learns |
|---|---|
| Relay peer | `id`, `created_at`, sizes, timing; a per-bucket hash it cannot invert |
| Recipient | that `to` matches one of its mailboxes (by hashing its own UUIDs) |
| Server (gateway) | the UUID from `to_enc`, and can verify `to` against it |
| Anyone holding the UUID | can compute `to` and recognise the mailbox — the UUID is the bearer anyway |

### 14.3 Server key distribution

- `GET /settings` carries the server's OpenPGP public key (and a key id/fingerprint) plus the
  identity period. The reply is **signed with that same key**, so the client can pin the key the
  first time and detect a swap. Rotation ships the new key alongside the old one for at least one
  queue lifetime, so envelopes encrypted to the previous key still route.
- The client never sends its own key material in `mailbox_encrypted`; only the mailbox UUID.

### 14.4 Why `mailbox_encrypted` if the server can hash too?

The server could compute `sha256("<bucket>|<uuid>")` for every mailbox it hosts and match `to`.
`mailbox_encrypted` is not required for that. It is kept because it gives O(1) routing for
relayed envelopes, avoids scanning every mailbox per bucket, and lets the server verify that
`to` really belongs to the UUID it decrypted (binding). If that is not wanted, drop it and route
purely by hash lookup.

### 14.5 What this does not hide

- The sender's and recipient's **traffic patterns** (timing, sizes, frequency) are still visible
  to relays.
- The server already knows the UUID (it is the bearer); `to_enc` hides the UUID from relays, not
  from the server.
- `id` is stable across relays for dedupe, so the same message can be correlated between relays,
  but not linked to a mailbox.
