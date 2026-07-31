package app.stoptrackingme.network

import app.stoptrackingme.rules.RuleValidationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import kotlin.system.measureTimeMillis

class PublicNetworkGuardTest {
    @Test
    fun rejectsPrivateAndLinkLocalAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.2", "::1").forEach { host ->
            val guard = PublicNetworkGuard {
                arrayOf(InetAddress.getByName(host))
            }
            assertThrows(BlockedNetworkTargetException::class.java) {
                guard.requirePublic(URI("https://example.test"), 500)
            }
        }
    }

    @Test
    fun dnsLookupHonorsHardTimeout() {
        val guard = PublicNetworkGuard {
            Thread.sleep(5_000)
            arrayOf(InetAddress.getByName("93.184.216.34"))
        }

        val elapsed = measureTimeMillis {
            assertThrows(RuleValidationException::class.java) {
                guard.requirePublic(URI("https://slow.example"), 500)
            }
        }

        assertTrue("DNS 超时不应等待系统解析上限", elapsed < 2_000)
    }
}
