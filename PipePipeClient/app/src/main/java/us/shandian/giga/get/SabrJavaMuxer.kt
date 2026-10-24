package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.streams.Mp4FromDashWriter
import org.schabi.newpipe.streams.WebMReader
import org.schabi.newpipe.streams.WebMWriter
import us.shandian.giga.io.FileStream
import java.io.File
import java.io.IOException

/**
 * Pure-Java SABR remuxer replacing [SabrFfmpegMuxer].
 *
 * SABR temp files are init + media segments concatenated per track — the same
 * shape classic DASH feeds the in-repo Java muxers. Dispatch on the formats'
 * mimeType (mp4 vs webm, via [YoutubeSabrFormatTimeline] rules):
 * - mp4+mp4 -> [Mp4FromDashWriter]
 * - webm+webm -> [WebMWriter] (video track from video source, audio from audio)
 * - single input -> direct copy for webm/webm (legacy fast path), otherwise
 *   single-track Java mux
 * - mixed mp4<->webm -> fail closed with [SabrDownloadException.Reason.MUXING],
 *   never a corrupt output.
 */
internal class SabrJavaMuxer(
    private val mission: DownloadMission,
) {
    @Throws(IOException::class, InterruptedException::class)
    fun remuxAndCopy(inputs: List<File>, targets: List<SabrDownloadTarget>, workDir: File): Long {
        if (canCopySingleInputDirectly(inputs, targets)) {
            logDebug("copySingleInputDirectly input=${inputs.first().length()}")
            return copyOutputToStorage(inputs.first())
        }
        val output = File(workDir, "output.${outputExtension()}")
        remux(inputs, targets, output)
        return try {
            copyOutputToStorage(output)
        } finally {
            deleteQuietly(output)
        }
    }

    @Throws(IOException::class)
    private fun remux(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        mission.psState = 1
        mission.writeThisToFile()
        try {
            dispatchRemux(inputs, targets, output)
        } catch (error: SabrDownloadException) {
            mission.psState = 0
            throw error
        } catch (error: IOException) {
            mission.psState = 0
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (${error.message.orEmpty()})",
                error,
            )
        } catch (error: Exception) {
            mission.psState = 0
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (${error.message.orEmpty()})",
                if (error is IOException) error else IOException(error.message, error),
            )
        }
    }

    @Throws(IOException::class)
    private fun dispatchRemux(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        if (inputs.isEmpty() || targets.isEmpty() || inputs.size != targets.size) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (inputs=${inputs.size} targets=${targets.size})",
            )
        }
        inputs.forEach { input ->
            if (!input.exists() || input.length() <= 0) {
                throw SabrDownloadException(
                    SabrDownloadException.Reason.MUXING,
                    "SABR download failed: java mux failed (missing input ${input.name})",
                )
            }
        }

        val containers = targets.map { target ->
            val mime = target.format.mimeType?.lowercase().orEmpty()
            when {
                mime.contains("mp4") -> Container.MP4
                mime.contains("webm") -> Container.WEBM
                else -> Container.UNKNOWN
            }
        }
        logDebug("remux inputs=${inputs.size} output=${output.name} containers=$containers")

        if (containers.any { it == Container.UNKNOWN }) {
            val mimeList = targets.map { it.format.mimeType }
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (unsupported mime $mimeList)",
            )
        }
        if (containers.distinct().size > 1) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (mixed containers $containers, itags=${targets.map { it.format.itag }})",
            )
        }

        // Fresh output: FileStream opens "rw" without truncating.
        if (output.exists() && !output.delete()) {
            throw IOException("Could not reset java mux output ${output.absolutePath}")
        }

        when (containers.first()) {
            Container.MP4 -> remuxMp4(inputs, output)
            Container.WEBM -> remuxWebm(inputs, targets, output)
            Container.UNKNOWN -> throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (unknown container)",
            )
        }
    }

    @Throws(IOException::class)
    private fun remuxMp4(inputs: List<File>, output: File) {
        val sources = inputs.map { FileStream(it) }
        var muxer: Mp4FromDashWriter? = null
        var out: FileStream? = null
        var completed = false
        try {
            muxer = Mp4FromDashWriter(*sources.toTypedArray())
            muxer.parseSources()
            // Validate each source exposes at least one track (index 0 is the
            // only track for single-track DASH/SABR fragments).
            sources.indices.forEach { index ->
                val tracks = muxer.getTracksFromSource(index)
                if (tracks.isEmpty()) {
                    throw IOException("MP4 source $index has no tracks")
                }
            }
            muxer.selectTracks(*IntArray(sources.size) { 0 })
            if (mission.kind == 'a') {
                // Audio-only MP4 must carry the M4A brand (M4aNoDash precedent).
                muxer.setMainBrand(0x4D344120)
            }
            out = FileStream(output)
            out.setLength(0)
            muxer.build(out)
            completed = true
        } finally {
            try {
                muxer?.close()
            } catch (ignored: Exception) {
            }
            // Mp4FromDashWriter.close() already closes sources; FileStream.close
            // is idempotent so double-close is safe.
            sources.forEach {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
            try {
                out?.close()
            } catch (ignored: Exception) {
            }
            if (!completed) {
                deleteQuietly(output)
            }
        }
    }

    @Throws(IOException::class)
    private fun remuxWebm(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        val sources = inputs.map { FileStream(it) }
        var muxer: WebMWriter? = null
        var out: FileStream? = null
        var completed = false
        try {
            muxer = WebMWriter(*sources.toTypedArray())
            muxer.parseSources()
            val indexes = IntArray(sources.size) { sourceIndex ->
                val tracks = muxer.getTracksFromSource(sourceIndex)
                if (tracks.isEmpty()) {
                    throw IOException("WebM source $sourceIndex has no tracks")
                }
                val wantVideo = targets[sourceIndex].format.isVideo
                pickWebmTrack(tracks, wantVideo)
            }
            muxer.selectTracks(*indexes)
            out = FileStream(output)
            out.setLength(0)
            muxer.build(out)
            completed = true
        } finally {
            try {
                muxer?.close()
            } catch (ignored: Exception) {
            }
            sources.forEach {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
            try {
                out?.close()
            } catch (ignored: Exception) {
            }
            if (!completed) {
                deleteQuietly(output)
            }
        }
    }

    private fun pickWebmTrack(tracks: Array<WebMReader.WebMTrack>, wantVideo: Boolean): Int {
        // Unlike WebMMuxer (audio-per-source for its DASH case), SABR needs the
        // video track from the video source. Mind the "fake video cover track"
        // quirk: enumerate via kind, don't assume index 0.
        for (index in tracks.indices) {
            val kind = tracks[index].kind
            if (wantVideo && kind == WebMReader.TrackKind.Video) {
                return index
            }
            if (!wantVideo && kind == WebMReader.TrackKind.Audio) {
                return index
            }
        }
        throw SabrDownloadException(
            SabrDownloadException.Reason.MUXING,
            "SABR download failed: java mux failed (no ${if (wantVideo) "video" else "audio"} WebM track)",
        )
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun copyOutputToStorage(output: File): Long {
        var copied = 0L
        try {
            output.inputStream().use { input ->
                mission.storage.getStream().use { storage ->
                    storage.setLength(0)
                    storage.seek(0)
                    val buffer = ByteArray(DownloadMission.BUFFER_SIZE)
                    while (true) {
                        ensureRunning()
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        storage.write(buffer, 0, read)
                        copied += read.toLong()
                    }
                }
            }
        } catch (error: IOException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not write final file",
                error,
            )
        }
        return copied
    }

    @Throws(InterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw InterruptedException()
        }
    }

    private fun outputExtension(): String {
        val name = mission.storage.name ?: return "mp4"
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return extension.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() } } ?: "mp4"
    }

    private fun canCopySingleInputDirectly(
        inputs: List<File>,
        targets: List<SabrDownloadTarget>,
    ): Boolean {
        if (inputs.size != 1 || targets.size != 1) {
            return false
        }
        val extension = outputExtension().lowercase()
        val mimeType = targets.first().format.mimeType ?: return false
        return extension == "webm" && mimeType.contains("webm", ignoreCase = true)
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (ignored: Exception) {
        }
    }

    private enum class Container {
        MP4,
        WEBM,
        UNKNOWN,
    }

    private companion object {
        private const val TAG = "SabrJavaMuxer"
    }
}
