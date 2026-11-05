# PagerAPP P2P transport protocol

Status: draft v0.1  
Scope: phone app first (Android), BLE first; Wi-Fi Direct next; LoRa/T-Deck later  
Encoding: compact CBOR on the wire; JSON examples below are only for readability

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
