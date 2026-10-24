package us.shandian.giga.hls.transfer

import java.io.IOException

data class TransferProgress(
    val bytesWritten: Long,
    val expectedBytes: Long,
    val resourceUrl: String,
)

data class TransferResult(
    val bytesWritten: Long,
    val expectedBytes: Long,
    val statusCode: Int,
    val finalUrl: String,
)

interface TransferProgressListener {
    fun onProgress(progress: TransferProgress)
}

interface TransferController {
    fun isRunning(): Boolean
}

class TransferInterruptedException : IOException("Transfer interrupted")

class TransferHttpException(
    val statusCode: Int,
) : IOException("HTTP $statusCode")

// Deterministic validation failure (size caps, Range-ignored servers,
// decrypt/key/IV errors): re-issuing the identical request cannot heal
// it, so TransferRetryPolicy never retries this type.
class NonRetryableTransferException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
