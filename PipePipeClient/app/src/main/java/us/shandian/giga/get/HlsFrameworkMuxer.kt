package us.shandian.giga.get

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.schabi.newpipe.BuildConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Framework HLS remuxer replacing ffmpeg `-c copy` in [HlsDownloader].
 *
 * Inputs are per-resource concatenations (init first, then segments in order;
 * AES-128 already decrypted, byte-ranges resolved). This is demux-then-mux
 * sample copying via [MediaExtractor] -> [MediaMuxer], zero new dependencies.
 *
 * Mapping mirrors today's `-map` semantics:
 * - audio-only single-input (`kind == 'a'`) -> audio tracks only
 * - otherwise -> first video + first audio per input (`-map i:v? -map i:a?`)
 *
 * [MediaMuxer] requires non-decreasing PTS per track while ffmpeg tolerated
 * overlaps, so per-track timestamps are rewritten monotonically (shifted,
 * never aborted for merely sloppy sources).
 */
internal object HlsFrameworkMuxer {
    private const val TAG = "HlsFrameworkMuxer"

    @Throws(IOException::class)
    fun remux(inputs: List<File>, output: File, kind: Char, outputExtension: String) {
        if (inputs.isEmpty()) {
            throw IOException("HLS framework remux failed: no inputs")
        }
        inputs.forEach { input ->
            if (!input.exists() || input.length() <= 0) {
                throw IOException("HLS framework remux failed: missing input ${input.name}")
            }
        }

        val ext = outputExtension.lowercase().takeIf { it.isNotBlank() } ?: "mp4"
        val isAudioOnly = kind == 'a' && inputs.size == 1

        // Phase 1: inspect every input, decide selected tracks.
        val inspected = inputs.mapIndexed { inputIndex, input ->
            inspectInput(input, inputIndex, isAudioOnly)
        }
        val selectedFormats = inspected.flatMap { it.selectedFormats }
        if (selectedFormats.isEmpty()) {
            inspected.forEach { it.releaseQuietly() }
            throw IOException("HLS framework remux failed: no audio/video tracks found")
        }

        // MP3 fast path: remuxing MP3->MP4 via MediaMuxer is unsupported and
        // pointless when container already matches (mirrors single-WebM copy).
        if (ext == "mp3") {
            try {
                if (selectedFormats.all { it.mime == "audio/mpeg" }) {
                    logDebug("mp3 concat inputs=${inputs.size}")
                    concatInputs(inputs, output)
                    return
                }
            } finally {
                inspected.forEach { it.releaseQuietly() }
            }
            throw IOException("HLS framework remux failed: non-MP3 tracks for .mp3 output")
        }

        val muxerFormat = muxerOutputFormat(ext)
        if (output.exists() && !output.delete()) {
            inspected.forEach { it.releaseQuietly() }
            throw IOException("HLS framework remux failed: cannot reset output")
        }
        output.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                inspected.forEach { it.releaseQuietly() }
                throw IOException("HLS framework remux failed: cannot create output dir")
            }
        }

        var muxer: MediaMuxer? = null
        var completed = false
        try {
            muxer = MediaMuxer(output.absolutePath, muxerFormat)
            // Phase 2: add every selected track (1:1 per input-track, like
            // ffmpeg's per-input -map which yields separate output streams).
            val muxerTrackFor = mutableListOf<MutableMap<Int, Int>>()
            val lastPts = mutableListOf<Long>()
            inspected.forEach { info ->
                val mapping = mutableMapOf<Int, Int>()
                info.selected.forEach { selection ->
                    try {
                        val muxerIndex = muxer.addTrack(selection.format)
                        mapping[selection.extractorIndex] = muxerIndex
                        // Grow lastPts so muxerIndex is addressable.
                        while (lastPts.size <= muxerIndex) {
                            lastPts.add(Long.MIN_VALUE)
                        }
                    } catch (error: Exception) {
                        throw IOException(
                            "HLS framework remux failed: addTrack ${selection.mime} unsupported (${error.message.orEmpty()})",
                            error as? IOException ?: IOException(error.message, error),
                        )
                    }
                }
                muxerTrackFor.add(mapping)
            }
            if (lastPts.isEmpty()) {
                throw IOException("HLS framework remux failed: no tracks added")
            }

            muxer.start()
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
                    val extractorTrack = try {
                        info.extractor.sampleTrackIndex
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleTrackIndex (${error.message.orEmpty()})", toIo(error))
                    }
                    if (extractorTrack < 0) {
                        break
                    }
                    val muxerTrack = mapping[extractorTrack]
                        ?: throw IOException("HLS framework remux failed: unexpected track $extractorTrack")
                    // Grow buffer if the current sample demands it.
                    val sampleSize: Long = try {
                        info.extractor.sampleSize
                    } catch (error: Exception) {
                        -1L
                    }
                    if (sampleSize > buffer.capacity().toLong()) {
                        buffer = ByteBuffer.allocate(sampleSize.coerceAtMost(MAX_BUFFER_SIZE.toLong()).toInt())
                    }
                    bufferInfo.offset = 0
                    bufferInfo.size = try {
                        info.extractor.readSampleData(buffer, 0)
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: read (${error.message.orEmpty()})", toIo(error))
                    }
                    if (bufferInfo.size < 0) {
                        break
                    }
                    val rawPts = try {
                        info.extractor.sampleTime
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: sampleTime", toIo(error))
                    }
                    bufferInfo.presentationTimeUs = rewritePts(lastPts, muxerTrack, rawPts).also {
                        if (it != rawPts) {
                            corrections++
                        }
                    }
                    bufferInfo.flags = try {
                        info.extractor.sampleFlags
                    } catch (error: Exception) {
                        0
                    }
                    // CSD is carried in MediaFormat; skip codec-config samples.
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        try {
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                        } catch (error: Exception) {
                            throw IOException("HLS framework remux failed: write (${error.message.orEmpty()})", toIo(error))
                        }
                        samples++
                    }
                    try {
                        info.extractor.advance()
                    } catch (error: Exception) {
                        throw IOException("HLS framework remux failed: advance", toIo(error))
                    }
                }
            }
            logDebug("muxed samples=$samples corrections=$corrections format=$ext")
            completed = true
        } finally {
            inspected.forEach { it.releaseQuietly() }
            try {
                if (muxer != null) {
                    try {
                        muxer.stop()
                    } catch (ignored: Exception) {
                        // stop() throws if no samples written; release still runs.
                    }
                    muxer.release()
                }
            } catch (ignored: Exception) {
            }
            if (!completed) {
                try {
                    output.delete()
                } catch (ignored: Exception) {
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
        val selectedFormats: List<TrackSelection> get() = selected

        fun selectAll() {
            selected.forEach { (index, _) ->
                extractor.selectTrack(index)
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
            throw IOException("HLS framework remux failed: cannot parse input-$inputIndex (${error.message.orEmpty()})", toIo(error))
        }
        val trackCount = try {
            extractor.trackCount
        } catch (error: Exception) {
            extractor.release()
            throw IOException("HLS framework remux failed: no tracks in input-$inputIndex", toIo(error))
        }
        val allTracks = (0 until trackCount).mapNotNull { index ->
            try {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                TrackSelection(index, format, mime)
            } catch (ignored: Exception) {
                null
            }
        }
        val selected = if (isAudioOnly) {
            allTracks.filter { it.mime.startsWith("audio/") }
        } else {
            val video = allTracks.firstOrNull { it.mime.startsWith("video/") }
            val audio = allTracks.firstOrNull { it.mime.startsWith("audio/") }
            listOfNotNull(video, audio)
        }
        if (selected.isEmpty()) {
            // Keep extractor open for caller cleanup symmetry? No — release now
            // and return empty selection; caller reports "no tracks found".
            // To keep lifecycle simple, return with extractor (caller releases).
        }
        return InspectedInput(extractor, selected)
    }

    @Throws(IOException::class)
    private fun muxerOutputFormat(ext: String): Int {
        // minSdk is 30, so WebM muxer output (API 26+) is always available.
        return when (ext) {
            "mp4", "m4a", "mov", "m4v" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }
    }

    private fun rewritePts(lastPts: MutableList<Long>, muxerTrack: Int, rawPts: Long): Long {
        val last = lastPts[muxerTrack]
        if (last == Long.MIN_VALUE || rawPts > last) {
            lastPts[muxerTrack] = rawPts
            return rawPts
        }
        // Non-monotonic source PTS (TS overlaps/discontinuities ffmpeg
        // tolerated): shift forward minimally, count for debug logs.
        val corrected = last + 1
        lastPts[muxerTrack] = corrected
        return corrected
    }

    @Throws(IOException::class)
    private fun concatInputs(inputs: List<File>, output: File) {
        if (output.exists() && !output.delete()) {
            throw IOException("HLS framework remux failed: cannot reset output")
        }
        output.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("HLS framework remux failed: cannot create output dir")
            }
        }
        try {
            output.outputStream().use { out ->
                inputs.forEach { input ->
                    input.inputStream().use { it.copyTo(out) }
                }
            }
        } catch (error: IOException) {
            try {
                output.delete()
            } catch (ignored: Exception) {
            }
            throw IOException("HLS framework remux failed: concat (${error.message.orEmpty()})", error)
        }
    }

    private fun toIo(error: Exception): IOException {
        return if (error is IOException) error else IOException(error.message, error)
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    private const val INITIAL_BUFFER_SIZE = 512 * 1024
    private const val MAX_BUFFER_SIZE = 8 * 1024 * 1024
}
