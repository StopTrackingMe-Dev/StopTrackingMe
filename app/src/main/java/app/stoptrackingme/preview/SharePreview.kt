package app.stoptrackingme.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.stoptrackingme.link.AccessFailureUrl
import app.stoptrackingme.link.HostPolicy
import app.stoptrackingme.network.PublicNetworkGuard
import app.stoptrackingme.rules.PreviewFieldSelector
import app.stoptrackingme.rules.PreviewHttpMethod
import app.stoptrackingme.rules.PreviewRequestRule
import app.stoptrackingme.rules.PreviewResponseType
import app.stoptrackingme.rules.PreviewSelectorType
import app.stoptrackingme.rules.PreviewSignatureAlgorithm
import app.stoptrackingme.rules.RedirectPolicy
import app.stoptrackingme.rules.SharePreviewRule
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

data class WebSharePreview(
    val title: String,
    val description: String,
    val thumbnail: ByteArray?,
)

fun copiedTextPreview(
    sourceName: String,
    sourceText: String?,
    urlRegex: String,
    defaultHost: String,
): WebSharePreview {
    val urlPattern = Regex(urlRegex)
    val copiedText = sourceText
        ?.replace(urlPattern, " ")
        ?.normalizeCopiedText()
    val leadingText = sourceText
        ?.let { text -> urlPattern.find(text)?.let { text.substring(0, it.range.first) } }
        ?.normalizeCopiedText()
    val titleText = (leadingText ?: copiedText)?.take(180) ?: "网页内容"
    val description = copiedText?.take(300) ?: "来自 $defaultHost 的净化链接"
    return WebSharePreview("【$sourceName】$titleText", description, null)
}

private fun String.normalizeCopiedText(): String? =
    replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '—', '|', '：', ':')
        .takeIf { it.isNotBlank() }

internal data class PageMetadata(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
)

