package us.shandian.giga.hls.state

import java.io.Serializable

data class HlsDownloadCheckpoint(
    // Retained for checkpoint compat: old files deserialize with this
    // field present, and dropping it would change the serial form.
    val version: Int = VERSION,
    val resources: List<HlsResourceCheckpoint> = emptyList(),
    val remuxStarted: Boolean = false,
    val remuxOutputPath: String? = null,
    // Playlist identity at remux time: a retarget between remux and copy
    // must re-remux instead of copying a stale container. Null (including
    // pre-upgrade checkpoints) never matches, failing safe toward remux.
    val remuxFingerprint: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        const val VERSION = 1
    }
}

data class HlsResourceCheckpoint(
    val resourceIndex: Int,
    val manifestUrl: String,
    val mediaPlaylistUrl: String?,
    val tempFilePath: String,
    val nextSegmentIndex: Int,
    // Always written as 0 and never read; retained for checkpoint compat
    // (dropping a serial field breaks old-file deserialization).
    val segmentByteOffset: Long,
    val bytesWritten: Long,
    val mediaSequence: Long,
    val playlistFingerprint: String?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
