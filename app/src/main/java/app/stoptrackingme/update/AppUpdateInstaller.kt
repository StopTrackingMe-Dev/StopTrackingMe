package app.stoptrackingme.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.stoptrackingme.BuildConfig
import java.io.File

internal object AppUpdateInstaller {
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

    fun install(context: Context, file: File) {
        if (!file.isFile) throw AppUpdateException("已下载的更新文件不存在")

        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = packageInstaller.createSession(sessionParams)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(createStatusIntentSender(context, sessionId))
            }
        } catch (error: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw AppUpdateException("无法提交系统安装会话", error)
        }
    }

    private fun createStatusIntentSender(context: Context, sessionId: Int) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, AppUpdateInstallReceiver::class.java)
                .setAction(AppUpdateInstallReceiver.ACTION_INSTALL_STATUS),
            PendingIntent.FLAG_UPDATE_CURRENT or mutablePendingIntentFlag(),
        ).intentSender

    private fun mutablePendingIntentFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
}
