package us.shandian.giga.hls.transfer

data class HttpTransferConfig(
    val maxRetries: Int = 3,
    val parallelism: Int = 1,
    val bufferSize: Int = 256 * 1024,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
)
