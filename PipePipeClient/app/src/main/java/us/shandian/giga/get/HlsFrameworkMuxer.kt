package us.shandian.giga.get

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.schabi.newpipe.BuildConfig
import us.shandian.giga.hls.transfer.TransferInterruptedException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Framework HLS remuxer replacing ffmpeg `-c copy` in [HlsDownloader].
 *
 * Inputs are per-resource concatenations (init first, then segments in order;
 * AES-128 already decrypted, byte-ranges resolved). This is demux-then-mux
 * sample copying via [MediaExtractor] -> [MediaMuxer], zero new dependencies.
 *
 * Track policy keeps at most one video track (the first across inputs, in
 * resource order) plus every audio track: video variants are mutually
 * exclusive renditions that must never play simultaneously, while alternate
 * audio dubs must survive. Audio-only single-input (`kind == 'a'`) missions
 * keep all audio tracks; subtitle missions (`kind == 's'`) are explicitly
 * unsupported.
 *
 * Known [MediaMuxer] parity gaps vs the old `-movflags +faststart` command:
 * [MediaMuxer] writes `moov` at the end (no front-loading) and offers no
 * output-brand API, so MP4 time-to-play/seek and `.m4a` branding may regress.
 * Accepted until a reserve-and-backpatch pass proves necessary.
 *
 * [MediaMuxer] requires non-decreasing PTS per track while ffmpeg tolerated
 * overlaps, so per-track timestamps are shifted by an offset that preserves
 * deltas (never collapsed, never aborted for merely sloppy sources).
 */
internal object HlsFrameworkMuxer {
    private const val TAG = "HlsFrameworkMuxer"
    private const val KIND_AUDIO = 'a'
    private const val KIND_SUBTITLES = 's'

    @Throws(IOException::class)
    fun remux(inputs: List<File>, output: File, kind: Char, extIn: String) {
        if (inputs.isEmpty()) {
            throw IOException("HLS framework remux failed: no inputs")
        }
        inputs.forEach { input ->
            if (!input.exists() || input.length() <= 0) {
                throw IOException("HLS framework remux failed: missing input ${input.name}")
            }
        }
        if (kind == KIND_SUBTITLES) {
            throw IOException("HLS framework remux failed: subtitles unsupported")
        }

        val ext = extIn.lowercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "mp4"
        // Validate the container before opening any extractor: only containers
        // MediaMuxer understands are accepted (mp3 handled below via concat).
        // Anything else fails closed instead of writing mislabeled MP4 bytes.
        if (ext != "mp3" && ext !in SUPPORTED_MUX_EXTENSIONS) {
            throw IOException("HLS framework remux failed: unsupported output .$ext")
        }
        val isAudioOnly = kind == KIND_AUDIO && inputs.size == 1

        // Phase 1: inspect every input, decide selected tracks. Release
        // already-opened extractors if a later input fails to parse.
        val inspected = mutableListOf<InspectedInput>()
        try {
            inputs.forEachIndexed { inputIndex, input ->
                inspected.add(inspectInput(input, inputIndex, isAudioOnly))
            }
        } catch (error: IOException) {
            inspected.forEach { it.releaseQuietly() }
            throw error
        } catch (error: Exception) {
            inspected.forEach { it.releaseQuietly() }
            throw IOException("HLS framework remux failed: cannot parse inputs", error)
        }
        if (inspected.sumOf { it.selected.size } == 0) {
            inspected.forEach { it.releaseQuietly() }
            throw IOException("HLS framework remux failed: no audio/video tracks found")
        }
        // First video across inputs wins: two video-bearing resources (e.g. a
        // muxed video variant mirrored in both) must not yield two simultaneous
        // video tracks the way per-input `-map i:v?` would.
        // inspectInput already caps each input at one video, so the first
        // video-bearing input keeps its track and every later video goes.
        var videoKept = false
        inspected.forEachIndexed { index, info ->
            val videos = info.selected.filter { it.mime.startsWith("video/") }
            if (videos.isEmpty()) {
                return@forEachIndexed
            }
            if (!videoKept) {
                videoKept = true
            } else {
                inspected[index] = InspectedInput(info.extractor, info.selected - videos.toSet())
            }
        }
        // The pre-dedup check above already guaranteed non-empty, and dedup
        // only removes duplicate videos while keeping the first — the list
        // cannot become empty here.
        logDebug("inputs=${inputs.size} tracks=${inspected.map { it.selected.map { s -> s.mime } }}")

        // MP3 fast path: remuxing MP3->MP4 via MediaMuxer is unsupported and
        // pointless when the container already matches (same idea as
        // SabrJavaMuxer.canCopySingleInputDirectly for single WebM). The mime
        // describes the codec track, not the file container, so probe for raw
        // MP3 bytes (TS/fMP4-wrapped MP3 must not be concatenated as .mp3).
        if (ext == "mp3") {
            try {
                if (inspected.flatMap { it.selected }.all { it.mime == "audio/mpeg" } &&
                    inputs.all { isRawMp3(it) }
                ) {
                    logDebug("mp3 concat inputs=${inputs.size}")
                    concatInputs(inputs, output)
                    return
                }
            } finally {
                inspected.forEach { it.releaseQuietly() }
            }
            // Fires when a track is not audio/mpeg or the bytes are not a
            // raw MP3 container (TS/fMP4-wrapped MP3 must not concatenate).
            throw IOException("HLS framework remux failed: not raw MP3 for .mp3 output")
        }

        if (output.exists() && !output.delete()) {
            inspected.forEach { it.releaseQuietly() }
            throw IOException("HLS framework remux failed: cannot reset output")
        }
        output.parentFile?.let { parent ->
            // Triple-check like the work-dir paths: mkdirs() false covers
            // both real failure and a lost creation race.
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                inspected.forEach { it.releaseQuietly() }
                throw IOException("HLS framework remux failed: cannot create output dir")
            }
        }

