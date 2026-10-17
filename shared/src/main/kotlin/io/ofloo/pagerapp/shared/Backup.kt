package io.ofloo.pagerapp.shared

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealed backup of everything the app cannot re-derive: the mailbox UUID, the OpenPGP key pair,
 * contacts (with their channel mailboxes) and the message history.
 *
 * The payload is opaque here — the platform layer builds it as JSON — because a backup containing
 * a private key must never be written in the clear. It is encrypted with a passphrase the user
 * chooses, using PBKDF2-HMAC-SHA256 and AES-256-GCM (authenticated, so tampering is detected).
 *
 * Envelope format (line based, no JSON dependency):
 *
 * ```
 * pagerapp-backup-v1
 * <base64 salt>
 * <iterations>
 * <base64 nonce>
 * <base64 ciphertext+tag>
 * ```
 */
object Backup {

    const val FORMAT = "pagerapp-backup-v1"

    private const val PROVIDER = "BC"
    private const val ITERATIONS = 200_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private fun ensureProvider() {
        val existing = Security.getProvider(PROVIDER)
        if (existing !is BouncyCastleProvider) {
            Security.removeProvider(PROVIDER)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Encrypts [payload] (JSON, built by the caller) under [passphrase]. */
    fun seal(payload: String, passphrase: CharArray): String {
        ensureProvider()
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding", PROVIDER)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        val sealed = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        return buildString {
            appendLine(FORMAT)
            appendLine(encoder.encodeToString(salt))
            appendLine(ITERATIONS)
            appendLine(encoder.encodeToString(nonce))
            appendLine(encoder.encodeToString(sealed))
        }.trimEnd()
    }

    /**
     * Decrypts a sealed backup. Throws when the passphrase is wrong or the envelope was altered —
     * callers must treat that as "restore failed", never as empty data.
     */
    fun open(envelope: String, passphrase: CharArray): String {
        ensureProvider()
        val lines = envelope.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size == 5 && lines[0] == FORMAT) { "not a PagerAPP backup" }

        val decoder = Base64.getDecoder()
        val salt = decoder.decode(lines[1])
        val iterations = lines[2].toIntOrNull() ?: error("bad iteration count")
        val nonce = decoder.decode(lines[3])
        val sealed = decoder.decode(lines[4])

        val cipher = Cipher.getInstance("AES/GCM/NoPadding", PROVIDER)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, iterations),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        return cipher.doFinal(sealed).toString(Charsets.UTF_8)
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", PROVIDER)
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        // The derived bytes are the AES-256 key.
        return SecretKeySpec(key, "AES")
    }
}
