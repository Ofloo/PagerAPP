package io.ofloo.pagerapp.shared

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PgpTest {

    @Test
    fun generatesATimeLimitedKeyRing() {
        val keys = Pgp.generate("Test <pagerapp>", expiryMonths = 6)
        assertTrue(keys.publicArmored.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertTrue(keys.secretArmored.contains("BEGIN PGP PRIVATE KEY BLOCK"))
        assertTrue(keys.fingerprint.length >= 32)
        assertFalse(Pgp.isExpired(keys.publicArmored))

        // Expiry must land between six and twelve months out.
        val now = System.currentTimeMillis() / 1000L
        val inSixMonths = now + TimeUnit.DAYS.toSeconds(6 * 30) - 86400
        val inTwelveMonths = now + TimeUnit.DAYS.toSeconds(12 * 30) + 86400
        assertTrue(
            keys.expiresAtEpochSeconds in inSixMonths..inTwelveMonths,
            "expiry=${keys.expiresAtEpochSeconds} now=$now",
        )
    }

    @Test
    fun roundTripsAnEncryptedMessage() {
        val bob = Pgp.generate("Bob <pagerapp>")
        val cipher = Pgp.encrypt("hello bob", bob.publicArmored)
        assertTrue(cipher.contains("BEGIN PGP MESSAGE"))
        assertFalse(cipher.contains("hello bob"))
        assertEquals("hello bob", Pgp.decrypt(cipher, bob.secretArmored))
    }

    @Test
    fun roundTripsBinaryDataAndFitsTheMessageLimit() {
        val bob = Pgp.generate("Bob <pagerapp>")
        val bytes = ByteArray(30_000) { (it * 31 % 256).toByte() }
        val cipher = Pgp.encryptBytes(bytes, bob.publicArmored)
        assertTrue(cipher.contains("BEGIN PGP MESSAGE"))
        assertTrue(cipher.length < 60_000, "armored size=${cipher.length}")
        assertTrue(Pgp.decryptBytes(cipher, bob.secretArmored).contentEquals(bytes))
    }

    @Test
    fun clampsTheExpiryIntoRange() {
        val short = Pgp.generate("x <pagerapp>", expiryMonths = 1)
        val long = Pgp.generate("x <pagerapp>", expiryMonths = 99)
        assertFalse(Pgp.isExpired(short.publicArmored))
        assertFalse(Pgp.isExpired(long.publicArmored))

        val six = Pgp.generate("x <pagerapp>", expiryMonths = 6).expiresAtEpochSeconds
        val clamped = Pgp.generate("x <pagerapp>", expiryMonths = 1).expiresAtEpochSeconds
        assertTrue(kotlin.math.abs(six - clamped) < 3 * 86400L)
    }

    /**
     * Cross-checks the generated material with the real `gpg` binary when it is available, so we
     * know the output is standard OpenPGP and not just self-consistent.
     */
    @Test
    fun gpgCanReadAndDecryptOurOutput() {
        val gpg = listOf("/usr/bin/gpg", "/usr/local/bin/gpg").map(::File).firstOrNull { it.canExecute() }
            ?: return   // no gpg on this machine; skip silently

        val bob = Pgp.generate("Bob <pagerapp>", expiryMonths = 6)
        val dir = File.createTempFile("pagerapp-gpg", "").apply { delete(); mkdirs() }
        dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true)
        try {
            val pubFile = File(dir, "pub.asc").apply { writeText(bob.publicArmored) }
            File(dir, "sec.asc").writeText(bob.secretArmored)

            val imported = run(
                gpg.absolutePath, "--homedir", dir.absolutePath, "--batch", "--import", pubFile.absolutePath,
            )
            val list = run(gpg.absolutePath, "--homedir", dir.absolutePath, "--list-keys")
            assertTrue(
                list.contains("Bob"),
                "gpg could not read our public key. import said:\n$imported\nlist said:\n$list",
            )

            run(gpg.absolutePath, "--homedir", dir.absolutePath, "--batch", "--import", File(dir, "sec.asc").absolutePath)

            val plain = File(dir, "msg.txt").apply { writeText("from gpg to pagerapp") }
            val cipherFile = File(dir, "msg.asc")
            run(
                gpg.absolutePath, "--homedir", dir.absolutePath, "--batch", "--yes", "--trust-model", "always",
                "--armor", "--output", cipherFile.absolutePath, "--encrypt", "-r", "Bob", plain.absolutePath,
            )
            // Our app must be able to decrypt what gpg produced.
            assertEquals("from gpg to pagerapp", Pgp.decrypt(cipherFile.readText(), bob.secretArmored))

            // And gpg must be able to decrypt what we produced.
            val ours = File(dir, "ours.asc")
            ours.writeText(Pgp.encrypt("from pagerapp to gpg", bob.publicArmored))
            val decrypted = run(
                gpg.absolutePath, "--homedir", dir.absolutePath, "--batch", "--yes",
                "--decrypt", ours.absolutePath,
            )
            assertTrue(decrypted.contains("from pagerapp to gpg"), "gpg could not decrypt our message:\n$decrypted")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(vararg cmd: String): String {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return output
    }
}

class SecureSendTest {
    private val server = "https://pager.ofloo.io"

    @Test
    fun theFirstMessageCarriesTheKeyAndLaterOnesAreEncrypted() {
        val aliceKeys = Pgp.generate("Alice <pagerapp>", 6)
        val bobKeys = Pgp.generate("Bob <pagerapp>", 6)

        // Bob already learned Alice's key from a previous message.
        val toAlice = Contact(
            name = "Alice", serverUrl = server, uuid = "chan",
            senderId = "alice-sid", mySenderId = "bob-sid",
            publicKey = aliceKeys.publicArmored,
        )
        val body = buildOutgoing(toAlice, "Bob", "hello", myPublicArmored = bobKeys.publicArmored)

        assertEquals(bobKeys.publicArmored, body["pub"])
        assertEquals(null, body["message"], "should not send plaintext once a key is known")
        assertTrue((body["enc"] as String).contains("BEGIN PGP MESSAGE"))

        val page = Page(
            id = "1", status = STATUS_MESSAGE, from = "bob-sid",
            pub = body["pub"] as String, enc = body["enc"] as String,
        )
        assertEquals(bobKeys.publicArmored, publicKeyFrom(page))
        assertEquals("hello", readableText(page, aliceKeys.secretArmored))
    }

    @Test
    fun withoutAPeerKeyTheTextIsPlainButOurKeyStillTravels() {
        val mine = Pgp.generate("Me <pagerapp>", 6)
        val unknown = Contact(name = "", serverUrl = server, uuid = "chan", mySenderId = "me-sid")

        val body = buildOutgoing(unknown, "Me", "hi", myPublicArmored = mine.publicArmored)
        assertEquals("hi", body["message"])
        assertEquals(null, body["enc"])
        assertEquals(mine.publicArmored, body["pub"])
    }

    @Test
    fun aReadReceiptReferencesTheMessageItReads() {
        val to = Contact(name = "Alice", serverUrl = server, uuid = "chan", mySenderId = "me-sid")
        val receipt = buildReadReceipt(to, mid = "out-123")

        assertEquals("out-123", receipt["read"])
        assertEquals("me-sid", receipt["from"])
        assertEquals(null, receipt["message"], "a receipt is not a message")
    }

    @Test
    fun outgoingMessagesCarryTheirOwnId() {
        val to = Contact(name = "Alice", serverUrl = server, uuid = "chan", mySenderId = "me-sid")
        val body = buildOutgoing(to, "Me", "hi", mid = "out-1")
        assertEquals("out-1", body["mid"])
    }

    @Test
    fun parsesReceiptFieldsFromTheWire() {
        val page = PageParser.parse(
            mapOf("id" to "m1", "status" to STATUS_MESSAGE, "mid" to "out-1", "read" to "out-2"),
        )
        assertEquals("out-1", page.mid)
        assertEquals("out-2", page.read)
    }
}
