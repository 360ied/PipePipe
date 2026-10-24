package us.shandian.giga.get

/**
 * Shared `output.<ext>` derivation: storage filename suffix when alphanumeric
 * (passed through verbatim), `mp4` default otherwise. Each muxer validates
 * the suffix against the containers it can write and fails closed on mismatch.
 */
internal fun downloadOutputExtension(storageName: String?): String {
    val extension = storageName?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
    return extension.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() } } ?: "mp4"
}