internal data class PreviewFetchRequest(
    val uri: URI,
    val allowedHosts: Set<String>,
    val requireHttps: Boolean,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val maxBytes: Int,
    val accept: String,
    val method: PreviewHttpMethod = PreviewHttpMethod.GET,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal data class PreviewResource(
    val finalUri: URI,
    val contentType: String?,
    val bytes: ByteArray,
)

internal fun interface PreviewResourceClient {
    fun fetch(request: PreviewFetchRequest): PreviewResource
    fun resetSession() = Unit
}

internal class PreviewResourceTooLargeException(
    val maxBytes: Int,
) : IOException("预览资源超过大小限制：$maxBytes 字节")

internal class PreviewHttpException(
    val statusCode: Int,
) : IOException("预览资源返回 HTTP $statusCode")

internal class PreviewAccessBlockedException(
    val finalUri: URI,
) : IOException("公开网页跳转到访问限制页面")

internal class PreviewMetadataUnavailableException :
    IOException("公开网页没有可用的预览元数据")

internal class SafePreviewResourceClient(
    private val networkGuard: PublicNetworkGuard = PublicNetworkGuard(),
) : PreviewResourceClient {
    private var cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    override fun resetSession() {
        cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    }

    override fun fetch(request: PreviewFetchRequest): PreviewResource {
        var current = request.uri
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateTarget(current, request)
            networkGuard.requirePublic(current, request.connectTimeoutMs)
            val connection = current.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = request.connectTimeoutMs
                connection.readTimeout = request.readTimeoutMs
                connection.requestMethod = request.method.name
                connection.setRequestProperty("Accept", request.accept)
                request.headers.forEach(connection::setRequestProperty)
                cookies.get(current, emptyMap()).forEach { (name, values) ->
                    if (values.isNotEmpty()) connection.setRequestProperty(name, values.joinToString("; "))
                }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                cookies.put(current, connection.headerFields)
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) error("预览资源重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: error("预览资源重定向缺少目标")
                    current = current.resolve(location)
                    return@repeat
                }
                if (status !in 200..299) throw PreviewHttpException(status)
                val contentLength = connection.contentLengthLong
                if (contentLength > request.maxBytes) {
                    throw PreviewResourceTooLargeException(request.maxBytes)
                }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(minOf(request.maxBytes, 32 * 1024))
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > request.maxBytes) {
                            throw PreviewResourceTooLargeException(request.maxBytes)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return PreviewResource(
                    finalUri = current,
                    contentType = connection.contentType?.substringBefore(';')
                        ?.trim()?.lowercase(Locale.ROOT),
                    bytes = bytes,
                )
            } finally {
                connection.disconnect()
            }
        }
        error("无法获取预览资源")
    }

    private fun validateTarget(uri: URI, request: PreviewFetchRequest) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https" || request.requireHttps && scheme != "https") {
            error("预览资源协议不受支持")
        }
        if (uri.userInfo != null || !HostPolicy.isAllowed(uri.host, request.allowedHosts)) {
            error("预览资源域名不在规则白名单内")
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal object PageMetadataParser {
    fun parse(
        bytes: ByteArray,
        baseUri: URI,
        rule: SharePreviewRule,
        responseType: PreviewResponseType = PreviewResponseType.HTML,
    ): PageMetadata {
        if (responseType == PreviewResponseType.JSON) return parseJson(bytes, rule)
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri.toASCIIString())
        val scriptJsonRoots = parseScriptJsonRoots(
            document,
            rule.titleSelectors + rule.descriptionSelectors + rule.imageSelectors,
        )
        return PageMetadata(
            title = firstValue(document, rule.titleSelectors, scriptJsonRoots),
            description = firstValue(document, rule.descriptionSelectors, scriptJsonRoots),
            imageUrl = firstImageValue(
                document,
                baseUri,
                rule.imageSelectors,
                scriptJsonRoots,
                rule.imageAllowedHosts,
            ),
        )
    }

    private fun parseJson(bytes: ByteArray, rule: SharePreviewRule): PageMetadata {
        val root = JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
        return PageMetadata(
            title = firstJsonValue(root, rule.titleSelectors),
            description = firstJsonValue(root, rule.descriptionSelectors),
            imageUrl = firstJsonValue(root, rule.imageSelectors),
        )
    }

    private fun firstJsonValue(root: JsonElement, selectors: List<PreviewFieldSelector>): String? {
        selectors.filter { it.type == PreviewSelectorType.JSON_PATH }.forEach { selector ->
            jsonStringAtPath(root, selector.key.orEmpty().split('.'))?.let { return it }
        }
        return null
    }

    private fun firstValue(
        document: Document,
        selectors: List<PreviewFieldSelector>,
        scriptJsonRoots: Map<String, JsonElement>,
    ): String? {
        selectors.forEach { selector ->
            selectorValues(document, selector, scriptJsonRoots).forEach { value ->
                normalize(value)?.let { return it }
            }
        }
        return null
    }

    private fun firstImageValue(
        document: Document,
        baseUri: URI,
        selectors: List<PreviewFieldSelector>,
        scriptJsonRoots: Map<String, JsonElement>,
        preferredHosts: Set<String>,
    ): String? {
        var fallback: String? = null
        for (selector in selectors) {
            for (value in selectorValues(document, selector, scriptJsonRoots)) {
                val normalized = normalize(value) ?: continue
                if (fallback == null) fallback = normalized
                val candidate = try {
                    baseUri.resolve(normalized)
                } catch (_: Exception) {
                    null
                }
                if (candidate != null && HostPolicy.isAllowed(candidate.host, preferredHosts)) {
                    return normalized
                }
            }
        }
        return fallback
    }

    private fun selectorValues(
        document: Document,
        selector: PreviewFieldSelector,
        scriptJsonRoots: Map<String, JsonElement>,
    ): List<String?> = when (selector.type) {
        PreviewSelectorType.HTML_TITLE -> listOf(document.title())
        PreviewSelectorType.META_PROPERTY -> document.getElementsByTag("meta")
            .filter { it.attr("property").equals(selector.key, ignoreCase = true) }
            .map { it.attr("content") }
        PreviewSelectorType.META_NAME -> document.getElementsByTag("meta")
            .filter { it.attr("name").equals(selector.key, ignoreCase = true) }
            .map { it.attr("content") }
        PreviewSelectorType.JSON_PATH -> emptyList()
        PreviewSelectorType.SCRIPT_JSON_PATH -> {
            val segments = selector.key.orEmpty().split('.')
            listOf(
                scriptJsonRoots[segments.first()]
                    ?.let { jsonStringAtPath(it, segments.drop(1)) },
            )
        }
    }

    private fun parseScriptJsonRoots(
        document: Document,
        selectors: List<PreviewFieldSelector>,
    ): Map<String, JsonElement> {
        val rootNames = selectors.asSequence()
            .filter { it.type == PreviewSelectorType.SCRIPT_JSON_PATH }
            .mapNotNull { it.key?.substringBefore('.') }
            .toSet()
        if (rootNames.isEmpty()) return emptyMap()

        val result = linkedMapOf<String, JsonElement>()
        document.getElementsByTag("script").forEach { script ->
            val source = script.data().trim()
            val scriptId = script.attr("id")
            if (scriptId in rootNames && scriptId !in result) {
                parseJsonOrNull(source)?.let { result[scriptId] = it }
            }
            rootNames.filterNot(result::containsKey).forEach { rootName ->
                val prefix = "window.$rootName="
                if (source.startsWith(prefix)) {
                    parseJsonOrNull(
                        source.removePrefix(prefix).removeSuffix(";").trim(),
                    )?.let { result[rootName] = it }
                }
            }
        }
        return result
    }

    private fun parseJsonOrNull(source: String): JsonElement? = try {
        JsonParser.parseString(source)
    } catch (_: Exception) {
        // Ignore malformed page state and continue to the next declared fallback.
        null
    }

    private fun jsonStringAtPath(root: JsonElement, segments: List<String>): String? {
        return jsonStringAtPath(
            current = root,
            segments = segments,
            index = 0,
            remainingVisits = intArrayOf(MAX_JSON_PATH_VISITS),
        )
    }

    private fun jsonStringAtPath(
        current: JsonElement,
        segments: List<String>,
        index: Int,
        remainingVisits: IntArray,
    ): String? {
        if (remainingVisits[0]-- <= 0) return null
        if (index == segments.size) {
            return if (current.isJsonPrimitive && current.asJsonPrimitive.isString) {
                normalize(current.asString)
            } else {
                null
            }
        }

        val segment = segments[index]
        if (segment == "*") {
            val candidates: Iterable<JsonElement> = when {
                current.isJsonObject -> current.asJsonObject.entrySet().map { it.value }
                current.isJsonArray -> current.asJsonArray
                else -> emptyList()
            }
            candidates.forEach { candidate ->
                jsonStringAtPath(candidate, segments, index + 1, remainingVisits)?.let { return it }
            }
            return null
        }

        val next = when {
            current.isJsonObject -> current.asJsonObject.get(segment)
            current.isJsonArray -> segment.toIntOrNull()?.let { arrayIndex ->
                current.asJsonArray.takeIf { arrayIndex in 0 until it.size() }?.get(arrayIndex)
            }
            else -> null
        } ?: return null
        return jsonStringAtPath(next, segments, index + 1, remainingVisits)
    }

    private fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.trim().replace(WHITESPACE, " ").take(MAX_METADATA_LENGTH).ifBlank { null }
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_METADATA_LENGTH = 2_048
    private const val MAX_JSON_PATH_VISITS = 512
}

