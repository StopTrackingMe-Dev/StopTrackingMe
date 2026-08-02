package app.stoptrackingme.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import app.stoptrackingme.link.HostPolicy
import app.stoptrackingme.network.PublicNetworkGuard
import app.stoptrackingme.rules.PreviewFieldSelector
import app.stoptrackingme.rules.PreviewSelectorType
import app.stoptrackingme.rules.RedirectPolicy
import app.stoptrackingme.rules.SharePreviewRule
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

data class WebSharePreview(
    val title: String,
    val description: String,
    val thumbnail: ByteArray?,
)

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
)

internal data class PreviewResource(
    val finalUri: URI,
    val contentType: String?,
    val bytes: ByteArray,
)

internal fun interface PreviewResourceClient {
    fun fetch(request: PreviewFetchRequest): PreviewResource
}

internal class SafePreviewResourceClient(
    private val networkGuard: PublicNetworkGuard = PublicNetworkGuard(),
) : PreviewResourceClient {
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
                connection.setRequestProperty("Accept", request.accept)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Cookie", "")
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) error("预览资源重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: error("预览资源重定向缺少目标")
                    current = current.resolve(location)
                    return@repeat
                }
                if (status !in 200..299) error("预览资源返回 HTTP $status")
                val contentLength = connection.contentLengthLong
                if (contentLength > request.maxBytes) error("预览资源超过大小限制")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(minOf(request.maxBytes, 32 * 1024))
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > request.maxBytes) error("预览资源超过大小限制")
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
        private const val USER_AGENT = "StopTrackingPreview/1"
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal object PageMetadataParser {
    fun parse(bytes: ByteArray, baseUri: URI, rule: SharePreviewRule): PageMetadata {
        val document = Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri.toASCIIString())
        return PageMetadata(
            title = firstValue(document, rule.titleSelectors),
            description = firstValue(document, rule.descriptionSelectors),
            imageUrl = firstValue(document, rule.imageSelectors),
        )
    }

    private fun firstValue(document: Document, selectors: List<PreviewFieldSelector>): String? {
        selectors.forEach { selector ->
            val value = when (selector.type) {
                PreviewSelectorType.HTML_TITLE -> document.title()
                PreviewSelectorType.META_PROPERTY -> document.getElementsByTag("meta")
                    .firstOrNull { it.attr("property").equals(selector.key, ignoreCase = true) }
                    ?.attr("content")
                PreviewSelectorType.META_NAME -> document.getElementsByTag("meta")
                    .firstOrNull { it.attr("name").equals(selector.key, ignoreCase = true) }
                    ?.attr("content")
            }
            normalize(value)?.let { return it }
        }
        return null
    }

    private fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.trim().replace(WHITESPACE, " ").take(MAX_METADATA_LENGTH).ifBlank { null }
    }

    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_METADATA_LENGTH = 2_048
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
    ): WebSharePreview {
        val pageUri = URI(cleanedUrl)
        val page = client.fetch(
            PreviewFetchRequest(
                uri = pageUri,
                allowedHosts = networkPolicy.allowedFinalHosts,
                requireHttps = networkPolicy.requireHttps,
                connectTimeoutMs = networkPolicy.connectTimeoutMs,
                readTimeoutMs = networkPolicy.readTimeoutMs,
                maxBytes = MAX_HTML_BYTES,
                accept = "text/html,application/xhtml+xml;q=0.9",
            ),
        )
        if (page.contentType != null && page.contentType !in HTML_CONTENT_TYPES) {
            error("目标页面不是 HTML")
        }
        val metadata = PageMetadataParser.parse(page.bytes, page.finalUri, rule)
        val title = metadata.title?.take(MAX_TITLE_CHARS) ?: "网页内容"
        val description = metadata.description?.take(MAX_DESCRIPTION_CHARS)
            ?: "来自 ${page.finalUri.host} 的净化链接"
        val thumbnail = loadThumbnail(metadata.imageUrl, page.finalUri, rule, networkPolicy)
        return WebSharePreview(
            title = "【$sourceName】$title",
            description = description,
            thumbnail = thumbnail,
        )
    }

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
        val allowedHosts = networkPolicy.allowedFinalHosts + rule.imageAllowedHosts
        if (!HostPolicy.isAllowed(imageUri.host, allowedHosts)) return null
        return try {
            val image = client.fetch(
                PreviewFetchRequest(
                    uri = imageUri,
                    allowedHosts = allowedHosts,
                    requireHttps = networkPolicy.requireHttps,
                    connectTimeoutMs = networkPolicy.connectTimeoutMs,
                    readTimeoutMs = networkPolicy.readTimeoutMs,
                    maxBytes = MAX_IMAGE_BYTES,
                    accept = "image/avif,image/webp,image/png,image/jpeg;q=0.9",
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

    companion object {
        private const val MAX_HTML_BYTES = 512 * 1024
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_TITLE_CHARS = 180
        private const val MAX_DESCRIPTION_CHARS = 300
        private val HTML_CONTENT_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}
