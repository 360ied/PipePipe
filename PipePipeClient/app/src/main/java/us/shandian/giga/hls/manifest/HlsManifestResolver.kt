package us.shandian.giga.hls.manifest

import us.shandian.giga.hls.transfer.HttpTransferConfig
import us.shandian.giga.hls.transfer.HttpTransferConnectionFactory
import us.shandian.giga.hls.transfer.TransferHttpException
import us.shandian.giga.hls.transfer.TransferInterruptedException
import us.shandian.giga.hls.transfer.useTransferConnection
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class HlsManifestResolver(
    config: HttpTransferConfig = HttpTransferConfig(),
) {
    private val connectionFactory = HttpTransferConnectionFactory(config)
    private val parser = HlsPlaylistParser()

    @Throws(IOException::class)
    fun resolve(url: String): HlsPlaylist {
        connectionFactory.open(url, "GET", null, null).useTransferConnection { connection ->
            val statusCode = connection.responseCode
            if (statusCode < 200 || statusCode > 299) {
                throw TransferHttpException(statusCode)
            }

            val finalUrl = connection.url.toString()
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0

                while (true) {
                    // A blocked read is uninterruptible, but poll between
                    // chunks so pause does not wait out the full body on a
                    // slow playlist response.
                    if (Thread.currentThread().isInterrupted) {
                        throw TransferInterruptedException()
                    }
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }
                    total += read
                    if (total > MAX_MANIFEST_BYTES) {
                        throw HlsManifestTooLargeException("HLS manifest is too large")
                    }
                    output.write(buffer, 0, read)
                }

                // ByteArrayOutputStream.toString(Charset) is API 33+;
                // the String-name overload works back to minSdk 30.
                output.toString(Charsets.UTF_8.name())
            }

            return parser.parse(finalUrl, body)
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    }
}
