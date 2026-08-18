package app.stoptrackingme.update

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.Locale

internal object AppUpdateManifestParser {
    fun parse(
        json: String,
        requestedVariant: AppVariant = AppVariant.FULL,
        supportedAbis: List<String> = emptyList(),
    ): AppUpdateRelease {
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
        if (versionCode != null && versionCode <= 0) {
            throw AppUpdateException("更新信息中的内部版本号无效")
        }
        val releaseName = root.optionalString("name") ?: tagName
        val releasePageUrl = root.optionalString("html_url")
            ?: root.optionalString("releaseUrl")
        val publishedAt = root.optionalString("published_at")
            ?: root.optionalString("publishedAt")
        val prerelease = root.optionalBoolean("prerelease") ?: false
        val mirrorUrl = root.optionalString("mirrorUrl")
        val rootVariant = root.declaredVariant("更新信息")

        requireShortValue(tagName, "发布标签", 100)
        requireShortValue(versionName, "版本号", 100)
        requireShortValue(releaseName, "发布名称", 200)
        releasePageUrl?.let { requireHttpsUrl(it, "发布页面") }

        val asset = parseAsset(
            root = root,
            rootMirrorUrl = mirrorUrl,
            rootVariant = rootVariant,
            requestedVariant = requestedVariant,
            supportedAbis = supportedAbis,
        )
        return AppUpdateRelease(
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            releaseName = releaseName,
            releasePageUrl = releasePageUrl,
            publishedAt = publishedAt,
            prerelease = prerelease,
            asset = asset,
            variant = asset.variant,
        )
    }

    private fun parseAsset(
        root: JsonObject,
        rootMirrorUrl: String?,
        rootVariant: AppVariant?,
        requestedVariant: AppVariant,
        supportedAbis: List<String>,
    ): AppUpdateAsset {
        val selectedAsset = root.get("assets")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.preferredApk(requestedVariant, rootVariant, supportedAbis)
        val source = selectedAsset?.value ?: root

        val fileName = source.apkFileName()
            ?: "app-release.apk"
        val variant = selectedAsset?.variant
            ?: source.declaredVariant("更新 APK")
            ?: rootVariant
            ?: AppVariant.fromApkFileName(fileName)
            ?: AppVariant.FULL
        if (variant != requestedVariant) {
            throw AppUpdateException("更新信息没有 ${requestedVariant.displayName} APK")
        }
        val githubUrl = source.optionalString("browser_download_url")
            ?: source.optionalString("githubUrl")
            ?: throw AppUpdateException("更新信息缺少 GitHub APK 下载地址")
        val sizeBytes = source.optionalLong("size")
            ?: source.optionalLong("sizeBytes")
        val digest = source.optionalString("digest")
            ?: source.optionalString("sha256")
            ?: throw AppUpdateException("更新信息缺少 APK SHA-256")
        val sha256 = normalizeSha256(digest)
        val targetAbi = if (selectedAsset != null) {
            selectedAsset.targetAbi
        } else {
            source.targetAbi(fileName)
        }
        val mirrorUrl = source.optionalString("mirrorUrl") ?: rootMirrorUrl

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
            targetAbi = targetAbi,
            githubUrl = githubUrl,
            mirrorUrl = mirrorUrl,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            variant = variant,
        )
    }

    private fun JsonArray.preferredApk(
        requestedVariant: AppVariant,
        rootVariant: AppVariant?,
        supportedAbis: List<String>,
    ): SelectedApk? {
        val candidates = mapNotNull { element ->
            val asset = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: return@mapNotNull null
            val fileName = asset.apkFileName()
                ?.takeIf { it.lowercase(Locale.ROOT).endsWith(".apk") }
                ?: return@mapNotNull null
            val variant = asset.declaredVariant("更新 APK")
                ?: AppVariant.fromApkFileName(fileName)
                ?: rootVariant
                ?: AppVariant.FULL
            if (variant != requestedVariant) return@mapNotNull null
            val declaredAbi = asset.declaredAbi()
            SelectedApk(
                value = asset,
                targetAbi = when {
                    declaredAbi == UNIVERSAL_ABI -> null
                    declaredAbi in APK_ABIS -> declaredAbi
                    else -> abiFromFileName(fileName)
                },
                isUniversal = declaredAbi == UNIVERSAL_ABI ||
                    (declaredAbi == null && isUniversalFileName(fileName)),
                variant = variant,
            )
        }

        supportedAbis.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { supportedAbi ->
                candidates.firstOrNull { it.targetAbi == supportedAbi }?.let { return it }
            }

        candidates.firstOrNull(SelectedApk::isUniversal)?.let { return it }
        if (candidates.size == 1 && candidates.single().targetAbi == null) {
            return candidates.single()
        }
        if (candidates.isEmpty()) return null

        val deviceAbis = supportedAbis.joinToString().ifBlank { "未知" }
        throw AppUpdateException("更新信息没有适用于当前设备 ABI（$deviceAbis）的 APK")
    }

    private fun JsonObject.apkFileName(): String? =
        optionalString("name") ?: optionalString("fileName")

    private fun JsonObject.targetAbi(fileName: String): String? {
        val declaredAbi = declaredAbi()
        return when {
            declaredAbi == UNIVERSAL_ABI -> null
            declaredAbi in APK_ABIS -> declaredAbi
            else -> abiFromFileName(fileName)
        }
    }

    private fun JsonObject.declaredVariant(label: String): AppVariant? {
        if (!has("variant")) return null
        val value = optionalString("variant")
            ?: throw AppUpdateException("$label 的版本类型无效")
        return AppVariant.fromWireValue(value)
            ?: throw AppUpdateException("$label 的版本类型无效")
    }

    private fun JsonObject.declaredAbi(): String? {
        if (!has("abi")) return null
        val value = optionalString("abi")
            ?.lowercase(Locale.ROOT)
            ?: throw AppUpdateException("更新 APK 的 ABI 无效")
        if (value != UNIVERSAL_ABI && value !in APK_ABIS) {
            throw AppUpdateException("更新 APK 的 ABI 无效")
        }
        return value
    }

    private fun abiFromFileName(fileName: String): String? = APK_ABIS
        .sortedByDescending(String::length)
        .firstOrNull { abi ->
            Regex(
                "(^|[-_.])${Regex.escape(abi)}($|[-_.])",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(fileName)
        }

    private fun isUniversalFileName(fileName: String): Boolean {
        if (fileName.equals("app-release.apk", ignoreCase = true)) return true
        return UNIVERSAL_FILE_PATTERN.containsMatchIn(fileName)
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

    private data class SelectedApk(
        val value: JsonObject,
        val targetAbi: String?,
        val isUniversal: Boolean,
        val variant: AppVariant,
    )

    private const val MIN_APK_BYTES = 1_024L
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val UNIVERSAL_ABI = "universal"
    private val APK_ABIS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    private val UNIVERSAL_FILE_PATTERN = Regex(
        "(^|[-_.])universal($|[-_.])",
        RegexOption.IGNORE_CASE,
    )
}
