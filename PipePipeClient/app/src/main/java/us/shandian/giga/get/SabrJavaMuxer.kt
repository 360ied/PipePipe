package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.streams.Mp4DashReader
import org.schabi.newpipe.streams.Mp4FromDashWriter
import org.schabi.newpipe.streams.WebMReader
import org.schabi.newpipe.streams.WebMWriter
import us.shandian.giga.io.FileStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Pure-Java SABR remuxer (replaces the retired ffmpeg-kit wrapper).
 *
 * SABR temp files are init + media segments concatenated per track — the same
 * shape classic DASH feeds the in-repo Java muxers. Dispatch on the formats'
 * mimeType (mp4 vs webm, same case-sensitive contains rules as
 * YoutubeSabrFormatTimeline):
 * - mp4+mp4 -> [Mp4FromDashWriter], track picked by audio/video kind
 * - webm+webm -> [WebMWriter] (video track from video source, audio from audio)
 * - single input -> direct copy for webm/webm (legacy fast path), otherwise
 *   single-track Java mux with verbatim-copy fallback for very short clips
 *   ([Mp4FromDashWriter] needs >= 8 samples per track)
 * - mixed mp4<->webm -> fail closed with [SabrDownloadException.Reason.MUXING],
 *   never a corrupt output.
 */
