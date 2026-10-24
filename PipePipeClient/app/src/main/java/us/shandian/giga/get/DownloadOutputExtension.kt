package us.shandian.giga.get

/**
 * Shared `output.<ext>` derivation: storage filename suffix when ASCII
 * alphanumeric (passed through verbatim), `mp4` default otherwise. Each
 * muxer validates the suffix against the containers it can write and fails
 * closed on mismatch. Non-ASCII alphanumerics fall back to `mp4` since no
 * container family matches them. A leading-dot name (e.g. `.mp4`) yields
 * its tail as the suffix; dotfiles are not a real storage case and fail
 * closed downstream like any other mismatch.
 */
internal fun downloadOutputExtension(storageName: String?): String {
    val extension = storageName?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
    val asciiAlnum = extension.isNotBlank() && extension.all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
    }
    return extension.takeIf { asciiAlnum } ?: "mp4"
}
