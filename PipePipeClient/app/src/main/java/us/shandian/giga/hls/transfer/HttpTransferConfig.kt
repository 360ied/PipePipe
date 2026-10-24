package us.shandian.giga.hls.transfer

internal data class HttpTransferConfig(
    val maxRetries: Int = 3,
    val parallelism: Int = 1,
    val bufferSize: Int = 256 * 1024,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
) {
    init {
        // Fail fast at construction: bad values escape the IOException
        // contract as runtime crashes (ByteArray, setConnectTimeout).
        // Callers pass coerced user prefs; these guard programmer error.
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(bufferSize > 0) { "bufferSize must be > 0" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be > 0" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be > 0" }
    }
}