internal fun interface ThumbnailProcessor {
    fun createThumbnail(bytes: ByteArray): ByteArray?
}

internal object AndroidThumbnailProcessor : ThumbnailProcessor {
    override fun createThumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            for (size in OUTPUT_SIZES) {
                val square = centerCrop(source, size)
                try {
                    for (quality in 88 downTo 40 step 8) {
                        val output = ByteArrayOutputStream()
                        if (square.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            val encoded = output.toByteArray()
                            if (encoded.size <= MAX_THUMBNAIL_BYTES) return encoded
                        }
                    }
                } finally {
                    square.recycle()
                }
            }
        } finally {
            source.recycle()
        }
        return null
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DECODE_SIDE * 2 || height / sample > MAX_DECODE_SIDE * 2) {
            sample *= 2
        }
        return sample
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val sourceSize = minOf(source.width, source.height)
        val left = (source.width - sourceSize) / 2
        val top = (source.height - sourceSize) / 2
        canvas.drawBitmap(
            source,
            Rect(left, top, left + sourceSize, top + sourceSize),
            Rect(0, 0, size, size),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    private const val MAX_SOURCE_PIXELS = 12_000_000L
    private const val MAX_DECODE_SIDE = 1_024
    private const val MAX_THUMBNAIL_BYTES = 32 * 1024
    private val OUTPUT_SIZES = intArrayOf(256, 224, 192, 160, 128)
}

