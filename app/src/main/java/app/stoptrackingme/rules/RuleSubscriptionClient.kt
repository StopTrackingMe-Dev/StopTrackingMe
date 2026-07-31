package app.stoptrackingme.rules

import app.stoptrackingme.network.PublicNetworkGuard
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class RuleSubscriptionClient(
    private val networkGuard: PublicNetworkGuard = PublicNetworkGuard(),
) {
    fun validateSubscriptionUrl(value: String): String {
        val uri = try {
            URI(value.trim())
        } catch (error: Exception) {
            throw RuleValidationException("订阅地址无效", error)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            uri.fragment != null
        ) {
            throw RuleValidationException("订阅只接受不含凭据与片段的 HTTPS 地址")
        }
        networkGuard.requirePublic(uri, TIMEOUT_MS)
        return uri.normalize().toASCIIString()
    }

    fun fetch(url: String): ByteArray {
        var current = URI(validateSubscriptionUrl(url))
        val originalHost = current.host.lowercase()
        repeat(MAX_REDIRECTS + 1) { hop ->
            networkGuard.requirePublic(current, TIMEOUT_MS)
            val connection = current.toURL().openConnection() as? HttpsURLConnection
                ?: throw RuleValidationException("订阅连接不是 HTTPS")
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val status = connection.responseCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (hop >= MAX_REDIRECTS) throw RuleValidationException("订阅重定向次数过多")
                    val location = connection.getHeaderField("Location")
                        ?: throw RuleValidationException("订阅重定向缺少目标")
                    val next = current.resolve(location)
                    if (!next.scheme.equals("https", ignoreCase = true) ||
                        !next.host.equals(originalHost, ignoreCase = true) ||
                        next.userInfo != null
                    ) {
                        throw RuleValidationException("订阅不允许跨域或降级重定向")
                    }
                    current = next
                    return@repeat
                }
                if (status !in 200..299) throw RuleValidationException("订阅服务器返回 HTTP $status")
                val declaredLength = connection.contentLengthLong
                if (declaredLength > RuleParser.MAX_BUNDLE_BYTES) {
                    throw RuleValidationException("订阅规则超过大小限制")
                }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > RuleParser.MAX_BUNDLE_BYTES) {
                            throw RuleValidationException("订阅规则超过大小限制")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw RuleValidationException("订阅下载失败")
    }

    companion object {
        private const val MAX_REDIRECTS = 2
        private const val TIMEOUT_MS = 8_000
        private const val USER_AGENT = "StopTrackingRuleClient/1"
        private val REDIRECT_STATUS_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
