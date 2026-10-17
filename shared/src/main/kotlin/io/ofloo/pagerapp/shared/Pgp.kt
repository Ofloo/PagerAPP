package io.ofloo.pagerapp.shared

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * OpenPGP (GPG) keys for private contact messaging.
 *
 * A key ring is generated on the device: an **Ed25519** primary key for signing/certification and
 * an **X25519** subkey for encryption, both expiring after a user-chosen period (6–12 months).
 * The keys are small enough to travel in a QR code or an NFC tag, and the output is standard
 * OpenPGP, so `gpg` can read, verify and decrypt it.
 *
 * Only the message body is encrypted; the transport fields PagerMCP itself needs (`id`, `status`,
 * `from`) stay in the clear.
 */
object Pgp {

    /** Key lifetime is deliberately bounded: at least half a year, at most a year. */
    const val MIN_EXPIRY_MONTHS = 6
    const val MAX_EXPIRY_MONTHS = 12
    const val DEFAULT_EXPIRY_MONTHS = 12

    private const val PROVIDER = "BC"
    private const val MONTH_SECONDS = 30L * 24L * 3600L

    data class KeyMaterial(
        val publicArmored: String,
        val secretArmored: String,
        val fingerprint: String,
        /** Absolute expiry (Unix seconds), computed from creation time + validity. */
        val expiresAtEpochSeconds: Long,
    )

    private fun ensureProvider() {
        // Android ships its own stripped provider already named "BC" (no SHA-1, no OpenPGP), so a
        // plain addProvider() is silently a no-op and our code would end up using that one.
        // Replace it with the real Bouncy Castle under the same name.
        val existing = Security.getProvider(PROVIDER)
        if (existing !is BouncyCastleProvider) {
            Security.removeProvider(PROVIDER)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generates a fresh, time-limited key ring for [userId] (e.g. `"Ofloo <pagerapp>"`).
     * [passphrase] protects the exported secret key; pass null for an unprotected one that the
     * platform layer is expected to encrypt at rest.
     */
    fun generate(
        userId: String,
        expiryMonths: Int = DEFAULT_EXPIRY_MONTHS,
        passphrase: CharArray? = null,
        now: Date = Date(),
    ): KeyMaterial {
        ensureProvider()
        val months = expiryMonths.coerceIn(MIN_EXPIRY_MONTHS, MAX_EXPIRY_MONTHS).toLong()
        val validitySeconds = Math.multiplyExact(months, MONTH_SECONDS)

        // Classic RSA: the one configuration Bouncy Castle and gpg agree on everywhere.
        val rsa = KeyPairGenerator.getInstance("RSA", PROVIDER).apply { initialize(2048) }
        val primaryPair = JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, rsa.generateKeyPair(), now)
        val subPair = JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_ENCRYPT, rsa.generateKeyPair(), now)

        val digests = JcaPGPDigestCalculatorProviderBuilder().setProvider(PROVIDER).build()
        val sha1 = digests.get(HashAlgorithmTags.SHA1)
        val signer = JcaPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256)
            .setProvider(PROVIDER)
        val encryptor = passphrase?.let {
            JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setProvider(PROVIDER)
                .build(it)
        }

        val primaryHashed = PGPSignatureSubpacketGenerator().apply {
            setSignatureCreationTime(false, now)
            setKeyExpirationTime(false, validitySeconds)
        }.generate()
        val subHashed = PGPSignatureSubpacketGenerator().apply {
            setSignatureCreationTime(false, now)
            setKeyExpirationTime(false, validitySeconds)
        }.generate()

        val primarySecret = PGPSecretKey(
            PGPSignature.DEFAULT_CERTIFICATION,
            primaryPair,
            userId,
            sha1,
            primaryHashed,
            null,
            signer,
            encryptor,
        )
        val subSecret = PGPSecretKey(
            subPair,
            primaryPair,
            sha1,
            subHashed,
            null,
            signer,
            encryptor,
        )

        val ring = PGPSecretKeyRing(listOf(primarySecret, subSecret))
        val certificate = ring.toCertificate()
        val createdSeconds = certificate.publicKey.creationTime.time / 1000L