class SharePreviewLoader internal constructor(
    private val client: PreviewResourceClient = SafePreviewResourceClient(),
    private val thumbnailProcessor: ThumbnailProcessor = AndroidThumbnailProcessor,
) {
    fun load(
        cleanedUrl: String,
        sourceName: String,
        rule: SharePreviewRule,
        networkPolicy: RedirectPolicy,
        fallbackPreview: WebSharePreview? = null,
    ): WebSharePreview {
        client.resetSession()
        val primaryRequest = rule.request?.takeIf {
            Regex(it.urlRegex).containsMatchIn(cleanedUrl)
        }
        val requestAttempts = buildList<PreviewRequestRule?> {
            add(primaryRequest)
            addAll(
                rule.fallbackRequests.filter {
                    Regex(it.urlRegex).containsMatchIn(cleanedUrl)
                },
            )
        }
        var bootstrapComplete = false
        var lastFailure: Exception? = null
        requestAttempts.forEachIndexed { index, configuredRequest ->
            try {
                if (configuredRequest != null && !bootstrapComplete) {
                    runBootstrap(rule, networkPolicy)
                    bootstrapComplete = true
                }
                return loadAttempt(
                    cleanedUrl = cleanedUrl,
                    sourceName = sourceName,
                    rule = rule,
                    networkPolicy = networkPolicy,
                    fallbackPreview = fallbackPreview,
                    configuredRequest = configuredRequest,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (index == requestAttempts.lastIndex) throw error
            }
        }
        throw lastFailure ?: error("没有可用的预览请求")
    }

    private fun loadAttempt(
        cleanedUrl: String,
        sourceName: String,
        rule: SharePreviewRule,
        networkPolicy: RedirectPolicy,
        fallbackPreview: WebSharePreview?,
        configuredRequest: PreviewRequestRule?,
    ): WebSharePreview {
        val pageUri = URI(
            configuredRequest?.let { transform(cleanedUrl, it.urlRegex, it.urlReplacement) }
                ?: cleanedUrl,
        )
        val formParameters = configuredRequest?.formParameters.orEmpty().mapValues { (_, value) ->
            transform(cleanedUrl, configuredRequest!!.urlRegex, value)
        }.toMutableMap()
        configuredRequest?.signature?.let { signature ->
            val material = formParameters.entries.joinToString("") { "${it.key}=${it.value}" } + signature.suffix
            formParameters[signature.parameterName] = when (signature.algorithm) {
                PreviewSignatureAlgorithm.MD5_CONCAT -> md5(material)
            }
        }
        val body = formParameters.takeIf { it.isNotEmpty() }?.entries?.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }?.toByteArray(StandardCharsets.UTF_8)
        val page = client.fetch(
            PreviewFetchRequest(
                uri = pageUri,
                allowedHosts = networkPolicy.allowedFinalHosts,
                requireHttps = networkPolicy.requireHttps,
                connectTimeoutMs = networkPolicy.connectTimeoutMs,
                readTimeoutMs = networkPolicy.readTimeoutMs,
                maxBytes = MAX_HTML_BYTES,
                accept = "text/html,application/xhtml+xml;q=0.9",
                method = configuredRequest?.method ?: PreviewHttpMethod.GET,
                body = body,
                headers = configuredRequest?.headers ?: rule.pageRequestHeaders,
            ),
        )
        if (AccessFailureUrl.matches(page.finalUri, networkPolicy)) {
            throw PreviewAccessBlockedException(page.finalUri)
        }
        val responseType = configuredRequest?.responseType ?: PreviewResponseType.HTML
        if (responseType == PreviewResponseType.HTML &&
            page.contentType != null && page.contentType !in HTML_CONTENT_TYPES
        ) {
            error("目标页面不是 HTML")
        }
        val metadata = PageMetadataParser.parse(page.bytes, page.finalUri, rule, responseType)
        val metadataTitle = metadata.title?.takeUnless { isGenericTitle(it, sourceName) }
        val hasAllowedImage = metadata.imageUrl?.let { imageUrl ->
            isAllowedImageUrl(imageUrl, page.finalUri, rule, networkPolicy)
        } == true
        if (metadataTitle == null && metadata.description == null && !hasAllowedImage) {
            throw PreviewMetadataUnavailableException()
        }
        val title = metadataTitle?.take(MAX_TITLE_CHARS)
        val description = metadata.description?.take(MAX_DESCRIPTION_CHARS)
            ?: fallbackPreview?.description?.take(MAX_DESCRIPTION_CHARS)
            ?: "来自 ${page.finalUri.host} 的净化链接"
        val thumbnail = loadThumbnail(metadata.imageUrl, page.finalUri, rule, networkPolicy)
        if (metadataTitle == null && metadata.description == null && thumbnail == null) {
            throw PreviewMetadataUnavailableException()
        }
        return WebSharePreview(
            title = title?.let { "【$sourceName】$it" }
                ?: fallbackPreview?.title
                ?: "【$sourceName】网页内容",
            description = description,
            thumbnail = thumbnail,
        )
    }

    private fun runBootstrap(rule: SharePreviewRule, networkPolicy: RedirectPolicy) {
        val bootstrap = rule.bootstrap ?: return
        val tokenBody = bootstrap.tokenFormParameters.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val tokenResponse = client.fetch(
            PreviewFetchRequest(
                uri = URI(bootstrap.tokenUrl),
                allowedHosts = networkPolicy.allowedFinalHosts,
                requireHttps = networkPolicy.requireHttps,
                connectTimeoutMs = networkPolicy.connectTimeoutMs,
                readTimeoutMs = networkPolicy.readTimeoutMs,
                maxBytes = 128 * 1024,
                accept = "*/*",
                method = PreviewHttpMethod.POST,
                body = tokenBody,
                headers = bootstrap.tokenHeaders,
            ),
        )
        val token = Regex(bootstrap.tokenRegex)
            .find(String(tokenResponse.bytes, StandardCharsets.UTF_8))
            ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: error("Preview session token could not be parsed")
        client.fetch(
            PreviewFetchRequest(
                uri = URI(bootstrap.sessionUrlTemplate.replace("{token}", encode(token))),
                allowedHosts = networkPolicy.allowedFinalHosts,
                requireHttps = networkPolicy.requireHttps,
                connectTimeoutMs = networkPolicy.connectTimeoutMs,
                readTimeoutMs = networkPolicy.readTimeoutMs,
                maxBytes = 128 * 1024,
                accept = "*/*",
                headers = bootstrap.sessionHeaders,
            ),
        )
    }

    private fun transform(input: String, regex: String, replacement: String): String =
        Regex(regex).replaceFirst(input, replacement)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun loadThumbnail(
        imageUrl: String?,
        pageUri: URI,
        rule: SharePreviewRule,
        networkPolicy: RedirectPolicy,
    ): ByteArray? {
        if (imageUrl == null) return null
        val imageUri = try {
            pageUri.resolve(imageUrl)
        } catch (_: Exception) {
            return null
        }
        val secureImageUri = if (networkPolicy.requireHttps && imageUri.scheme.equals("http", true)) {
            URI("https", imageUri.userInfo, imageUri.host, imageUri.port, imageUri.path, imageUri.query, imageUri.fragment)
        } else {
            imageUri
        }
        val allowedHosts = networkPolicy.allowedFinalHosts + rule.imageAllowedHosts
        if (!HostPolicy.isAllowed(secureImageUri.host, allowedHosts)) return null
        return try {
            val image = client.fetch(
                PreviewFetchRequest(
                    uri = secureImageUri,
                    allowedHosts = allowedHosts,
                    requireHttps = networkPolicy.requireHttps,
                    connectTimeoutMs = networkPolicy.connectTimeoutMs,
                    readTimeoutMs = networkPolicy.readTimeoutMs,
                    maxBytes = MAX_IMAGE_BYTES,
                    accept = "image/avif,image/webp,image/png,image/jpeg;q=0.9",
                    headers = rule.imageRequestHeaders.ifEmpty {
                        rule.pageRequestHeaders.filterKeys { name ->
                            !name.equals("Accept", ignoreCase = true) &&
                                !name.equals("Content-Type", ignoreCase = true)
                        }
                    },
                ),
            )
            if (image.contentType?.startsWith("image/") != true ||
                image.contentType == "image/svg+xml"
            ) {
                null
            } else {
                thumbnailProcessor.createThumbnail(image.bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isAllowedImageUrl(
        imageUrl: String,
        pageUri: URI,
        rule: SharePreviewRule,
        networkPolicy: RedirectPolicy,
    ): Boolean {
        val imageUri = try {
            pageUri.resolve(imageUrl)
        } catch (_: Exception) {
            return false
        }
        val scheme = imageUri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        return HostPolicy.isAllowed(
            imageUri.host,
            networkPolicy.allowedFinalHosts + rule.imageAllowedHosts,
        )
    }

    private fun isGenericTitle(title: String, sourceName: String): Boolean =
        title.trim(' ', '-', '—', '|', '｜', '：', ':')
            .equals(sourceName.trim(), ignoreCase = true)

    companion object {
        // Modern media pages often embed hydration data in the initial HTML. Keep a bounded
        // limit for memory safety, but allow pages such as Bilibili's ~1 MiB responses.
        internal const val MAX_HTML_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_TITLE_CHARS = 180
        private const val MAX_DESCRIPTION_CHARS = 300
        private val HTML_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}
