package app.stoptrackingme.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.stoptrackingme.BuildConfig
import java.io.File

internal object AppUpdateInstaller {
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun validateArchive(context: Context, update: DownloadedAppUpdate) {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                update.file.absolutePath,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(update.file.absolutePath, 0)
        } ?: throw AppUpdateException("下载文件不是有效的 Android 安装包")

        if (packageInfo.packageName != context.packageName) {
            throw AppUpdateException("下载 APK 的应用标识不匹配")
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        if (archiveVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            throw AppUpdateException("下载 APK 不是更高版本")
        }
        update.release.versionCode?.let { expected ->
            if (archiveVersionCode != expected) {
                throw AppUpdateException("下载 APK 的内部版本号与更新信息不一致")
            }
        }
        if (packageInfo.versionName != update.release.versionName) {
            throw AppUpdateException("下载 APK 的显示版本号与更新信息不一致")
        }
    }

    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun createPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun createInstallIntent(context: Context, file: File): Intent {
        if (!file.isFile) throw AppUpdateException("已下载的更新文件不存在")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("StopTrackingMe update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
