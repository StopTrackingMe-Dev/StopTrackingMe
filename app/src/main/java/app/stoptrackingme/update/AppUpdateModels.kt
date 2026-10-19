package app.stoptrackingme.update

import java.io.File
import java.util.Locale

internal enum class AppVariant(
    val wireName: String,
    val displayName: String,
    val edition: String,
    val hasAccessibility: Boolean,
) {
    MINIMAL("minimal", "Minimal 精简版 · 含无障碍", "minimal", true),
    FULL("full", "Full 完整版 · 含无障碍", "full", true),
    MINIMAL_WITHOUT_ACCESSIBILITY("minimal-without-accessibility", "Minimal 精简版 · 不含无障碍", "minimal", false),
    FULL_WITHOUT_ACCESSIBILITY("full-without-accessibility", "Full 完整版 · 不含无障碍", "full", false),
    ;

    val hasQrImages: Boolean get() = edition == "full"

    fun withAccessibility(enabled: Boolean): AppVariant =
        entries.first { it.edition == edition && it.hasAccessibility == enabled }

    fun withEdition(edition: String): AppVariant =
        entries.first { it.edition == edition && it.hasAccessibility == hasAccessibility }

    companion object {
        fun fromWireValue(value: String?): AppVariant? {
            val normalized = value?.trim()?.lowercase(Locale.ROOT)
                ?.replace(Regex("[-_]?withaccessibility"), "")
                ?.replace(Regex("[-_]?withoutaccessibility"), "-without-accessibility")
                ?.removeSuffix("-with-accessibility")
            return entries.firstOrNull { it.wireName == normalized }
        }

        fun fromApkFileName(fileName: String): AppVariant? {
            val edition = when {
                Regex("(^|[-_.])minimal($|[-_.])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(fileName) -> MINIMAL
                Regex("(^|[-_.])full($|[-_.])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(fileName) -> FULL
                else -> null
            } ?: return null
            return edition.withAccessibility(
                !Regex("without[-_]?accessibility", RegexOption.IGNORE_CASE).containsMatchIn(fileName),
            )
        }
    }
}

internal data class AppUpdateAsset(
    val fileName: String,
    val targetAbi: String?,
    val githubUrl: String,
    val mirrorUrl: String?,
    val sizeBytes: Long?,
    val sha256: String,
    val variant: AppVariant = AppVariant.FULL,
)

internal data class AppUpdateRelease(
    val tagName: String,
    val versionName: String,
    val versionCode: Long?,
    val releaseName: String,
    val releasePageUrl: String?,
    val publishedAt: String?,
    val prerelease: Boolean,
    val asset: AppUpdateAsset,
    val variant: AppVariant = AppVariant.FULL,
)

internal enum class AppUpdateDownloadSource(
    val displayName: String,
) {
    MIRROR("国内镜像"),
    GITHUB("GitHub"),
}

internal data class AppUpdateDownloadProgress(
    val source: AppUpdateDownloadSource,
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

internal data class DownloadedAppUpdate(
    val release: AppUpdateRelease,
    val source: AppUpdateDownloadSource,
    val file: File,
)

internal sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data class UpToDate(val release: AppUpdateRelease) : AppUpdateStatus
    data class Available(val release: AppUpdateRelease) : AppUpdateStatus
    data class Downloading(
        val release: AppUpdateRelease,
        val progress: AppUpdateDownloadProgress,
    ) : AppUpdateStatus
    data class Ready(val update: DownloadedAppUpdate) : AppUpdateStatus
    data class Failed(
        val message: String,
        val release: AppUpdateRelease? = null,
    ) : AppUpdateStatus
}

internal class AppUpdateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
