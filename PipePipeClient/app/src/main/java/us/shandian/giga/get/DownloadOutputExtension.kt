package us.shandian.giga.get

/**
 * Shared `output.<ext>` derivation: storage filename suffix when alphanumeric,
 * `mp4` default otherwise (unknown/future suffixes keep today's default).
 */
internal fun downloadOutputExtension(storageName: String?): String {
    val extension = storageName?.substringAfterLast('.', missingDelimiterValue = "").orEmpty()
    return extension.takeIf { it.isNotBlank() && it.all { char -> char.isLetterOrDigit() } } ?: "mp4"
}
