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
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(
                update.file.absolutePath,
                PackageManager.GET_META_DATA,
            )
        } ?: throw AppUpdateException("下载文件不是有效的 Android 安装包")

        val archiveVariant = packageInfo.applicationInfo?.metaData
            ?.getString(APP_VARIANT_META_DATA)
            ?.let(AppVariant::fromWireValue)
            ?: throw AppUpdateException("下载 APK 缺少版本类型标识")
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        validateArchiveMetadata(
            archivePackageName = packageInfo.packageName,
            archiveVariant = archiveVariant,
            archiveVersionCode = archiveVersionCode,
            archiveVersionName = packageInfo.versionName,
            expectedPackageName = context.packageName,
            currentVariant = currentVariant(),
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            currentVersionName = BuildConfig.VERSION_NAME,
            release = update.release,
        )
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

    private fun currentVariant(): AppVariant =
        AppVariant.fromWireValue(BuildConfig.APP_VARIANT)
            ?: throw AppUpdateException("未知的应用版本：${BuildConfig.APP_VARIANT}")

    private const val APP_VARIANT_META_DATA = "app.stoptrackingme.APP_VARIANT"
}

internal fun validateArchiveMetadata(
    archivePackageName: String,
    archiveVariant: AppVariant,
    archiveVersionCode: Long,
    archiveVersionName: String?,
    expectedPackageName: String,
    currentVariant: AppVariant,
    currentVersionCode: Long,
    currentVersionName: String,
    release: AppUpdateRelease,
) {
    if (archivePackageName != expectedPackageName) {
        throw AppUpdateException("下载 APK 的应用标识不匹配")
    }
    if (release.asset.variant != release.variant) {
        throw AppUpdateException("更新信息中的版本类型不一致")
    }
    if (archiveVariant != release.variant) {
        throw AppUpdateException("下载 APK 的版本类型与更新信息不一致")
    }

    val switchingVariant = archiveVariant != currentVariant
    if (archiveVersionCode < currentVersionCode ||
        (archiveVersionCode == currentVersionCode && !switchingVariant)
    ) {
        throw AppUpdateException(
            if (switchingVariant) "下载 APK 版本过旧，不能切换到该版本类型"
            else "下载 APK 不是更高版本",
        )
    }
    if (archiveVersionCode == currentVersionCode &&
        archiveVersionName != currentVersionName
    ) {
        throw AppUpdateException("同版本切换要求显示版本号一致")
    }
    release.versionCode?.let { expected ->
        if (archiveVersionCode != expected) {
            throw AppUpdateException("下载 APK 的内部版本号与更新信息不一致")
        }
    }
    if (archiveVersionName != release.versionName) {
        throw AppUpdateException("下载 APK 的显示版本号与更新信息不一致")
    }
}
