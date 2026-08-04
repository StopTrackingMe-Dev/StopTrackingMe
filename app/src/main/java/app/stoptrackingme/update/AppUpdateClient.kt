package app.stoptrackingme.update

import app.stoptrackingme.BuildConfig
import app.stoptrackingme.network.PublicNetworkGuard
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal class AppUpdateClient(
    manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL,
    private val defaultMirrorUrl: String = BuildConfig.UPDATE_MIRROR_URL,
    private val networkGuard: PublicNetworkGuard = PublicNetworkGuard(),
) {
    private val manifestUri = validatedManifestUri(manifestUrl)

    fun fetchRelease(): AppUpdateRelease {
        var current = manifestUri
        repeat(MAX_REDIRECTS + 1) { hop ->
            networkGuard.requirePublic(current, MANIFEST_CONNECT_TIMEOUT_MS)
            val connection = openGet(current, MANIFEST_CONNECT_TIMEOUT_MS, MANIFEST_READ_TIMEOUT_MS)
            try {
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (hop >= MAX_REDIRECTS) throw AppUpdateException("更新信息重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw AppUpdateException("更新信息重定向缺少目标")
                    val next = current.resolve(location)
                    if (!next.scheme.equals("https", ignoreCase = true) ||
                        !next.host.equals(manifestUri.host, ignoreCase = true) ||
                        next.userInfo != null || next.fragment != null
                    ) {
                        throw AppUpdateException("更新信息不允许跨域或降级重定向")
                    }
                    current = next
                    return@repeat
                }
                if (status !in 200..299) {
                    throw AppUpdateException("更新服务器返回 HTTP $status")
                }
                val bytes = readLimited(connection, MAX_MANIFEST_BYTES)
                val json = bytes.toString(Charsets.UTF_8)
                return AppUpdateManifestParser.parse(json)
            } catch (error: AppUpdateException) {
                throw error
            } catch (error: Exception) {
                throw AppUpdateException("无法读取更新信息", error)
            } finally {
                connection.disconnect()
            }
        }
        throw AppUpdateException("无法读取更新信息")
    }

    fun download(
        release: AppUpdateRelease,
        updateDirectory: File,
        preferredSource: AppUpdateDownloadSource,
        allowFallback: Boolean,
        onProgress: (AppUpdateDownloadProgress) -> Unit,
    ): DownloadedAppUpdate {
        val candidates = downloadCandidates(release, preferredSource, allowFallback)
        var lastError: AppUpdateException? = null
        for (candidate in candidates) {
            try {
                return downloadCandidate(release, candidate, updateDirectory, onProgress)
            } catch (error: AppUpdateException) {
                lastError = error
            }
        }
        val attempted = candidates.joinToString("、") { it.source.displayName }
        throw AppUpdateException(
            "$attempted 下载均失败：${lastError?.message ?: "未知错误"}",
            lastError,
        )
    }

    private fun downloadCandidate(
        release: AppUpdateRelease,
        candidate: DownloadCandidate,
        updateDirectory: File,
        onProgress: (AppUpdateDownloadProgress) -> Unit,
    ): DownloadedAppUpdate {
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw AppUpdateException("无法创建更新下载目录")
        }
        if (!updateDirectory.isDirectory) throw AppUpdateException("更新下载目录无效")

        val safeTag = release.tagName.replace(Regex("[^0-9A-Za-z._-]"), "_").take(80)
        val target = File(updateDirectory, "StopTrackingMe-$safeTag.apk")
        if (target.isFile && verifyFile(target, release.asset)) {
            onProgress(
                AppUpdateDownloadProgress(
                    source = candidate.source,
                    downloadedBytes = target.length(),
                    totalBytes = target.length(),
                ),
            )
            return DownloadedAppUpdate(release, candidate.source, target)
        }
        if (target.exists() && !target.delete()) {
            throw AppUpdateException("无法替换旧的更新文件")
        }

        val partial = File(updateDirectory, target.name + ".part")
        if (partial.exists() && !partial.delete()) {
            throw AppUpdateException("无法清理未完成的更新下载")
        }

        return try {
            downloadToFile(release, candidate, partial, onProgress)
            if (!partial.renameTo(target)) throw AppUpdateException("无法保存已校验的更新文件")
            DownloadedAppUpdate(release, candidate.source, target)
        } catch (error: AppUpdateException) {
            partial.delete()
            throw error
        } catch (error: Exception) {
            partial.delete()
            throw AppUpdateException("${candidate.source.displayName} 下载失败", error)
        }
    }

    private fun downloadToFile(
        release: AppUpdateRelease,
        candidate: DownloadCandidate,
        partial: File,
        onProgress: (AppUpdateDownloadProgress) -> Unit,
    ) {
        var current = candidate.uri
        repeat(MAX_REDIRECTS + 1) { hop ->
            AppUpdateDownloadPolicy.requireAllowed(current, candidate.source, initial = hop == 0)
            networkGuard.requirePublic(current, DOWNLOAD_CONNECT_TIMEOUT_MS)
            val connection = openGet(current, DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
            try {
                connection.setRequestProperty("Accept", APK_MIME_TYPE + ", application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (hop >= MAX_REDIRECTS) throw AppUpdateException("APK 下载重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw AppUpdateException("APK 下载重定向缺少目标")
                    current = current.resolve(location)
                    return@repeat
                }
                if (status !in 200..299) {
                    throw AppUpdateException("${candidate.source.displayName}返回 HTTP $status")
                }

                val declaredLength = connection.contentLengthLong.takeIf { it >= 0 }
                if (declaredLength != null && declaredLength > MAX_APK_BYTES) {
                    throw AppUpdateException("更新 APK 超过大小限制")
                }
                val expectedLength = release.asset.sizeBytes
                if (declaredLength != null && expectedLength != null &&
                    declaredLength != expectedLength
                ) {
                    throw AppUpdateException("${candidate.source.displayName}上的 APK 大小不一致")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastReported = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_APK_BYTES) {
                                throw AppUpdateException("更新 APK 超过大小限制")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            if (total - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = total
                                onProgress(
                                    AppUpdateDownloadProgress(
                                        source = candidate.source,
                                        downloadedBytes = total,
                                        totalBytes = expectedLength ?: declaredLength,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (expectedLength != null && total != expectedLength) {
                    throw AppUpdateException("${candidate.source.displayName}下载的 APK 大小不一致")
                }
                val actualSha256 = digest.digest().toHex()
                if (actualSha256 != release.asset.sha256) {
                    throw AppUpdateException("${candidate.source.displayName}下载的 APK 校验失败")
                }
                onProgress(
                    AppUpdateDownloadProgress(
                        source = candidate.source,
                        downloadedBytes = total,
                        totalBytes = expectedLength ?: declaredLength ?: total,
                    ),
                )
                return
            } finally {
                connection.disconnect()
            }
        }
        throw AppUpdateException("APK 下载失败")
    }

    private fun downloadCandidates(
        release: AppUpdateRelease,
        preferredSource: AppUpdateDownloadSource,
        allowFallback: Boolean,
    ): List<DownloadCandidate> {
        val mirrorUrl = release.asset.mirrorUrl ?: defaultMirrorUrl.takeIf(String::isNotBlank)
        val mirror = mirrorUrl?.let {
            DownloadCandidate(AppUpdateDownloadSource.MIRROR, validatedDownloadUri(it))
        }
        val github = DownloadCandidate(
            AppUpdateDownloadSource.GITHUB,
            validatedDownloadUri(release.asset.githubUrl),
        )
        return when (preferredSource) {
            AppUpdateDownloadSource.MIRROR -> buildList {
                mirror?.let(::add)
                if (allowFallback || mirror == null) add(github)
            }
            AppUpdateDownloadSource.GITHUB -> listOf(github)
        }
    }

    private fun openGet(uri: URI, connectTimeoutMs: Int, readTimeoutMs: Int): HttpsURLConnection {
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw AppUpdateException("更新连接不是 HTTPS")
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun readLimited(connection: HttpsURLConnection, maximum: Int): ByteArray {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maximum) throw AppUpdateException("更新信息超过大小限制")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maximum) throw AppUpdateException("更新信息超过大小限制")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun verifyFile(file: File, asset: AppUpdateAsset): Boolean {
        if (asset.sizeBytes != null && file.length() != asset.sizeBytes) return false
        return runCatching {
            file.inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().toHex() == asset.sha256
            }
        }.getOrDefault(false)
    }

    private data class DownloadCandidate(
        val source: AppUpdateDownloadSource,
        val uri: URI,
    )

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_MANIFEST_BYTES = 512 * 1024
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 128L * 1024
        private const val MANIFEST_CONNECT_TIMEOUT_MS = 8_000
        private const val MANIFEST_READ_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT = "StopTrackingUpdate/1"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val REDIRECT_STATUS_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )

        private fun validatedManifestUri(value: String): URI {
            val uri = validatedHttpsUri(value, "更新信息")
            if (!uri.host.equals("stoptracking.me", ignoreCase = true) ||
                uri.path != "/latest.json"
            ) {
                throw AppUpdateException("更新信息地址不受信任")
            }
            return uri
        }

        private fun validatedDownloadUri(value: String): URI =
            validatedHttpsUri(value, "APK 下载")

        private fun validatedHttpsUri(value: String, label: String): URI {
            val uri = try {
                URI(value.trim())
            } catch (error: Exception) {
                throw AppUpdateException("$label 地址无效", error)
            }
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
                value.length > 4_096
            ) {
                throw AppUpdateException("$label 地址必须是安全的 HTTPS 地址")
            }
            return uri.normalize()
        }
    }
}

internal object AppUpdateDownloadPolicy {
    private const val MIRROR_HOST = "1813680010.cdn.123clouddisk.com"
    private const val GITHUB_HOST = "github.com"
    private const val GITHUB_RELEASE_PATH =
        "/StopTrackingMe-Dev/StopTrackingMe/releases/download/"
    private val GITHUB_ASSET_HOSTS = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun requireAllowed(
        uri: URI,
        source: AppUpdateDownloadSource,
        initial: Boolean,
    ) {
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw AppUpdateException("APK 下载地址缺少域名")
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null || uri.fragment != null
        ) {
            throw AppUpdateException("APK 下载不允许明文、凭据或片段")
        }
        val allowed = when (source) {
            AppUpdateDownloadSource.MIRROR -> host == MIRROR_HOST
            AppUpdateDownloadSource.GITHUB -> when {
                initial -> host == GITHUB_HOST && uri.path.startsWith(GITHUB_RELEASE_PATH)
                else -> host == GITHUB_HOST || host in GITHUB_ASSET_HOSTS
            }
        }
        if (!allowed) throw AppUpdateException("APK 下载重定向到了不受信任的域名")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
}
