package app.stoptrackingme.link

import app.stoptrackingme.network.BlockedNetworkTargetException
import app.stoptrackingme.network.PublicNetworkGuard
import app.stoptrackingme.rules.RedirectPolicy
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

sealed interface RedirectOutcome {
    data class Success(
        val finalUrl: String,
        val redirectCount: Int,
    ) : RedirectOutcome

    data class Failure(
        val message: String,
        val blockedTarget: Boolean,
    ) : RedirectOutcome
}

fun interface RedirectResolver {
    fun resolve(url: String, policy: RedirectPolicy): RedirectOutcome
}

data class RedirectHttpResponse(
    val statusCode: Int,
    val location: String?,
)

fun interface RedirectTransport {
    fun execute(uri: URI, policy: RedirectPolicy): RedirectHttpResponse
}

class UrlConnectionRedirectTransport : RedirectTransport {
    override fun execute(uri: URI, policy: RedirectPolicy): RedirectHttpResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = policy.connectTimeoutMs
            connection.readTimeout = policy.readTimeoutMs
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            return RedirectHttpResponse(
                statusCode = connection.responseCode,
                location = connection.getHeaderField("Location"),
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT = "StopTrackingLinkResolver/1"
    }
}

class HttpRedirectResolver(
    private val networkGuard: PublicNetworkGuard = PublicNetworkGuard(),
    private val transport: RedirectTransport = UrlConnectionRedirectTransport(),
) : RedirectResolver {
    override fun resolve(url: String, policy: RedirectPolicy): RedirectOutcome {
        var current = try {
            URI(url)
        } catch (_: Exception) {
            return RedirectOutcome.Failure("短链格式无效", false)
        }
        if (policy.requireHttps &&
            current.scheme.equals("http", ignoreCase = true) &&
            current.port == -1 &&
            HostPolicy.isAllowed(current.host, policy.shortLinkHosts)
        ) {
            current = try {
                URI("https" + current.toASCIIString().substring(current.scheme.length))
            } catch (_: Exception) {
                return RedirectOutcome.Failure("短链格式无效", false)
            }
        }
        val visited = linkedSetOf<String>()

        for (redirectCount in 0..policy.maxRedirects) {
            val scheme = current.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                return RedirectOutcome.Failure("重定向使用了不支持的协议", false)
            }
            if (policy.requireHttps && scheme != "https") {
                return RedirectOutcome.Failure("短链及其重定向必须使用 HTTPS", false)
            }
            if (current.userInfo != null || current.host.isNullOrBlank()) {
                return RedirectOutcome.Failure("重定向目标格式无效", false)
            }
            if (AccessFailureUrl.matches(current, policy)) {
                val recovered = AccessFailureUrl.recoverTarget(current, policy)
                    ?: return RedirectOutcome.Failure("短链跳转到访问限制页面，且无法恢复原始地址", false)
                return RedirectOutcome.Success(recovered.toASCIIString(), redirectCount)
            }
            if (policy.stopAtAllowedFinalHost &&
                HostPolicy.isAllowed(current.host, policy.allowedFinalHosts) &&
                !HostPolicy.isAllowed(current.host, policy.shortLinkHosts)
            ) {
                // The redirect location is all the cleaner needs. Avoid probing the destination
                // page here because preview loading will fetch it once with site-appropriate headers.
                return RedirectOutcome.Success(current.toASCIIString(), redirectCount)
            }
            val canonical = current.normalize().toASCIIString()
            if (!visited.add(canonical)) {
                return RedirectOutcome.Failure("短链出现重定向循环", false)
            }
            try {
                networkGuard.requirePublic(current, policy.connectTimeoutMs)
            } catch (_: BlockedNetworkTargetException) {
                return RedirectOutcome.Failure("已阻止私网、本机或链路本地重定向", true)
            } catch (_: Exception) {
                return RedirectOutcome.Failure("无法验证重定向网络目标", false)
            }

            try {
                val response = transport.execute(current, policy)
                val status = response.statusCode
                if (status in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= policy.maxRedirects) {
                        return RedirectOutcome.Failure("短链重定向次数超过限制", false)
                    }
                    val location = response.location
                        ?: return RedirectOutcome.Failure("短链重定向缺少目标", false)
                    val next = try {
                        current.resolve(location)
                    } catch (_: Exception) {
                        return RedirectOutcome.Failure("短链重定向目标无效", false)
                    }
                    val nextScheme = next.scheme?.lowercase(Locale.ROOT)
                    if (nextScheme != "http" && nextScheme != "https" ||
                        policy.requireHttps && nextScheme != "https"
                    ) {
                        return RedirectOutcome.Failure("重定向协议被阻止", false)
                    }
                    current = next
                    continue
                }
                if (status !in 200..299) {
                    return RedirectOutcome.Failure("短链服务器返回 HTTP $status", false)
                }
                return RedirectOutcome.Success(current.toASCIIString(), redirectCount)
            } catch (_: Exception) {
                return RedirectOutcome.Failure("短链展开失败，请检查网络后重试", false)
            }
        }
        return RedirectOutcome.Failure("短链展开失败", false)
    }

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
