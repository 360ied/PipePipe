package us.shandian.giga.hls.manifest

import java.io.IOException

/** Deterministic parse failure (e.g. unknown playlist type): never worth retrying. */
internal class UnsupportedHlsPlaylistException(message: String) : IOException(message)

/** Deterministic fetch failure (e.g. over the size cap): never worth retrying. */
internal class HlsManifestTooLargeException(message: String) : IOException(message)