internal class SabrJavaMuxer(
    private val mission: DownloadMission,
) {
    @Throws(IOException::class, InterruptedException::class)
    fun remuxAndCopy(inputs: List<File>, targets: List<SabrDownloadTarget>, workDir: File): Long {
        requireValidInputs(inputs, targets)
        if (canCopySingleInputDirectly(inputs, targets)) {
            logDebug("copySingleInputDirectly input=${inputs.first().length()}")
            mission.psState = 1
            mission.writeThisToFile()
            try {
                return copyOutputToStorage(inputs.first())
            } catch (error: InterruptedException) {
                mission.psState = 0
                mission.writeThisToFile()
                throw error
            } catch (error: IOException) {
                mission.psState = 0
                mission.writeThisToFile()
                throw error
            }
        }
        val output = File(workDir, "output.${storageExtension()}")
        remux(inputs, targets, output)
        try {
            return copyOutputToStorage(output)
        } catch (error: InterruptedException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
        } catch (error: IOException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
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
            mission.writeThisToFile()
            throw error
        } catch (error: InterruptedException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
        } catch (error: IOException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (${error.message.orEmpty()})",
                error,
            )
        } catch (error: Exception) {
            mission.psState = 0
            mission.writeThisToFile()
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (${error::class.simpleName}: ${error.message.orEmpty()})",
                error,
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun requireValidInputs(inputs: List<File>, targets: List<SabrDownloadTarget>) {
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
                    "SABR download failed: java mux failed (missing input ${input.absolutePath})",
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun storageExtension(): String {
        // StoredFileHelper.getName() throws on invalid storage; map that to
        // STORAGE rather than letting it escape as an unknown error.
        val name = try {
            mission.storage.name
        } catch (error: IllegalStateException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: invalid storage (${error.message.orEmpty()})",
                error,
            )
        }
        return downloadOutputExtension(name)
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun dispatchRemux(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        requireValidInputs(inputs, targets)
        // Same case-sensitive contains rules as YoutubeSabrFormatTimeline.parse.
        val containers = targets.map { target ->
            val mime = target.format.mimeType.orEmpty()
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
                "SABR download failed: java mux failed (incompatible mixed containers " +
                    "$containers, itags=${targets.map { it.format.itag }}, " +
                    "mimes=${targets.map { it.format.mimeType }})",
            )
        }

        // The output filename selects the container; never write bytes the
        // suffix mislabels. WEBM family deliberately covers user-renamed
        // audio suffixes (weba) and Matroska names (mkv/mka): WebM bytes
        // remain playable under them (.opus is excluded — conventionally
        // Ogg-Opus), while failing the download over a rename would be
        // worse. Anything else fails closed.
        val extension = storageExtension().lowercase()
        val unanimous = containers.first()
        val agrees = when (unanimous) {
            Container.MP4 -> extension in MP4_FAMILY_EXTENSIONS
            Container.WEBM -> extension in WEBM_FAMILY_EXTENSIONS
            Container.UNKNOWN -> false
        }
        if (!agrees) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (output .$extension disagrees " +
                    "with $unanimous inputs, mimes=${targets.map { it.format.mimeType }})",
            )
        }

        when (unanimous) {
            Container.MP4 -> remuxMp4(inputs, targets, output)
            Container.WEBM -> remuxWebm(inputs, targets, output)
            Container.UNKNOWN -> throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (unknown container)",
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun remuxMp4(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        var sources: List<FileStream> = emptyList()
        var muxer: Mp4FromDashWriter? = null
        var out: FileStream? = null
        var completed = false
        try {
            sources = openSources(inputs)
            ensureRunning()
            muxer = Mp4FromDashWriter(*sources.toTypedArray())
            muxer.parseSources()
            ensureRunning()
            val indexes = IntArray(sources.size) { sourceIndex ->
                val tracks = muxer.getTracksFromSource(sourceIndex)
                pickMp4Track(tracks, wantVideoFor(targets[sourceIndex]), sourceIndex)
            }
            muxer.selectTracks(*indexes)
            ensureRunning()
            if (targets.all { it.format.isAudio }) {
                // Audio-only MP4 must carry the M4A brand (M4aNoDash
                // precedent). Keyed on target composition, not mission kind,
                // so a video byte can never land in an M4A-branded file.
                muxer.setMainBrand(M4A_BRAND)
            }
            // Fresh output: FileStream opens "rw" without truncating an
            // existing file, so delete first and truncate as belt-and-braces.
            if (output.exists() && !output.delete()) {
                throw IOException("Could not reset java mux output ${output.absolutePath}")
            }
            out = FileStream(output)
            out.setLength(0)
            try {
                muxer.build(out)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                // Very short clips (< 8 samples/track) or odd writer
                // failures (Mp4FromDashWriter throws RuntimeException for
                // bad matrices/done-state): a single fragmented input is
                // already playable verbatim.
                if (sources.size != 1) {
                    throw error
                }
                logDebug("single-input mux failed, verbatim fallback: ${error.message.orEmpty()}")
                try {
                    out.close()
                } catch (ignored: Exception) {
                }
                out = null
                deleteQuietly(output)
                try {
                    copyVerbatim(inputs.first(), output)
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: IOException) {
                    // Temp work-dir I/O is STORAGE, not a mux failure.
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.STORAGE,
                        "SABR download failed: could not write temporary media (${error.message.orEmpty()})",
                        error,
                    )
                }
            }
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

    @Throws(IOException::class, InterruptedException::class)
    private fun remuxWebm(inputs: List<File>, targets: List<SabrDownloadTarget>, output: File) {
        var sources: List<FileStream> = emptyList()
        var muxer: WebMWriter? = null
        var out: FileStream? = null
        var completed = false
        try {
            sources = openSources(inputs)
            ensureRunning()
            muxer = WebMWriter(*sources.toTypedArray())
            muxer.parseSources()
            ensureRunning()
            val indexes = IntArray(sources.size) { sourceIndex ->
                val tracks = muxer.getTracksFromSource(sourceIndex)
                pickWebmTrack(tracks, wantVideoFor(targets[sourceIndex]), sourceIndex)
            }
            muxer.selectTracks(*indexes)
            ensureRunning()
            // Fresh output: see remuxMp4 comment.
            if (output.exists() && !output.delete()) {
                throw IOException("Could not reset java mux output ${output.absolutePath}")
            }
            out = FileStream(output)
            out.setLength(0)
            try {
                muxer.build(out)
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                // Same single-input verbatim argument as the MP4 path:
                // concatenated WebM clusters are already playable.
                if (sources.size != 1) {
                    throw error
                }
                logDebug("single-input WebM mux failed, verbatim fallback: ${error.message.orEmpty()}")
                try {
                    out.close()
                } catch (ignored: Exception) {
                }
                out = null
                deleteQuietly(output)
                try {
                    copyVerbatim(inputs.first(), output)
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: IOException) {
                    // Temp work-dir I/O is STORAGE, not a mux failure.
                    throw SabrDownloadException(
                        SabrDownloadException.Reason.STORAGE,
                        "SABR download failed: could not write temporary media (${error.message.orEmpty()})",
                        error,
                    )
                }
            }
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

    @Throws(IOException::class)
    private fun openSources(inputs: List<File>): List<FileStream> {
        // Opened streams are tracked incrementally so a throwing Nth
        // constructor (`FileStream` throws `FileNotFoundException`) cleans
        // up streams 0..N-1 instead of losing them with the unassigned list.
        val opened = mutableListOf<FileStream>()
        try {
            inputs.forEach { input ->
                // Read-only: a file deleted between the existence precheck
                // and now must fail instead of being recreated as 0 bytes.
                opened.add(FileStream(input, true))
            }
        } catch (error: Exception) {
            opened.forEach { closeQuietly(it) }
            throw error
        }
        return opened
    }

    private fun closeQuietly(stream: FileStream) {
        try {
            stream.close()
        } catch (ignored: Exception) {
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun copyVerbatim(input: File, output: File) {
        val buffer = ByteArray(DownloadMission.BUFFER_SIZE)
        input.inputStream().use { source ->
            output.outputStream().use { dest ->
                while (true) {
                    ensureRunning()
                    val read = source.read(buffer)
                    if (read == -1) {
                        return
                    }
                    dest.write(buffer, 0, read)
                }
            }
        }
    }

    private fun wantVideoFor(target: SabrDownloadTarget): Boolean {
        val format = target.format
        return when {
            format.isVideo -> true
            format.isAudio -> false
            else -> throw SabrDownloadException(
                SabrDownloadException.Reason.MUXING,
                "SABR download failed: java mux failed (unsupported mime ${format.mimeType})",
            )
        }
    }

    private fun pickMp4Track(
        tracks: Array<Mp4DashReader.Mp4Track>,
        wantVideo: Boolean,
        sourceIndex: Int,
    ): Int {
        // Same hazard as the WebM "fake video cover track": enumerate via kind,
        // don't assume index 0. An audio MP4 exposing cover art at index 0 must
        // not land a video track in an M4A-branded file.
        for (index in tracks.indices) {
            val kind = tracks[index].kind
            if (wantVideo && kind == Mp4DashReader.TrackKind.Video) {
                return index
            }
            if (!wantVideo && kind == Mp4DashReader.TrackKind.Audio) {
                return index
            }
        }
        throw SabrDownloadException(
            SabrDownloadException.Reason.MUXING,
            "SABR download failed: java mux failed (no ${if (wantVideo) "video" else "audio"} " +
                "MP4 track in source $sourceIndex)",
        )
    }

    private fun pickWebmTrack(
        tracks: Array<WebMReader.WebMTrack>,
        wantVideo: Boolean,
        sourceIndex: Int,
    ): Int {
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
            "SABR download failed: java mux failed (no ${if (wantVideo) "video" else "audio"} " +
                "WebM track in source $sourceIndex)",
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
        } catch (error: InterruptedException) {
            throw error
        } catch (error: InterruptedIOException) {
            // Cancellation via interruptible I/O is a pause, not an error.
            throw InterruptedException(error.message)
        } catch (error: IllegalStateException) {
            // StoredFileHelper.getStream() validates the helper; an
            // invalidated helper is STORAGE, not an unknown error.
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: invalid storage (${error.message.orEmpty()})",
                error,
            )
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

    private fun canCopySingleInputDirectly(
        inputs: List<File>,
        targets: List<SabrDownloadTarget>,
    ): Boolean {
        if (inputs.size != 1 || targets.size != 1) {
            return false
        }
        // Same case-sensitive rule as dispatch: a single WebM input whose
        // storage suffix is in the WebM family copies verbatim (WebM bytes
        // stay playable under weba/mkv/mka; .opus is excluded on purpose).
        // STORAGE-invalid names fail closed in storageExtension(); a fast path
        // must never silently copy.
        val extension = try {
            storageExtension().lowercase()
        } catch (error: SabrDownloadException) {
            return false
        }
        val mimeType = targets.first().format.mimeType ?: return false
        return extension in WEBM_FAMILY_EXTENSIONS && mimeType.contains("webm")
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
        private const val M4A_BRAND = 0x4D344120
        private val MP4_FAMILY_EXTENSIONS = setOf("mp4", "m4a", "m4v", "mov")
        // weba/mkv/mka carry WebM-compatible bytes; .opus is excluded on
        // purpose (conventionally Ogg-Opus, which strict players reject).
        private val WEBM_FAMILY_EXTENSIONS = setOf("webm", "weba", "mkv", "mka")
    }
}
