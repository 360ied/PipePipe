package us.shandian.giga.hls.transfer

import org.schabi.newpipe.DownloaderImpl
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

internal class HttpTransferConnectionFactory(
    private val config: HttpTransferConfig,
) {
    @Throws(IOException::class)
    fun open(
        rawUrl: String,
        method: String,
        rangeStart: Long?,
        rangeEnd: Long?,
    ): HttpURLConnection {
        val requestUrl = stripCookieFragment(rawUrl)
        val cookie = extractCookie(rawUrl)
        // Fail closed on non-HTTP(S) schemes: `as HttpURLConnection` below
        // would throw ClassCastException, bypassing the IOException contract
        // and retry accounting.
        val scheme = try {
            URL(requestUrl).toURI().scheme?.lowercase(java.util.Locale.ROOT)
        } catch (error: Exception) {
            null
        }
        if (scheme != "http" && scheme != "https") {
            throw IOException("Unsupported URL scheme")
        }
        val connection = URL(requestUrl).openConnection() as HttpURLConnection

        connection.instanceFollowRedirects = true
        connection.requestMethod = method
        connection.connectTimeout = config.connectTimeoutMillis
        connection.readTimeout = config.readTimeoutMillis
        connection.setRequestProperty("User-Agent", DownloaderImpl.USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        // Identity always: a compressed partial response would pass the 206
        // check yet fail byte-range accounting or corrupt the concat input.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie)
        }
        if (rangeStart != null) {
            connection.setRequestProperty("Range", formatRange(rangeStart, rangeEnd))
        }

        return connection
    }

    private fun formatRange(rangeStart: Long, rangeEnd: Long?): String {
        return buildString {
            append("bytes=")
            append(rangeStart)
            append('-')
            if (rangeEnd != null && rangeEnd >= 0) {
                append(rangeEnd)
            }
        }
    }

    private fun stripCookieFragment(rawUrl: String): String {
        return rawUrl.substringBefore("#cookie=")
    }

    private fun extractCookie(rawUrl: String): String? {
        val marker = "#cookie="
        val start = rawUrl.indexOf(marker)
        if (start < 0) {
            return null
        }
        val encoded = rawUrl.substring(start + marker.length).substringBefore('&')
        return URLDecoder.decode(encoded, Charsets.UTF_8.name())
    }
}

inline fun <T> HttpURLConnection.useTransferConnection(
    block: (HttpURLConnection) -> T,
): T {
    try {
        return block(this)
    } finally {
        disconnect()
    }
}
