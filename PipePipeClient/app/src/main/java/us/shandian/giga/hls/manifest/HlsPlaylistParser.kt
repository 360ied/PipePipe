package us.shandian.giga.hls.manifest

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist as ExoHlsMediaPlaylist
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist.Segment as ExoHlsSegment
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist.SegmentBase as ExoHlsSegmentBase
import com.google.android.exoplayer2.source.hls.playlist.HlsMultivariantPlaylist as ExoHlsMultivariantPlaylist
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser as ExoHlsPlaylistParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI

// Internal: the only consumer is the internal HlsManifestResolver.
internal class HlsPlaylistParser {
    @Throws(IOException::class)
    fun parse(sourceUrl: String, body: String): HlsPlaylist {
        val input = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        return when (val playlist = ExoHlsPlaylistParser().parse(Uri.parse(sourceUrl), input)) {
            is ExoHlsMediaPlaylist -> playlist.toInternalMediaPlaylist(sourceUrl)
            is ExoHlsMultivariantPlaylist -> playlist.toInternalMasterPlaylist(sourceUrl)
            // Deterministic: retrying an unparseable playlist is pointless
            // (TransferRetryPolicy excludes this type).
            else -> throw UnsupportedHlsPlaylistException("Unsupported HLS playlist type: ${playlist.javaClass.name}")
        }
    }

    private fun ExoHlsMultivariantPlaylist.toInternalMasterPlaylist(sourceUrl: String): HlsMasterPlaylist {
        return HlsMasterPlaylist(
            sourceUrl = sourceUrl,
            variants = variants.map { variant ->
                val format = variant.format
                HlsVariant(
                    url = resolveUrl(sourceUrl, variant.url.toString()),
                    bandwidth = positiveLong(format.peakBitrate) ?: positiveLong(format.bitrate),
                    averageBandwidth = positiveLong(format.averageBitrate),
                )
            },
        )
    }

    private fun ExoHlsMediaPlaylist.toInternalMediaPlaylist(sourceUrl: String): HlsMediaPlaylist {
        // The engine prepends a single init: a playlist rotating EXT-X-MAP
        // mid-list would join later segments to the wrong init (remux
        // failure or corrupt output), so reject instead of collapsing.
        // Checked here rather than validatePlaylist, which only sees the
        // collapsed model. Keyed on (url, byteRange): same-URL range
        // rotation is a different init too.
        val distinctInits = segments.mapNotNull { segment ->
            segment.initializationSegment?.let { init ->
                Triple(init.url, init.byteRangeLength, init.byteRangeOffset)
            }
        }.toSet()
        if (distinctInits.size > 1) {
            throw UnsupportedHlsPlaylistException("Multiple HLS init segments are not supported")
        }
        val initSegment = segments
            .firstOrNull { it.initializationSegment != null }
            ?.initializationSegment
            ?.toInternalInitSegment(sourceUrl)
        return HlsMediaPlaylist(
            sourceUrl = sourceUrl,
            mediaSequence = mediaSequence,
            segments = segments.map { it.toInternalSegment(sourceUrl) },
            isEndList = hasEndTag,
            initSegment = initSegment,
            hasUnsupportedEncryption = hasUnsupportedEncryption(),
        )
    }

    private fun ExoHlsSegment.toInternalSegment(sourceUrl: String): HlsSegment {
        return HlsSegment(
            url = resolveUrl(sourceUrl, url),
            durationSeconds = secondsOf(durationUs),
            byteRange = byteRange(),
            encryptionKey = encryptionKey(sourceUrl),
        )
    }

    private fun ExoHlsSegment.toInternalInitSegment(sourceUrl: String): HlsInitSegment {
        return HlsInitSegment(
            url = resolveUrl(sourceUrl, url),
            byteRange = byteRange(),
            encryptionKey = encryptionKey(sourceUrl),
        )
    }

    private fun ExoHlsMediaPlaylist.hasUnsupportedEncryption(): Boolean {
        return protectionSchemes != null || tags.any { tag ->
            if (!tag.startsWith(EXT_X_KEY)) {
                false
            } else {
                val method = keyMethod(tag)
                method != null
                    && !method.equals("NONE", ignoreCase = true)
                    && !method.equals("AES-128", ignoreCase = true)
            }
        }
    }

    private fun ExoHlsSegmentBase.byteRange(): HlsByteRange? {
        if (byteRangeLength <= 0) {
            return null
        }
        return HlsByteRange(byteRangeLength, byteRangeOffset.takeIf { it >= 0 })
    }

    private fun ExoHlsSegmentBase.encryptionKey(sourceUrl: String): HlsEncryptionKey? {
        val keyUri = fullSegmentEncryptionKeyUri ?: return null
        return HlsEncryptionKey(
            method = "AES-128",
            url = resolveUrl(sourceUrl, keyUri),
            iv = encryptionIV,
        )
    }

    private fun keyMethod(tag: String): String? {
        // Hoisted: key-rotated playlists would otherwise recompile the
        // pattern per EXT-X-KEY tag.
        return KEY_METHOD_PATTERN
            .find(tag.substringAfter(':', ""))
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"')
    }

    private fun positiveLong(value: Int): Long? {
        return value.takeIf { it > 0 }?.toLong()
    }

    private fun secondsOf(valueUs: Long): Double? {
        return valueUs.takeIf { it != C.TIME_UNSET && it >= 0 }?.let { it / 1_000_000.0 }
    }

    private fun resolveUrl(sourceUrl: String, reference: String): String {
        // URI throws unchecked IllegalArgumentException AND checked
        // URISyntaxException on malformed input; wrap both as deterministic
        // playlist failure so the IOException contract and retry
        // exclusions hold.
        return try {
            URI(sourceUrl).resolve(reference).toString()
        } catch (error: Exception) {
            throw UnsupportedHlsPlaylistException("Invalid HLS playlist URL: ${error.message.orEmpty()}")
        }
    }

    private companion object {
        const val EXT_X_KEY = "#EXT-X-KEY:"
        val KEY_METHOD_PATTERN = Regex("(?:^|,)METHOD=([^,]+)")
    }
}
