package us.shandian.giga.get

import java.io.IOException

internal class SabrDownloadException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason {
        FORMAT,
        // Unused today (init/network failures surface as FORMAT/PROTOCOL/
        // STALLED), but MissionAdapter maps them to user-visible strings —
        // keep the variants rather than orphaning those cases.
        INITIALIZATION,
        PROTECTED,
        STALLED,
        NETWORK,
        MUXING,
        STORAGE,
        PROTOCOL,
    }
}
