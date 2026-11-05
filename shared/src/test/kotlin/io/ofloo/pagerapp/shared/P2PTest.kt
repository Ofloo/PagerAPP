package io.ofloo.pagerapp.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class P2PTest {

    private val text = "P2P envelope\nwith ünïcode and a few chunks."
    private val bytes = text.toByteArray(Charsets.UTF_8)
    private val sha = sha256Hex(bytes)

    @Test
    fun chunksReassembleOutOfOrderAndVerifyTheWholeHash() {
        val chunks = P2PChunker.split("transfer-1", bytes, chunkSize = 9)
        val assembler = P2PAssembler("transfer-1", chunks.size, sha)
        chunks.reversed().forEach { assertTrue(assembler.accept(it)) }
        assertTrue(assembler.isComplete)
        assertTrue(assembler.finish()!!.contentEquals(bytes))
    }

    @Test
    fun duplicateChunksAreIdempotent() {
        val chunks = P2PChunker.split("transfer-2", bytes, 7)
        val assembler = P2PAssembler("transfer-2", chunks.size, sha)
        chunks.forEach { assertTrue(assembler.accept(it)) }
        chunks.forEach { assertTrue(assembler.accept(it)) }
        assertTrue(assembler.finish()!!.contentEquals(bytes))
    }

    @Test
    fun corruptChunkIsRejected() {
        val chunk = P2PChunker.split("transfer-3", bytes, 8).first()
        val corrupt = chunk.copy(data = chunk.data.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        val assembler = P2PAssembler("transfer-3", chunk.total, sha)
        assertFalse(assembler.accept(corrupt))
        assertFalse(assembler.isComplete)
    }

    @Test
    fun missingChunkKeepsTransferIncomplete() {
        val chunks = P2PChunker.split("transfer-4", bytes, 6)
        val assembler = P2PAssembler("transfer-4", chunks.size, sha)
        chunks.dropLast(1).forEach(assembler::accept)
        assertFalse(assembler.isComplete)
        assertNull(assembler.finish())
    }

    @Test
    fun wrongWholePayloadHashIsRejected() {
        val chunks = P2PChunker.split("transfer-5", bytes, 5)
        val assembler = P2PAssembler("transfer-5", chunks.size, "0".repeat(64))
        chunks.forEach(assembler::accept)
        assertTrue(assembler.isComplete)
        assertNull(assembler.finish())
    }

    @Test
    fun syncPlanOnlyRequestsMissingEnvelopes() {
        val plan = P2PSyncPlan.between(
            localIds = listOf("a", "b", "c"),
            remoteIds = listOf("b", "c", "d", "e"),
        )
        assertEquals(listOf("d", "e"), plan.want)
        assertEquals(listOf("a"), plan.have)
    }

    @Test
    fun localTargetAndExpiryAreDeterministic() {
        assertTrue(isLocalP2PTarget("fingerprint-1", setOf("fingerprint-1", "fingerprint-2")))
        assertFalse(isLocalP2PTarget("fingerprint-3", setOf("fingerprint-1")))

        val envelope = P2PEnvelope(
            id = "e1", from = "a", to = "b", createdAt = 100, expiresAt = 200,
            payload = "{}",
        )
        assertFalse(envelope.isExpired(199))
        assertTrue(envelope.isExpired(200))
        assertTrue(envelope.isExpired(201))
    }

    @Test
    fun ackMustMatchTheEnvelopeHash() {
        val envelope = P2PEnvelope(
            id = "e2", from = "a", to = "b", createdAt = 1, expiresAt = 2,
            payload = text,
        )
        assertTrue(
            P2PAck("t1", envelope.id, envelope.payloadSha256(), stored = true).matches(envelope),
        )
        assertFalse(
            P2PAck("t1", envelope.id, "0".repeat(64), stored = true).matches(envelope),
        )
        assertFalse(
            P2PAck("t1", envelope.id, envelope.payloadSha256(), stored = false).matches(envelope),
        )
    }
}
