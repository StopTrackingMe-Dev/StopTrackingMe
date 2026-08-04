package app.stoptrackingme.update

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.Locale

internal object AppUpdateManifestParser {
    fun parse(json: String): AppUpdateRelease {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (error: Exception) {
            throw AppUpdateException("更新信息不是有效的 JSON", error)
        }

        val tagName = root.optionalString("tag_name")
            ?: root.optionalString("tagName")
            ?: throw AppUpdateException("更新信息缺少发布标签")
        val versionName = root.optionalString("versionName")
            ?: tagName.removePrefix("v").removePrefix("V")
        val versionCode = root.optionalLong("versionCode")
        val releaseName = root.optionalString("name") ?: tagName
        val releasePageUrl = root.optionalString("html_url")
            ?: root.optionalString("releaseUrl")
        val publishedAt = root.optionalString("published_at")
            ?: root.optionalString("publishedAt")
        val prerelease = root.optionalBoolean("prerelease") ?: false
        val mirrorUrl = root.optionalString("mirrorUrl")

        requireShortValue(tagName, "发布标签", 100)
        requireShortValue(versionName, "版本号", 100)
        requireShortValue(releaseName, "发布名称", 200)
        releasePageUrl?.let { requireHttpsUrl(it, "发布页面") }

        val asset = parseAsset(root, mirrorUrl)
        return AppUpdateRelease(
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            releaseName = releaseName,
            releasePageUrl = releasePageUrl,
            publishedAt = publishedAt,
            prerelease = prerelease,
            asset = asset,
        )
    }

    private fun parseAsset(root: JsonObject, mirrorUrl: String?): AppUpdateAsset {
        val githubAsset = root.get("assets")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.preferredApk()

        val fileName = githubAsset?.optionalString("name")
            ?: root.optionalString("fileName")
            ?: "app-release.apk"
        val githubUrl = githubAsset?.optionalString("browser_download_url")
            ?: root.optionalString("githubUrl")
            ?: throw AppUpdateException("更新信息缺少 GitHub APK 下载地址")
        val sizeBytes = githubAsset?.optionalLong("size")
            ?: root.optionalLong("sizeBytes")
        val digest = githubAsset?.optionalString("digest")
            ?: root.optionalString("sha256")
            ?: throw AppUpdateException("更新信息缺少 APK SHA-256")
        val sha256 = normalizeSha256(digest)

        if (!fileName.lowercase(Locale.ROOT).endsWith(".apk") ||
            fileName.contains('/') || fileName.contains('\\')
        ) {
            throw AppUpdateException("更新 APK 文件名无效")
        }
        if (sizeBytes != null && sizeBytes !in MIN_APK_BYTES..MAX_APK_BYTES) {
            throw AppUpdateException("更新 APK 大小无效")
        }
        requireHttpsUrl(githubUrl, "GitHub APK")
        mirrorUrl?.let { requireHttpsUrl(it, "国内镜像") }

        return AppUpdateAsset(
            fileName = fileName,
            githubUrl = githubUrl,
            mirrorUrl = mirrorUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )
    }

    private fun JsonArray.preferredApk(): JsonObject? {
        val candidates = mapNotNull { element ->
            element.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.filter { asset ->
            asset.optionalString("name")?.lowercase(Locale.ROOT)?.endsWith(".apk") == true
        }
        return candidates.firstOrNull {
            it.optionalString("name").equals("app-release.apk", ignoreCase = true)
        } ?: candidates.firstOrNull()
    }

    private fun normalizeSha256(value: String): String {
        val normalized = value.trim().removePrefix("sha256:").lowercase(Locale.ROOT)
        if (normalized.length != 64 || normalized.any { it !in "0123456789abcdef" }) {
            throw AppUpdateException("更新信息中的 APK SHA-256 无效")
        }
        return normalized
    }

    private fun requireHttpsUrl(value: String, label: String) {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw AppUpdateException("$label 地址无效", error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            throw AppUpdateException("$label 只接受不含凭据与片段的 HTTPS 地址")
        }
    }

    private fun requireShortValue(value: String, label: String, maximum: Int) {
        if (value.isBlank() || value.length > maximum) {
            throw AppUpdateException("更新信息中的$label 无效")
        }
    }

    private fun JsonObject.optionalString(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun JsonObject.optionalLong(name: String): Long? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.runCatching { asLong }
        ?.getOrNull()

    private fun JsonObject.optionalBoolean(name: String): Boolean? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean

    private const val MIN_APK_BYTES = 1_024L
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
}
