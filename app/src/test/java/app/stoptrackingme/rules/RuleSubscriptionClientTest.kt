package app.stoptrackingme.rules

import app.stoptrackingme.network.PublicNetworkGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class RuleSubscriptionClientTest {
    private val publicClient = RuleSubscriptionClient(
        PublicNetworkGuard {
            arrayOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
        },
    )

    @Test
    fun acceptsOnlyCleanHttpsSubscriptionUrl() {
        assertEquals(
            "https://rules.example/path",
            publicClient.validateSubscriptionUrl("https://rules.example/path"),
        )
        assertThrows(RuleValidationException::class.java) {
            publicClient.validateSubscriptionUrl("http://rules.example/path")
        }
        assertThrows(RuleValidationException::class.java) {
            publicClient.validateSubscriptionUrl("https://user:pass@rules.example/path")
        }
        assertThrows(RuleValidationException::class.java) {
            publicClient.validateSubscriptionUrl("https://rules.example/path#fragment")
        }
    }

    @Test
    fun blocksLocalSubscriptionTarget() {
        val client = RuleSubscriptionClient()

        assertThrows(RuleValidationException::class.java) {
            client.validateSubscriptionUrl("https://127.0.0.1/rules.json")
        }
    }
}

