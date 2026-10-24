package io.ofloo.pagerapp.shared

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A pointer to a larger photo uploaded to a share host. The file itself was encrypted to the peer
 * before upload, so the URL is only useful together with the peer's private key. [days] is the
 * host's retention, fetched at upload time (default 3).
 */
data class PhotoLink(
    val url: String,
    val sha256: String,
    val days: Int = 3,
    val deleteUrl: String? = null,
) {
    fun encode(): String = buildString {
        append("pagerapp://photo?u=").append(encodePart(url))
        append("&h=").append(sha256)
        append("&d=").append(days)
        deleteUrl?.takeIf { it.isNotBlank() }?.let { append("&x=").append(encodePart(it)) }
    }

    companion object {
        private const val PREFIX = "pagerapp://photo?"

        fun parse(text: String?): PhotoLink? {
            val raw = text?.trim() ?: return null
            if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
            val query = raw.substring(PREFIX.length)
            val values = query.split('&').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val url = values["u"]?.let { decodePart(it) } ?: return null
            val sha = values["h"]?.lowercase() ?: return null
            if (!url.startsWith("https://", ignoreCase = true)) return null
            if (!sha.matches(Regex("[0-9a-f]{64}"))) return null
            val days = values["d"]?.toIntOrNull()?.takeIf { it in 1..365 } ?: 3
            val deleteUrl = values["x"]?.let { decodePart(it) }
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            return PhotoLink(url, sha, days, deleteUrl)
        }

        private fun encodePart(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decodePart(value: String): String =
            runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull() ?: value
    }
}
