package us.shandian.giga.hls.manifest

import java.io.IOException

class UnsupportedHlsPlaylistException(message: String) : IOException(message)

/** Deterministic fetch failure (e.g. over the size cap): never worth retrying. */
class HlsManifestTooLargeException(message: String) : IOException(message)
