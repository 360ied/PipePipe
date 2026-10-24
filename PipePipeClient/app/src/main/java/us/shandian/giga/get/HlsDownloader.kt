package us.shandian.giga.get

import android.util.Log
import org.schabi.newpipe.BuildConfig
import us.shandian.giga.hls.state.HlsDownloadCheckpoint
import us.shandian.giga.hls.state.HlsResourceCheckpoint
import us.shandian.giga.hls.transfer.HlsSegmentFileTransferEngine
import us.shandian.giga.hls.transfer.HttpTransferConfig
import us.shandian.giga.hls.transfer.HttpTransferConnectionFactory
import us.shandian.giga.hls.transfer.TransferController
import us.shandian.giga.hls.transfer.TransferHttpException
import us.shandian.giga.hls.transfer.TransferInterruptedException
import us.shandian.giga.hls.transfer.TransferRetryPolicy
import us.shandian.giga.hls.transfer.TransferProgress
import us.shandian.giga.hls.transfer.TransferProgressListener
import us.shandian.giga.hls.transfer.useTransferConnection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.util.Locale

internal class HlsDownloader(
    private val mission: DownloadMission,
) : Runnable {
    private val controller = object : TransferController {
        override fun isRunning(): Boolean = mission.running
    }
    // Coerced here so the HttpTransferConfig requires (added round-9) fire
    // only on genuine programmer error, never on user pref values: this
    // field initializer runs outside run()'s try/catch -> notifyError path.
    private val transferConfig = HttpTransferConfig(
        maxRetries = mission.maxRetry.coerceAtLeast(0),
        parallelism = mission.threadCount.coerceIn(1, MAX_PARALLELISM),
    )
    private val retryPolicy = TransferRetryPolicy(transferConfig.maxRetries)
    private val hlsTransfer = HlsSegmentFileTransferEngine(transferConfig, controller)
    private val connectionFactory = HttpTransferConnectionFactory(transferConfig)

    override fun run() {
        try {
            ensureRunning()
            if (mission.hlsCheckpoint == null) {
                mission.hlsCheckpoint = HlsDownloadCheckpoint()
            }
            mission.unknownLength = true
            mission.done = restoredProgress()
            mission.length = mission.done.coerceAtLeast(mission.nearLength)
            mission.writeThisToFile()

            val workDir = workDirectory(mission)
            // mkdirs() returns false both when creation failed and when a
            // concurrent creator won the race; re-check before failing.
            if (!workDir.exists() && !workDir.mkdirs() && !workDir.exists()) {
                throw IOException("Cannot create HLS work directory: $workDir")
            }

            remuxedOutputFromCheckpoint(workDir)?.let { output ->
                val finalBytes = copyWithPsStateReset { copyOutputToStorage(output) }
                completeMission(finalBytes)
                return
            }

            val inputs = mission.urls.indices.map { index -> File(workDir, "input-$index.media") }
            for (index in mission.urls.indices) {
                ensureRunning()
                mission.current = index
                if (isHlsResource(index)) {
                    transferHlsResource(index, inputs[index])
                } else {
                    transferDirectCompanionResource(index, inputs[index])
                }
            }

            ensureRunning()
            val output = File(workDir, "output.${outputExtension()}")
            remuxWithFramework(inputs, output)
            markRemuxOutput(output)
            val finalBytes = copyWithPsStateReset { copyOutputToStorage(output) }
            completeMission(finalBytes)
        } catch (error: TransferInterruptedException) {
            // Pause/stop requested. The mission state has already been persisted by checkpoints.
        } catch (error: TransferHttpException) {
            if (shouldRecover(error.statusCode)) {
                mission.doRecover(error.statusCode)
            } else {
                mission.notifyError(error)
            }
        } catch (error: Exception) {
            if (mission.running) {
                mission.notifyError(error)
            }
        }
    }

    @Throws(IOException::class)
    private fun transferHlsResource(index: Int, targetFile: File) {
        val previous = mission.hlsCheckpoint?.resources?.firstOrNull { it.resourceIndex == index }
        // Seed only what restoredProgress actually counted: a truncated temp
        // file contributes 0, so seeding its stale count would double-
        // subtract via onRestart and drive done negative.
        var lastProgress = previous
            ?.takeIf {
                it.tempFilePath == targetFile.absolutePath && targetFile.exists() &&
                    targetFile.length() >= it.bytesWritten
            }
            ?.bytesWritten
            ?: 0L

        hlsTransfer.transfer(
            resourceIndex = index,
            manifestUrl = hlsManifestUrl(index),
            targetFile = targetFile,
            checkpoint = previous,
            listener = object : TransferProgressListener {
                override fun onProgress(progress: TransferProgress) {
                    val delta = progress.bytesWritten - lastProgress
                    if (delta > 0) {
                        if (progress.expectedBytes > 0) {
                            updateEstimatedLength(index, progress.expectedBytes, delta)
                        }
                        lastProgress = progress.bytesWritten
                        mission.notifyProgress(delta)
                    }
                }
            },
            onCheckpoint = { checkpoint -> updateResourceCheckpoint(checkpoint) },
            onRestart = {
                if (lastProgress > 0) {
                    mission.notifyProgress(-lastProgress)
                    lastProgress = 0
                }
            },
        )
    }

    @Throws(IOException::class)
    private fun transferDirectCompanionResource(index: Int, targetFile: File) {
        val previous = mission.hlsCheckpoint?.resources?.firstOrNull { it.resourceIndex == index }
        val previousFile = previous?.tempFilePath?.let { File(it) }
        if (previous != null && previousFile?.exists() == true && previous.bytesWritten > 0) {
            if (previousFile.absolutePath == targetFile.absolutePath &&
                previousFile.length() == previous.bytesWritten &&
                previous.nextSegmentIndex == 1 &&
                previous.manifestUrl == mission.urls[index]
            ) {
                return
            }
            // Subtract only what restoredProgress counted: a truncated file
            // contributed 0, so subtracting the stale count would drive done
            // negative.
            if (previousFile.length() >= previous.bytesWritten) {
                mission.notifyProgress(-previous.bytesWritten)
            }
        }

        val parent = targetFile.parentFile ?: throw IOException("HLS companion target has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create HLS companion parent: $parent")
        }
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Cannot reset HLS companion target: $targetFile")
        }

        // Each retry restarts from byte 0 into a fresh file: reusing the
        // same stream would append a full re-download behind a partial prefix
        // and double-count progress. Roll back notified progress first.
        var written = 0L
        var notified = 0L
        retryPolicy.withRetries {
            if (notified > 0) {
                mission.notifyProgress(-notified)
                notified = 0
            }
            written = 0
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Cannot reset HLS companion target: $targetFile")
            }
            targetFile.outputStream().use { output ->
                // Same retry parity as every other network path in the engine.
                connectionFactory.open(mission.urls[index], "GET", null, null).useTransferConnection { connection ->
                    val statusCode = connection.responseCode
                    if (statusCode < 200 || statusCode > 299) {
                        throw TransferHttpException(statusCode)
                    }
                    val contentLength = connection.contentLengthLong
                    if (contentLength > 0) {
                        updateEstimatedLength(index, contentLength, 0)
                    }
                    connection.inputStream.use { input ->
                        copyDirectCompanion(input, output) { delta ->
                            written += delta
                            notified += delta
                            mission.notifyProgress(delta)
                        }
                    }
                }
            }
        }
        updateResourceCheckpoint(
            HlsResourceCheckpoint(
                resourceIndex = index,
                manifestUrl = mission.urls[index],
                mediaPlaylistUrl = null,
                tempFilePath = targetFile.absolutePath,
                nextSegmentIndex = 1,
                segmentByteOffset = 0,
                bytesWritten = written,
                mediaSequence = -1,
                playlistFingerprint = "direct-companion",
            )
        )
    }

    private fun updateEstimatedLength(resourceIndex: Int, resourceExpectedBytes: Long, nextDelta: Long) {
        val otherResourcesBytes = mission.hlsCheckpoint?.resources
            ?.filter { it.resourceIndex != resourceIndex }
            ?.sumOf { it.bytesWritten }
            ?: 0L
        val estimatedLength = (otherResourcesBytes + resourceExpectedBytes)
            .coerceAtLeast(mission.done + nextDelta)
            .coerceAtLeast(mission.nearLength)
        if (estimatedLength > mission.length || mission.unknownLength) {
            mission.length = estimatedLength
            mission.unknownLength = false
        }
    }

    @Throws(IOException::class)
    private fun copyDirectCompanion(
        input: InputStream,
        output: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        // Same interrupt taxonomy as copyOutputToStorage: NIO interrupts
        // and a set interrupt flag mean pause, not download error.
        try {
            val buffer = ByteArray(transferConfig.bufferSize)
            while (true) {
                ensureRunning()
                val read = input.read(buffer)
                if (read == -1) {
                    return
                }
                output.write(buffer, 0, read)
                onProgress(read.toLong())
            }
        } catch (error: InterruptedIOException) {
            throw TransferInterruptedException()
        } catch (error: ClosedByInterruptException) {
            throw TransferInterruptedException()
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) {
                throw TransferInterruptedException()
            }
            throw error
        }
    }

    @Throws(IOException::class)
    private fun copyWithPsStateReset(copy: () -> Long): Long {
        // The copy phases run with psState == 1; any failure must reset and
        // persist it or pause() stays disabled, mirroring SabrJavaMuxer.
        try {
            return copy()
        } catch (error: TransferInterruptedException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
        } catch (error: Exception) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
        }
    }

    @Throws(IOException::class)
    private fun remuxWithFramework(inputs: List<File>, output: File) {
        mission.psState = 1
        mission.writeThisToFile()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "remuxWithFramework inputs=${inputs.size}")
        }
        try {
            HlsFrameworkMuxer.remux(inputs, output, mission.kind, outputExtension())
        } catch (error: TransferInterruptedException) {
            mission.psState = 0
            mission.writeThisToFile()
            throw error
        } catch (error: Exception) {
            mission.psState = 0
            // MediaMuxer throws unchecked (IllegalStateException, etc.); reset
            // psState for any failure so the mission never sticks at 1.
            // Keep the old message shape (detail appended) so error buckets
            // don't churn, without double-prefixing framework detail.
            val rawDetail = if (error is IOException) error.message.orEmpty() else "${error::class.simpleName}: ${error.message.orEmpty()}"
            val detail = rawDetail.ifBlank { error::class.simpleName.orEmpty() }
            val message = if (detail.startsWith("HLS framework remux failed")) detail else "HLS framework remux failed: $detail"
            throw IOException(message, error)
        }
    }

    @Throws(IOException::class)
    private fun copyOutputToStorage(output: File): Long {
        var copied = 0L
        try {
            output.inputStream().use { input ->
                mission.storage.getStream().use { storage ->
                    storage.setLength(0)
                    storage.seek(0)

                    val buffer = ByteArray(transferConfig.bufferSize)
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
        } catch (error: InterruptedIOException) {
            // SABR parity: an interrupt surfacing as I/O is a pause.
            throw TransferInterruptedException()
        } catch (error: ClosedByInterruptException) {
            throw TransferInterruptedException()
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) {
                throw TransferInterruptedException()
            }
            throw error
        }
        return copied
    }

    private fun remuxedOutputFromCheckpoint(workDir: File): File? {
        val checkpoint = mission.hlsCheckpoint ?: return null
        if (!checkpoint.remuxStarted) {
            return null
        }
        val output = checkpoint.remuxOutputPath?.let { File(it) }?.absoluteFile ?: return null
        if (!output.absolutePath.startsWith(workDir.absolutePath + File.separator) || !output.exists() || output.length() <= 0) {
            return null
        }
        // A storage rename changes the container; never copy a stale remux
        // whose suffix no longer matches the current target.
        if (output.extension.lowercase(Locale.ROOT) != outputExtension().lowercase(Locale.ROOT)) {
            return null
        }
        // A retarget between remux and copy must re-remux, not copy a stale
        // container from different playlists.
        if (checkpoint.remuxFingerprint != currentResourcesFingerprint()) {
            return null
        }
        return output
    }

    private fun currentResourcesFingerprint(): String {
        // Include the mission-level URLs: recovery can retarget them while
        // keeping the checkpoint, and the old remux must not be reused.
        // Strip cookie fragments so rotation alone does not force re-remux.
        val urls = (mission.urls?.toList().orEmpty() + (mission.resourceManifestUrls?.toList().orEmpty()))
            .joinToString(",") { it.substringBefore("#cookie=") }
        val resources = mission.hlsCheckpoint?.resources
            ?.sortedBy { it.resourceIndex }
            ?.joinToString("|") { "${it.mediaPlaylistUrl}@${it.playlistFingerprint}" }
            .orEmpty()
        return "$urls|$resources"
    }

    private fun markRemuxOutput(output: File) {
        val current = mission.hlsCheckpoint ?: HlsDownloadCheckpoint()
        mission.hlsCheckpoint = current.copy(
            remuxStarted = true,
            remuxOutputPath = output.absolutePath,
            remuxFingerprint = currentResourcesFingerprint(),
        )
        mission.writeThisToFile()
    }

    private fun completeMission(finalBytes: Long) {
        if (finalBytes <= 0) {
            throw IOException("HLS framework remux failed: empty output")
        }
        mission.done = finalBytes
        mission.length = finalBytes
        mission.current = mission.urls.size
        mission.psState = 2
        mission.hlsCheckpoint = null
        cleanup(mission)
        mission.unknownLength = false
        mission.notifyFinished()
    }

    private fun hlsManifestUrl(index: Int): String {
        val isUrl = mission.resourceIsUrls?.getOrNull(index) ?: true
        val manifestUrl = mission.resourceManifestUrls?.getOrNull(index)
        val url = mission.urls[index]
        if (!isUrl) {
            throw IOException("Inline HLS manifests are not supported by HlsDownloader yet")
        }
        return when {
            !manifestUrl.isNullOrBlank() && HlsDownloadStreamHelper.looksLikeHls(manifestUrl) -> manifestUrl
            else -> url
        }
    }

    private fun isHlsResource(index: Int): Boolean {
        return mission.resourceDeliveryMethods?.getOrNull(index) == "HLS" ||
            HlsDownloadStreamHelper.looksLikeHls(mission.resourceManifestUrls?.getOrNull(index)) ||
            HlsDownloadStreamHelper.looksLikeHls(mission.urls.getOrNull(index))
    }

    private fun updateResourceCheckpoint(resourceCheckpoint: HlsResourceCheckpoint) {
        val current = mission.hlsCheckpoint ?: HlsDownloadCheckpoint()
        val resources = current.resources
            .filterNot { it.resourceIndex == resourceCheckpoint.resourceIndex }
            .toMutableList()
        resources += resourceCheckpoint
        mission.hlsCheckpoint = current.copy(resources = resources.sortedBy { it.resourceIndex })
        mission.writeThisToFile()
    }

    private fun restoredProgress(): Long {
        // SabrDownloader parity: ignore truncated temp files whose length
        // trails the checkpoint, instead of zero-padding them on resume.
        return mission.hlsCheckpoint?.resources
            ?.filter { File(it.tempFilePath).let { file -> file.exists() && file.length() >= it.bytesWritten } }
            ?.sumOf { it.bytesWritten }
            ?: 0L
    }

    @Throws(TransferInterruptedException::class)
    private fun ensureRunning() {
        if (!mission.running || Thread.currentThread().isInterrupted) {
            throw TransferInterruptedException()
        }
    }

    private fun outputExtension(): String = downloadOutputExtension(mission.storage.name)

    private fun shouldRecover(statusCode: Int): Boolean {
        return statusCode == DownloadMission.ERROR_HTTP_AUTH ||
            statusCode == DownloadMission.ERROR_HTTP_FORBIDDEN ||
            statusCode == HTTP_NOT_FOUND ||
            statusCode == HTTP_GONE
    }

    companion object {
        private const val TAG = "HlsDownloader"
        private const val MAX_PARALLELISM = 6
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_GONE = 410

        @JvmStatic
        fun cleanup(mission: DownloadMission) {
            try {
                workDirectory(mission).deleteRecursively()
            } catch (ignored: Exception) {
                // Nothing to do.
            }
        }

        private fun workDirectory(mission: DownloadMission): File {
            val base = mission.context.getExternalFilesDir(null) ?: mission.context.filesDir
            val missionId = if (mission.timestamp > 0) {
                mission.timestamp.toString()
            } else {
                // SABR parity: salt with the url list so same-named
                // missions never share a work dir and clobber inputs.
                val nameHash = mission.storage.name?.hashCode() ?: 0
                val urlsHash = mission.urls?.contentHashCode() ?: 0
                "${nameHash}_${urlsHash}"
            }
            return File(base, "hls-downloader/$missionId")
        }
    }
}
