package io.ofloo.pagerapp.ui

import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.shared.sha256Hex
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * transfer.sh-compatible share host for chat photos.
 *
 * The image is GPG-encrypted before upload, so the host never sees it. The URL is sent to the peer
 * over the normal encrypted chat channel, and the file lives for the host's retention period
 * (fetched from its landing page, default 3 days).
 *
 * The base URL is configurable in Settings; both devices must use the same host so the receiver can
 * validate and download the link.
 */
object PhotoShare {

    /** The public URL and the one-shot transfer.sh delete URL returned by the host. */
    data class Upload(val url: String, val deleteUrl: String?)

    private const val MAX_DOWNLOAD_BYTES = 20_000_000
    private const val RETENTION_DEFAULT = 3
    private const val RETENTION_CACHE_MS = 24L * 60L * 60L * 1000L

    private val retentionCache = ConcurrentHashMap<String, Pair<Int, Long>>()

    fun baseUrl(configured: String?): String =
        configured?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: Store.DEFAULT_PHOTO_SHARE

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    /** True when [url] is an HTTPS URL on the configured share host. */
    fun isAllowed(configured: String, url: String): Boolean {
        val base = baseUrl(configured)
        val baseHost = hostOf(base) ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(baseHost, ignoreCase = true) &&
            uri.userInfo == null
    }

    /** The host's advertised retention, parsed from its landing page; 3 when unavailable. */
    fun retentionDays(configured: String): Int {
        val base = baseUrl(configured)
        val now = System.currentTimeMillis()
        retentionCache[base]?.let { (days, at) ->
            if (now - at < RETENTION_CACHE_MS) return days
        }
        val fetched = runCatching {
            val conn = (URL("$base/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html")
                setRequestProperty("User-Agent", "PagerAPP")
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText().take(200_000) }
                Regex("""Files stored for\s+(\d+)\s+days?""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.toIntOrNull()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()?.takeIf { it in 1..365 }
        val days = fetched ?: RETENTION_DEFAULT
        retentionCache[base] = days to now
        return days
    }

    /**
     * PUTs the armored ciphertext to the configured share host. Returns the public HTTPS URL, or
     * null on any failure. The file gets a random name; nothing about the image is in the path.
     */
    fun upload(configured: String, armor: String): Upload? = runCatching {
        val base = baseUrl(configured)
        val host = hostOf(base) ?: return@runCatching null
        if (!base.startsWith("https://", ignoreCase = true)) return@runCatching null
        val days = retentionDays(base)
        val bytes = armor.toByteArray(Charsets.UTF_8)
        val name = "pagerapp-${UUID.randomUUID()}.asc"
        val conn = (URL("$base/$name").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "PUT"
            doOutput = true
            instanceFollowRedirects = false
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Max-Days", days.toString())
            setRequestProperty("User-Agent", "PagerAPP")
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) return@runCatching null
            val url = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!isAllowed(base, url) || !hostOf(url).equals(host, ignoreCase = true)) {
                return@runCatching null
            }
            val deleteUrl = conn.getHeaderField("X-Url-Delete")
                ?.trim()
                ?.takeIf { isAllowed(base, it) && hostOf(it).equals(host, ignoreCase = true) }
            Upload(url, deleteUrl)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Deletes an uploaded file after a successful local download, when the host gave a token. */
    fun delete(configured: String, deleteUrl: String?): Boolean {
        if (deleteUrl.isNullOrBlank()) return false
        val base = baseUrl(configured)
        if (!isAllowed(base, deleteUrl)) return false
        return runCatching {
            val conn = (URL(deleteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                requestMethod = "DELETE"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PagerAPP")
            }
            try {
                conn.responseCode in 200..299 || conn.responseCode == 404
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Downloads an uploaded armored blob and verifies it against [expectedSha256]. Only HTTPS URLs
     * on the configured host are accepted, and redirects are not followed, so a peer cannot turn
     * this into an SSRF primitive.
     */
    fun download(configured: String, url: String, expectedSha256: String): String? = runCatching {
        if (!isAllowed(configured, url)) return@runCatching null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PagerAPP")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val declared = conn.contentLengthLong
            if (declared > MAX_DOWNLOAD_BYTES) return@runCatching null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) return@runCatching null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (sha256Hex(text) != expectedSha256.lowercase()) return@runCatching null
            text
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
