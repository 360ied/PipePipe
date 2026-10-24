package us.shandian.giga.hls.transfer

import us.shandian.giga.hls.manifest.HlsManifestTooLargeException
import us.shandian.giga.hls.manifest.UnsupportedHlsPlaylistException
import java.io.IOException
import kotlin.math.min

internal class TransferRetryPolicy(
    private val maxRetries: Int,
) {
    @Throws(IOException::class)
    fun <T> withRetries(operation: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return operation()
            } catch (error: IOException) {
                if (!canRetry(error) || attempt >= maxRetries) {
                    throw error
                }
                attempt++
                sleepBeforeRetry(attempt)
            }
        }
    }

    private fun canRetry(error: IOException): Boolean {
        return when (error) {
            is TransferInterruptedException -> false
            // Deterministic playlist and validation errors never heal.
            is UnsupportedHlsPlaylistException -> false
            is HlsManifestTooLargeException -> false
            is NonRetryableTransferException -> false
            is TransferHttpException -> error.statusCode == 408 || error.statusCode == 429 ||
                error.statusCode >= 500
            else -> true
        }
    }

    @Throws(TransferInterruptedException::class)
    private fun sleepBeforeRetry(attempt: Int) {
        // Jitter so parallel workers failing together do not retry in
        // lockstep and hammer the server as one.
        val backoff = min(attempt * 500L, 2_000L) + (Math.random() * 500).toLong()
        try {
            Thread.sleep(backoff)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransferInterruptedException()
        }
    }
}
