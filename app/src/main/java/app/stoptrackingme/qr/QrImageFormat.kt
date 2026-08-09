package app.stoptrackingme.qr

data class QrImageFormat(
    val mimeType: String,
    val extension: String,
    val quality: Int,
)

object QrImageFormats {
    val PNG = QrImageFormat("image/png", "png", 100)
    val JPEG = QrImageFormat("image/jpeg", "jpg", 95)

    fun fromMimeType(mimeType: String?): QrImageFormat? = when (
        mimeType?.substringBefore(';')?.trim()?.lowercase()
    ) {
        "image/png" -> PNG
        "image/jpeg", "image/jpg" -> JPEG
        else -> null
    }
}

object QrCachePolicy {
    const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L

    fun isExpired(lastModifiedMillis: Long, nowMillis: Long): Boolean =
        lastModifiedMillis > 0L && nowMillis - lastModifiedMillis >= MAX_AGE_MILLIS
}