        return KeyMaterial(
            publicArmored = armorOf { certificate.encode(it) },
            secretArmored = armorOf { ring.encode(it) },
            fingerprint = fingerprintOf(certificate.publicKey),
            expiresAtEpochSeconds = createdSeconds + certificate.publicKey.validSeconds,
        )
    }

    /** Encrypts [plaintext] to the holder of [recipientPublicArmored]. */
    fun encrypt(plaintext: String, recipientPublicArmored: String): String {
        ensureProvider()
        val recipient = readPublicKey(recipientPublicArmored)
        val bytes = plaintext.toByteArray(Charsets.UTF_8)

        val literal = ByteArrayOutputStream()
        val literalGenerator = PGPLiteralDataGenerator()
        literalGenerator.open(literal, PGPLiteralData.BINARY, "pagerapp", bytes.size.toLong(), Date())
            .use { it.write(bytes) }
        val literalBytes = literal.toByteArray()

        val out = ByteArrayOutputStream()
        val generator = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
                .setProvider(PROVIDER),
        )
        generator.addMethod(
            JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider(PROVIDER),
        )
        generator.open(out, literalBytes.size.toLong()).use { it.write(literalBytes) }

        return armorOf { out.writeTo(it) }
    }

    /** Decrypts an armored message produced by [encrypt]. */
    fun decrypt(armored: String, secretArmored: String, passphrase: CharArray? = null): String {
        ensureProvider()
        val secretRing = PGPSecretKeyRing(
            PGPUtil.getDecoderStream(ByteArrayInputStream(secretArmored.toByteArray())),
            JcaKeyFingerprintCalculator(),
        )

        val factory = JcaPGPObjectFactory(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray())),
        )
        var first: Any? = factory.nextObject()
        if (first is PGPMarker) first = factory.nextObject()
        val encryptedList = first as? PGPEncryptedDataList
            ?: error("not an encrypted OpenPGP message")

        var encrypted: PGPPublicKeyEncryptedData? = null
        for (i in 0 until encryptedList.size()) {
            val candidate = encryptedList.get(i)
            if (candidate is PGPPublicKeyEncryptedData) {
                encrypted = candidate
                break
            }
        }
        val enc = encrypted ?: error("no encrypted data in the message")

        val secretKey = secretRing.getSecretKey(enc.keyID)
            ?: secretRing.getSecretKey()
            ?: error("no matching secret key")
        val privateKey = secretKey.extractPrivateKey(
            passphrase?.let {
                JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER).build(it)
            },
        )

        val clear = enc
            .getDataStream(JcePublicKeyDataDecryptorFactoryBuilder().setProvider(PROVIDER).build(privateKey))
            .use { it.readBytes() }

        var objectFactory = JcaPGPObjectFactory(ByteArrayInputStream(clear))
        var obj: Any? = objectFactory.nextObject()
        if (obj is PGPCompressedData) {
            objectFactory = JcaPGPObjectFactory(obj.dataStream)
            obj = objectFactory.nextObject()
        }
        val literal = obj as? PGPLiteralData ?: error("no literal data in the message")
        return literal.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    /** The encryption-capable public key from an armored public key block. */
    fun readPublicKey(armored: String): PGPPublicKey {
        ensureProvider()
        val rings = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        return rings.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .first { it.isEncryptionKey }
    }

    /** Fingerprint of an armored public key block (hex, no spaces). */
    fun fingerprintOfArmored(armored: String): String = fingerprintOf(readPublicKey(armored))

    /** True when the key's own validity has already lapsed. */
    fun isExpired(armored: String, atEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        val key = readPublicKey(armored)
        val created = key.creationTime.time / 1000L
        val valid = key.validSeconds
        return valid > 0 && atEpochSeconds > created + valid
    }

    private fun fingerprintOf(key: PGPPublicKey): String =
        key.fingerprint.joinToString("") { "%02X".format(it) }

    private inline fun armorOf(write: (OutputStream) -> Unit): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use(write)
        return out.toString("UTF-8")
    }
}