        var muxer: MediaMuxer? = null
        var completed = false
        try {
            // Wrap the constructor like every other muxer call: it throws
            // unchecked IllegalArgumentException/IllegalStateException
            // despite the IOException contract.
            muxer = try {
                // muxerOutputFormat is infallible here (ext validated above).
                MediaMuxer(output.absolutePath, muxerOutputFormat(ext, isAudioOnly))
            } catch (error: TransferInterruptedException) {
                // Never wrap pause as failure.
                throw error
            } catch (error: Exception) {
                throw IOException("HLS framework remux failed: muxer (${error.message.orEmpty()})", error)
            }
            // Phase 2: add every selected track (1:1 per input-track).
            val muxerTrackFor = mutableListOf<MutableMap<Int, Int>>()
            val lastPts = mutableListOf<Long>()
            val ptsOffset = mutableListOf<Long>()
            inspected.forEach { info ->
                val mapping = mutableMapOf<Int, Int>()
                info.selected.forEach { selection ->
                    try {
                        val muxerIndex = muxer.addTrack(selection.format)
                        mapping[selection.extractorIndex] = muxerIndex
                        // Grow PTS state so muxerIndex is addressable.
                        while (lastPts.size <= muxerIndex) {
                            lastPts.add(Long.MIN_VALUE)
                            ptsOffset.add(0L)
                        }
                    } catch (error: Exception) {
                        throw IOException(
                            "HLS framework remux failed: addTrack ${selection.mime} unsupported (${error.message.orEmpty()})",
                            error,
                        )
                    }
                }
                muxerTrackFor.add(mapping)
            }
            try {
                muxer.start()
            } catch (error: Exception) {
                throw IOException("HLS framework remux failed: muxer start (${error.message.orEmpty()})", error)
            }
            var corrections = 0
            var samples = 0
            // Phase 3: drain each input in order, preserving file-order
            // interleaving by selecting all desired tracks at once.
            inspected.forEachIndexed { inputIndex, info ->
                info.selectAll()
                val mapping = muxerTrackFor[inputIndex]
                var buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE)
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        Thread.currentThread().interrupt()
                        throw TransferInterruptedException()
                    }
                    val extractorTrack = try {
                        info.extractor.sampleTrackIndex
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleTrackIndex (${error.message.orEmpty()})", error)
                    }
                    if (extractorTrack < 0) {
                        break
                    }
                    val muxerTrack = mapping[extractorTrack]
                        ?: throw IOException("HLS framework remux failed: unexpected track $extractorTrack")
                    // Fail closed instead of truncating: an undersized buffer
                    // would write a corrupt sample as if valid. 8 MiB covers
                    // even large 4K keyframes (ceiling picked with headroom,
                    // not a tunable); anything larger is rejected rather
                    // than silently clipped. A failed size query must throw, never -1,
                    // or every guard below would accept a truncation.
                    val sampleSize: Long = try {
                        info.extractor.sampleSize
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleSize", error)
                    }
                    if (sampleSize > MAX_BUFFER_SIZE.toLong()) {
                        throw IOException("HLS framework remux failed: sample too large ($sampleSize)")
                    }
                    if (sampleSize > buffer.capacity().toLong()) {
                        buffer = ByteBuffer.allocate(sampleSize.toInt())
                    }
                    buffer.clear()
                    bufferInfo.offset = 0
                    bufferInfo.size = try {
                        info.extractor.readSampleData(buffer, 0)
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: read (${error.message.orEmpty()})", error)
                    }
                    if (bufferInfo.size < 0) {
                        break
                    }
                    // Guard against a misreporting extractor: a full buffer
                    // may hide a truncation. Grow past both the observed
                    // size and the prior capacity and re-read once; a second
                    // full buffer means the stream is not converging.
                    if (bufferInfo.size >= buffer.capacity()) {
                        val grown = minOf(
                            maxOf(bufferInfo.size.toLong() + 1, buffer.capacity().toLong() * 2),
                            MAX_BUFFER_SIZE.toLong(),
                        ).toInt()
                        if (grown <= buffer.capacity()) {
                            throw IOException("HLS framework remux failed: sample too large (truncated read)")
                        }
                        buffer = ByteBuffer.allocate(grown)
                        bufferInfo.offset = 0
                        bufferInfo.size = try {
                            info.extractor.readSampleData(buffer, 0)
                        } catch (error: Exception) {
                            throw IOException("HLS framework remux failed: read (${error.message.orEmpty()})", error)
                        }
                        if (bufferInfo.size < 0) {
                            break
                        }
                        if (bufferInfo.size >= buffer.capacity()) {
                            throw IOException("HLS framework remux failed: sample too large (truncated read)")
                        }
                    }
                    val rawPts = try {
                        info.extractor.sampleTime
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleTime", error)
                    }
                    if (rawPts < 0) {
                        throw IOException("HLS framework remux failed: negative timestamp")
                    }
                    bufferInfo.flags = try {
                        info.extractor.sampleFlags
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleFlags", error)
                    }
                    // CSD is carried in MediaFormat: drop codec-config samples
                    // before the PTS rewrite so they never shift track timing.
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        bufferInfo.presentationTimeUs = rewritePts(lastPts, ptsOffset, muxerTrack, rawPts).also {
                            if (it != rawPts) {
                                corrections++
                            }
                        }
                        try {
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                        } catch (error: Exception) {
                            throw IOException("HLS framework remux failed: write (${error.message.orEmpty()})", error)
                        }
                        samples++
                    }
                    try {
                        info.extractor.advance()
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: advance", error)
                    }
                }
            }
            if (samples == 0) {
                throw IOException("HLS framework remux failed: no samples written")
            }
            try {
                muxer.stop()
            } catch (error: Exception) {
                // stop() fails exactly when the output is unusable (no
                // trailer); never keep it as a completed download.
                throw IOException("HLS framework remux failed: muxer stop (${error.message.orEmpty()})", error)
            }
            logDebug("muxed samples=$samples corrections=$corrections format=$ext")
            completed = true
        } finally {
            inspected.forEach { it.releaseQuietly() }
            try {
                muxer?.release()
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "muxer release: ${error.message.orEmpty()}")
                }
            }
            if (!completed) {
                try {
                    output.delete()
                } catch (error: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "delete partial: ${error.message.orEmpty()}")
                    }
                }
            }
        }
    }

    private data class TrackSelection(
        val extractorIndex: Int,
        val format: MediaFormat,
        val mime: String,
    )

    private class InspectedInput(
        val extractor: MediaExtractor,
        val selected: List<TrackSelection>,
    ) {
        fun selectAll() {
            selected.forEach {
                extractor.selectTrack(it.extractorIndex)
            }
        }

        fun releaseQuietly() {
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
        }
    }

    @Throws(IOException::class)
    private fun inspectInput(input: File, inputIndex: Int, isAudioOnly: Boolean): InspectedInput {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (error: Exception) {
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
            throw IOException("HLS framework remux failed: cannot parse input-$inputIndex (${error.message.orEmpty()})", error)
        }
        val trackCount = try {
            extractor.trackCount
        } catch (error: Exception) {
            // Guard the release like the setDataSource catch: a throwing
            // release must not mask the IOException being built.
            try {
                extractor.release()
            } catch (ignored: Exception) {
            }
            throw IOException("HLS framework remux failed: no tracks in input-$inputIndex", error)
        }
        val allTracks = (0 until trackCount).mapNotNull { index ->
            try {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                TrackSelection(index, format, mime)
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "skip unreadable track $index in input-$inputIndex: ${error.message.orEmpty()}")
                }
                null
            }
        }
        // First video across inputs plus every audio track: video renditions
        // are mutually exclusive (never play two at once), alternate dubs
        // must survive.
        val selected = if (isAudioOnly) {
            allTracks.filter { it.mime.startsWith("audio/") }
        } else {
            val audio = allTracks.filter { it.mime.startsWith("audio/") }
            val video = allTracks.firstOrNull { it.mime.startsWith("video/") }
            listOfNotNull(video) + audio
        }
        return InspectedInput(extractor, selected)
    }

    @Throws(IOException::class)
    private fun muxerOutputFormat(ext: String, isAudioOnly: Boolean): Int {
        // minSdk is 30, so WebM muxer output (API 26+) is always available.
        // MediaMuxer always writes MP4 brands: .mov/.m4v would be mislabeled,
        // and .m4a must be audio-only (a video track under .m4a misleads).
        return when {
            ext == "mp4" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ext == "m4a" && isAudioOnly -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ext == "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            ext == "mp3" -> throw IOException("HLS framework remux failed: internal mp3 routing")
            else -> throw IOException("HLS framework remux failed: unsupported output .$ext")
        }
    }

    /**
     * True when [input] looks like raw MP3 bytes (ID3 tag or validated frame
     * sync), ruling out TS/fMP4/WebM-wrapped MP3 which must never be
     * concatenated. I/O failures propagate instead of masquerading as
     * "non-MP3" (which would misreport the error bucket).
     *
     * Known over-rejection: APEv2/ID3v1-only files without a leading sync
     * are rejected though concatenation would be valid.
     */
    @Throws(IOException::class)
    private fun isRawMp3(input: File): Boolean {
        return input.inputStream().use { stream ->
                val header = ByteArray(12)
                var read = 0
                while (read < header.size) {
                    val count = stream.read(header, read, header.size - read)
                    if (count == -1) {
                        break
                    }
                    read += count
                }
                if (read < 4) {
                    return false
                }
                // MP4 (....ftyp), EBML, OggS, FLAC, RIFF, TS sync: not raw MP3.
                if (read >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
                ) {
                    return false
                }
                if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte()) {
                    return false
                }
                if (header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()
                ) {
                    return false
                }
                if (read >= 4 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()
                ) {
                    return false
                }
                if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
                ) {
                    return false
                }
                if (header[0] == 0x47.toByte()) {
                    return false
                }
                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                ) {
                    return true
                }
                // MPEG frame sync plus valid version/layer bits: rejects
                // reserved combos and ADTS (layer 00), which the bare sync
                // mask would otherwise over-accept. Mask with 0xFF first so
                // sign-extended negative bytes read as unsigned.
                val second = header[1].toInt() and 0xFF
                if (header[0] != 0xFF.toByte() || (second and 0xE0) != 0xE0) {
                    return false
                }
                val versionBits = (second ushr 3) and 0x03
                val layerBits = (second ushr 1) and 0x03
                return versionBits != 0x01 && layerBits != 0x00
            }
        }

    private fun rewritePts(
        lastPts: MutableList<Long>,
        ptsOffset: MutableList<Long>,
        muxerTrack: Int,
        rawPts: Long,
    ): Long {
        val last = lastPts[muxerTrack]
        var offset = ptsOffset[muxerTrack]
        var corrected = rawPts + offset
        if (last == Long.MIN_VALUE || corrected > last) {
            lastPts[muxerTrack] = corrected
            return corrected
        }
        // Non-monotonic source PTS (TS overlaps, discontinuities, wraps — all
        // cases ffmpeg tolerated): shift the track offset forward so deltas
        // survive instead of collapsing every sample to last+1.
        offset += last - corrected + 1
        ptsOffset[muxerTrack] = offset
        corrected = rawPts + offset
        lastPts[muxerTrack] = corrected
        return corrected
    }

    @Throws(IOException::class)
    private fun concatInputs(inputs: List<File>, output: File) {
        if (output.exists() && !output.delete()) {
            throw IOException("HLS framework remux failed: cannot reset output")
        }
        output.parentFile?.let { parent ->
            // Triple-check like the work-dir paths: mkdirs() false covers
            // both real failure and a lost creation race.
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw IOException("HLS framework remux failed: cannot create output dir")
            }
        }
        try {
            val chunk = ByteArray(64 * 1024)
            output.outputStream().use { out ->
                inputs.forEach { input ->
                    input.inputStream().use { source ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                Thread.currentThread().interrupt()
                                throw TransferInterruptedException()
                            }
                            val read = source.read(chunk)
                            if (read == -1) {
                                break
                            }
                            out.write(chunk, 0, read)
                        }
                    }
                }
            }
        } catch (error: TransferInterruptedException) {
            // Unwrap: the caller distinguishes pause (TransferInterrupted
            // Exception) from failure (IOException) to decide silent return
            // vs notifyError.
            try {
                output.delete()
            } catch (ignored: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "delete partial MP3: ${ignored.message.orEmpty()}")
                }
            }
            throw error
        } catch (error: IOException) {
            // Log the ignored result: a failed delete leaves a stale
            // partial, which the next run still rejects via the reset check.
            try {
                if (!output.delete() && BuildConfig.DEBUG) {
                    Log.w(TAG, "delete stale MP3 concat output failed")
                }
            } catch (ignored: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "delete stale MP3 concat output failed: ${ignored.message.orEmpty()}")
                }
            }
            throw IOException("HLS framework remux failed: concat (${error.message.orEmpty()})", error)
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    // Accepted early (pre-inspect); muxerOutputFormat narrows further
    // (audio-only m4a; mov/m4v rejected as mislabeled MP4 brands).
    private val SUPPORTED_MUX_EXTENSIONS = setOf("mp4", "m4a", "webm")
    private const val INITIAL_BUFFER_SIZE = 512 * 1024
    private const val MAX_BUFFER_SIZE = 8 * 1024 * 1024
}
