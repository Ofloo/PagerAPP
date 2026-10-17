package io.ofloo.pagerapp.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {

    private val payload = """{"uuid":"abc","secret":"-----BEGIN PGP PRIVATE KEY BLOCK-----"}"""

    @Test
    fun roundTripsAPayload() {
        val sealed = Backup.seal(payload, "correct horse".toCharArray())
        assertTrue(sealed.startsWith(Backup.FORMAT))
        assertFalse(sealed.contains("PRIVATE KEY"), "the envelope must not leak the payload")
        assertEquals(payload, Backup.open(sealed, "correct horse".toCharArray()))
    }

    @Test
    fun theWrongPassphraseFails() {
        val sealed = Backup.seal(payload, "one".toCharArray())
        assertFailsWith<Exception> { Backup.open(sealed, "two".toCharArray()) }
    }

    @Test
    fun tamperingIsDetected() {
        val sealed = Backup.seal(payload, "pw".toCharArray())
        val lines = sealed.lines().toMutableList()
        // Flip the ciphertext body: GCM authentication must reject it.
        lines[4] = if (lines[4].first() == 'A') "B" + lines[4].drop(1) else "A" + lines[4].drop(1)
        assertFailsWith<Exception> { Backup.open(lines.joinToString("\n"), "pw".toCharArray()) }
    }

    @Test
    fun rejectsSomethingElseEntirely() {
        assertFailsWith<Exception> { Backup.open("hello\nworld", "pw".toCharArray()) }
    }
}
