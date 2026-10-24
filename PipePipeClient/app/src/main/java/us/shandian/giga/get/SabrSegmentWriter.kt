package us.shandian.giga.get

import org.schabi.newpipe.extractor.services.youtube.sabr.exception.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.media.SabrMediaSegment
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormatTimeline
import java.io.IOException
import java.io.OutputStream

internal class SabrSegmentWriter(
    private val targets: List<SabrDownloadTarget>,
    private val outputs: Map<Int, OutputStream>,
    private val onBytesWritten: (SabrDownloadTarget, Long) -> Unit,
) {
    @Throws(IOException::class, SabrProtocolException::class)
    fun acceptSegment(segment: SabrMediaSegment) {
        val target = targets.firstOrNull {
            it.format.itag == segment.header.itag &&
                it.format.xtags == segment.header.xtags
        }
        if (target == null) {
            segment.delete()
            return
        }
        try {
            if (segment.header.isInitSegment) {
                writeInitializationSegment(
                    target,
                    outputs.getValue(target.resourceIndex),
                    segment.data,
                )
            } else {
                writeMediaSegment(target, outputs.getValue(target.resourceIndex), segment)
            }
        } finally {
            segment.delete()
        }
    }

    @Throws(SabrProtocolException::class)
    fun observeWrittenInitializations() {
        for (target in targets) {
            val data = target.initializationData ?: continue
            if (target.timeline == null) {
                target.timeline = YoutubeSabrFormatTimeline.parse(target.format, data)
            }
        }
    }

    @Throws(IOException::class, SabrProtocolException::class)
    private fun writeInitializationSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        data: ByteArray,
    ) {
        // Unit: the sole caller ignores any result; idempotence is
        // already guarded by initializationWritten.
        if (target.initializationWritten) {
            return
        }
        // Validate before publishing: a corrupt init must not leave bad
        // bytes in the temp file with accounting already recorded.
        val timeline = YoutubeSabrFormatTimeline.parse(target.format, data)
        writeToStorage(output, data)
        target.initializationWritten = true
        target.initializationData = data
        target.timeline = timeline
        onBytesWritten(target, data.size.toLong())
        flushPendingMedia(target, output)
    }

    @Throws(IOException::class)
    private fun writeMediaSegment(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        val sequence = segment.header.sequenceNumber
        if (sequence < target.nextWriteSequence) {
            return
        }
        if (!target.initializationWritten) {
            cachePendingMedia(target, sequence, segment.data, "waiting for initialization")
            return
        }
        if (sequence > target.nextWriteSequence) {
            cachePendingMedia(target, sequence, segment.data, "waiting for sequence ${target.nextWriteSequence}")
            return
        }
        writeMediaSegmentBytes(target, output, segment)
        flushPendingMedia(target, output)
    }

    private fun flushPendingMedia(target: SabrDownloadTarget, output: OutputStream) {
        while (true) {
            // Peek first: removing before a throwing write would drop the
            // sequence while nextWriteSequence stays put, idling the
            // download into a misleading STALLED instead of failing here.
            val pending = target.pending[target.nextWriteSequence] ?: return
            writeMediaBytes(target, output, pending)
            target.pending.remove(target.nextWriteSequence)
            target.pendingBytes = (target.pendingBytes - pending.size).coerceAtLeast(0)
        }
    }

    private fun cachePendingMedia(
        target: SabrDownloadTarget,
        sequence: Int,
        data: ByteArray,
        reason: String,
    ) {
        val previous = target.pending.put(sequence, data)
        if (previous != null) {
            target.pendingBytes -= previous.size.toLong()
        }
        target.pendingBytes += data.size.toLong()
        if (target.pending.size > MAX_PENDING_SEGMENTS
            || target.pendingBytes > MAX_PENDING_BYTES
        ) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STALLED,
                "SABR download stalled while writing itag ${target.format.itag}: $reason"
                    + " (${target.pending.size} pending segments, ${target.pendingBytes} bytes)",
            )
        }
    }

    private fun writeMediaBytes(target: SabrDownloadTarget, output: OutputStream, data: ByteArray) {
        writeToStorage(output, data)
        target.nextWriteSequence++
        onBytesWritten(target, data.size.toLong())
    }

    private fun writeMediaSegmentBytes(
        target: SabrDownloadTarget,
        output: OutputStream,
        segment: SabrMediaSegment,
    ) {
        // Account actual copied bytes, not the declared length: short
        // reads (spool truncation, early EOF) must not inflate done.
        val copied = try {
            segment.openStream().use { input ->
                input.copyTo(output)
            }
        } catch (error: IOException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not write temporary media" +
                    " for itag ${target.format.itag} (${error.message.orEmpty()})",
                error,
            )
        }
        target.nextWriteSequence++
        onBytesWritten(target, copied)
    }

    private fun writeToStorage(output: OutputStream, data: ByteArray) {
        try {
            output.write(data)
        } catch (error: IOException) {
            throw SabrDownloadException(
                SabrDownloadException.Reason.STORAGE,
                "SABR download failed: could not write temporary media",
                error,
            )
        }
    }

    private companion object {
        private const val MAX_PENDING_SEGMENTS = 64
        private const val MAX_PENDING_BYTES = 24L * 1024L * 1024L
    }
}
