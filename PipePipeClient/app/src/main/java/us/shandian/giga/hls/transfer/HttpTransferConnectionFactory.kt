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
        val origin = try {
            URL(requestUrl).toURI()
        } catch (error: Exception) {
            throw IOException("Invalid URL", error)
        }
        if (origin.scheme?.lowercase(java.util.Locale.ROOT) != "http" &&
            origin.scheme?.lowercase(java.util.Locale.ROOT) != "https"
        ) {
            throw IOException("Unsupported URL scheme")
        }
        // Follow redirects manually so the Cookie header (session-scoped)
        // is only re-applied same-host+port; HttpURLConnection would retain
        // it across hosts on auto-redirect and leak the session.
        var current = requestUrl
        var redirects = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = config.connectTimeoutMillis
            connection.readTimeout = config.readTimeoutMillis
            connection.setRequestProperty("User-Agent", DownloaderImpl.USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            // Identity always: a compressed partial response would pass the 206
            // check yet fail byte-range accounting or corrupt the concat input.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (cookie != null && isSameHostPort(current, origin)) {
                connection.setRequestProperty("Cookie", cookie)
            }
            if (rangeStart != null) {
                connection.setRequestProperty("Range", formatRange(rangeStart, rangeEnd))
            }
            val status = connection.responseCode
            if (status !in REDIRECT_CODES || method != "GET") {
                return connection
            }
            if (redirects >= MAX_REDIRECTS) {
                connection.disconnect()
                throw IOException("Too many redirects")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw IOException("Redirect without Location")
            }
            current = try {
                URL(URL(current), location).toString()
            } catch (error: Exception) {
                throw IOException("Invalid redirect location", error)
            }
            redirects++
        }
    }

    private fun isSameHostPort(candidate: String, origin: java.net.URI): Boolean {
        return try {
            val url = URL(candidate)
            url.host.equals(origin.host, ignoreCase = true) && url.port == origin.toURL().port
        } catch (error: Exception) {
            false
        }
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
        // Malformed % escapes throw unchecked IllegalArgumentException:
        // wrap so the IOException contract and retry accounting hold.
        val cookie = try {
            URLDecoder.decode(encoded, Charsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid cookie encoding in URL", error)
        }
        // HttpURLConnection rejects embedded CR/LF with unchecked
        // IllegalArgumentException; reject first with a clean IOException.
        if (cookie.contains('\r') || cookie.contains('\n')) {
            throw IOException("Invalid cookie value in URL")
        }
        return cookie
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
    }
}

internal inline fun <T> HttpURLConnection.useTransferConnection(
    block: (HttpURLConnection) -> T,
): T {
    try {
        return block(this)
    } finally {
        disconnect()
    }
}
