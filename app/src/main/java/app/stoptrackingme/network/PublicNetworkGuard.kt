package app.stoptrackingme.network

import app.stoptrackingme.rules.RuleValidationException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class BlockedNetworkTargetException(message: String) : RuleValidationException(message)

class NetworkResolutionException(
    message: String,
    cause: Throwable,
) : RuleValidationException(message, cause)

class PublicNetworkGuard(
    private val lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    fun requirePublic(uri: URI, lookupTimeoutMs: Int = DEFAULT_LOOKUP_TIMEOUT_MS) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw RuleValidationException("网络目标协议不受支持")
        }
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT)
            ?: throw RuleValidationException("网络目标缺少域名")
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw BlockedNetworkTargetException("已阻止本机网络目标")
        }
        val lookupTask = DNS_EXECUTOR.submit<Array<InetAddress>> { lookup(host) }
        val addresses = try {
            lookupTask.get(
                lookupTimeoutMs.coerceIn(MIN_LOOKUP_TIMEOUT_MS, MAX_LOOKUP_TIMEOUT_MS).toLong(),
                TimeUnit.MILLISECONDS,
            )
        } catch (error: TimeoutException) {
            lookupTask.cancel(true)
            throw NetworkResolutionException("解析网络目标超时", error)
        } catch (error: Exception) {
            lookupTask.cancel(true)
            throw NetworkResolutionException("无法解析网络目标", error)
        }
        if (addresses.isEmpty() || addresses.any(::isBlocked)) {
            throw BlockedNetworkTargetException("已阻止私网、本机或链路本地目标")
        }
    }

    internal fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                first == 0 ||
                    first >= 224 ||
                    first == 100 && second in 64..127 ||
                    first == 169 && second == 254
            }
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xff
                first and 0xfe == 0xfc
            }
            else -> true
        }
    }

    companion object {
        private const val DEFAULT_LOOKUP_TIMEOUT_MS = 5_000
        private const val MIN_LOOKUP_TIMEOUT_MS = 500
        private const val MAX_LOOKUP_TIMEOUT_MS = 10_000
        private val DNS_EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "StopTracking-DNS").apply { isDaemon = true }
        }
    }
}
