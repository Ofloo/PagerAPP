package io.ofloo.pagerapp.data

import io.ofloo.pagerapp.shared.P2PEnvelope
import org.json.JSONObject

/** JSON storage for the P2P queue. The wire format is documented in docs/p2p-protocol.md. */
fun P2PEnvelope.toJson(): JSONObject = JSONObject()
    .put("v", 1)
    .put("id", id)
    .put("from", from)
    .put("to", to)
    .put("created_at", createdAt)
    .put("expires_at", expiresAt)
    .put("payload", payload)
    .put("sig", signature)
    .put("hops", hops)

fun p2pEnvelopeFromJson(json: JSONObject): P2PEnvelope = P2PEnvelope(
    id = json.optString("id"),
    from = json.optString("from"),
    to = json.optString("to"),
    createdAt = json.optLong("created_at", 0L),
    expiresAt = json.optLong("expires_at", 0L),
    payload = json.optString("payload"),
    signature = json.optString("sig").takeIf { it.isNotBlank() && it != "null" },
    hops = json.optInt("hops", 0),
)
