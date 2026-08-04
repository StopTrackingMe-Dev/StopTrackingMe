package app.stoptrackingme.update

import java.io.File

internal data class AppUpdateAsset(
    val fileName: String,
    val githubUrl: String,
    val mirrorUrl: String?,
    val sizeBytes: Long?,
    val sha256: String,
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
