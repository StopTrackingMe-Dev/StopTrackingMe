package app.stoptrackingme.link

import app.stoptrackingme.network.PublicNetworkGuard
import app.stoptrackingme.rules.AccessFailureRule
import app.stoptrackingme.rules.RedirectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RedirectResolverTest {
    private val publicGuard = PublicNetworkGuard {
        arrayOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
    }

    @Test
    fun followsManualHttpsRedirects() {
        val locations = mapOf(
            "https://short.example/a" to "https://jump.example/b",
            "https://jump.example/b" to "https://final.example/video",
        )
        val transport = RedirectTransport { uri, _ ->
            locations[uri.toString()]?.let { RedirectHttpResponse(302, it) }
                ?: RedirectHttpResponse(200, null)
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://short.example/a", policy(maxRedirects = 5))

        result as RedirectOutcome.Success
        assertEquals("https://final.example/video", result.finalUrl)
        assertEquals(2, result.redirectCount)
    }

    @Test
    fun stopsBeforeRequestingAnAllowedFinalPage() {
        val requestedUris = ArrayList<String>()
        val transport = RedirectTransport { uri, _ ->
            requestedUris += uri.toASCIIString()
            if (uri.host == "short.example") {
                RedirectHttpResponse(302, "https://final.example/video")
            } else {
                error("最终页面不应在短链展开阶段被请求")
            }
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve(
                "https://short.example/a",
                policy(maxRedirects = 5).copy(stopAtAllowedFinalHost = true),
            )

        result as RedirectOutcome.Success
        assertEquals("https://final.example/video", result.finalUrl)
        assertEquals(1, result.redirectCount)
        assertEquals(listOf("https://short.example/a"), requestedUris)
    }

    @Test
    fun recoversAllowedContentUrlFromAccessFailureRedirect() {
        val errorUrl = "https://www.xiaohongshu.com/website-login/error" +
            "?redirectPath=http%3A%2F%2Fwww.xiaohongshu.com%2Fdiscovery%2Fitem%2Fnote-id" +
            "%3Ftype%3Dvideo%26xsec_token%3Drequired%253D&error_code=300011"
        val transport = RedirectTransport { _, _ -> RedirectHttpResponse(302, errorUrl) }
        val xiaohongshuPolicy = policy(maxRedirects = 5).copy(
            shortLinkHosts = setOf("xhslink.cn"),
            allowedFinalHosts = setOf("xiaohongshu.com"),
            accessFailures = listOf(
                AccessFailureRule(
                    urlRegex = "^https://www\\.xiaohongshu\\.com/website-login/error([?#].*)?$",
                    recoveryQueryParameter = "redirectPath",
                ),
            ),
        )

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://xhslink.cn/o/example", xiaohongshuPolicy)

        result as RedirectOutcome.Success
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/note-id" +
                "?type=video&xsec_token=required%3D",
            result.finalUrl,
        )
        assertEquals(1, result.redirectCount)
    }

    @Test
    fun rejectsAccessFailureRecoveryOutsideAllowedHosts() {
        val errorUrl = "https://www.xiaohongshu.com/website-login/error" +
            "?redirectPath=https%3A%2F%2Fattacker.example%2Fpayload&error_code=300011"
        val transport = RedirectTransport { _, _ -> RedirectHttpResponse(302, errorUrl) }
        val xiaohongshuPolicy = policy(maxRedirects = 5).copy(
            shortLinkHosts = setOf("xhslink.cn"),
            allowedFinalHosts = setOf("xiaohongshu.com"),
            accessFailures = listOf(
                AccessFailureRule(
                    urlRegex = "^https://www\\.xiaohongshu\\.com/website-login/error([?#].*)?$",
                    recoveryQueryParameter = "redirectPath",
                ),
            ),
        )

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://xhslink.cn/o/example", xiaohongshuPolicy)

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).message.contains("无法恢复"))
    }

    @Test
    fun upgradesAllowedInitialHttpShortLinkToHttps() {
        val requestedUris = ArrayList<String>()
        val transport = RedirectTransport { uri, _ ->
            requestedUris += uri.toASCIIString()
            RedirectHttpResponse(200, null)
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("http://short.example/a?token=a%2Fb", policy(maxRedirects = 5))

        result as RedirectOutcome.Success
        assertEquals("https://short.example/a?token=a%2Fb", result.finalUrl)
        assertEquals(listOf("https://short.example/a?token=a%2Fb"), requestedUris)
    }

    @Test
    fun doesNotUpgradeHttpUrlOutsideShortLinkAllowlist() {
        var connected = false
        val transport = RedirectTransport { _, _ ->
            connected = true
            RedirectHttpResponse(200, null)
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("http://untrusted.example/a", policy(maxRedirects = 5))

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).message.contains("HTTPS"))
        assertFalse(connected)
    }

    @Test
    fun rejectsRedirectLoop() {
        val transport = RedirectTransport { uri, _ ->
            val location = if (uri.host == "one.example") {
                "https://two.example/x"
            } else {
                "https://one.example/x"
            }
            RedirectHttpResponse(302, location)
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://one.example/x", policy(maxRedirects = 5))

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).message.contains("循环"))
    }

    @Test
    fun rejectsRedirectOverLimit() {
        val transport = RedirectTransport { uri, _ ->
            RedirectHttpResponse(302, "https://next${uri.path.length}.example${uri.path}x")
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://start.example/x", policy(maxRedirects = 1))

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).message.contains("次数"))
    }

    @Test
    fun rejectsProtocolDowngrade() {
        val transport = RedirectTransport { _, _ ->
            RedirectHttpResponse(302, "http://public.example/video")
        }

        val result = HttpRedirectResolver(publicGuard, transport)
            .resolve("https://short.example/x", policy(maxRedirects = 5))

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).message.contains("协议"))
    }

    @Test
    fun blocksPrivateTargetBeforeOpeningConnection() {
        var connected = false
        val transport = RedirectTransport { _, _ ->
            connected = true
            RedirectHttpResponse(200, null)
        }

        val result = HttpRedirectResolver(transport = transport)
            .resolve("https://127.0.0.1/x", policy(maxRedirects = 5))

        assertTrue(result is RedirectOutcome.Failure)
        assertTrue((result as RedirectOutcome.Failure).blockedTarget)
        assertFalse(connected)
    }

    private fun policy(maxRedirects: Int) = RedirectPolicy(
        shortLinkHosts = setOf("short.example"),
        allowedFinalHosts = setOf("final.example"),
        maxRedirects = maxRedirects,
        requireHttps = true,
        connectTimeoutMs = 500,
        readTimeoutMs = 500,
    )
}
