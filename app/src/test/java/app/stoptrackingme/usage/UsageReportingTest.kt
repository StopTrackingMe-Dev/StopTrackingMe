package app.stoptrackingme.usage

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UsageReportingTest {
    @Test
    fun `missing or invalid consent remains unset`() {
        assertEquals(UsageReportingConsent.UNSET, UsageReportingConsent.fromStored(null))
        assertEquals(UsageReportingConsent.UNSET, UsageReportingConsent.fromStored("UNKNOWN"))
        assertEquals(
            UsageReportingConsent.GRANTED,
            UsageReportingConsent.fromStored(UsageReportingConsent.GRANTED.name),
        )
        assertEquals(
            UsageReportingConsent.DENIED,
            UsageReportingConsent.fromStored(UsageReportingConsent.DENIED.name),
        )
    }

    @Test
    fun `installation code is random-looking and not device-derived`() {
        val first = InstallationCode.generate()
        val second = InstallationCode.generate()

        assertEquals(43, first.length)
        assertTrue(first.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(first, second)
    }

    @Test
    fun `installation code encoding has a stable privacy-safe shape`() {
        val encoded = InstallationCode.encode(ByteArray(32) { it.toByte() })

        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", encoded)
        assertEquals(43, encoded.length)
    }

    @Test
    fun `share report contains only the documented fields`() {
        val report = PendingUsageReport(
            installationCode = "A".repeat(43),
            eventId = UUID.randomUUID().toString(),
            shareCount = 7,
        )
        val json = JsonParser.parseString(UsageReporter.reportJson(report)).asJsonObject

        assertEquals(
            setOf("protocolVersion", "installationCode", "eventId", "shareCount"),
            json.keySet(),
        )
        assertFalse(json.has("url"))
        assertFalse(json.has("sourcePackage"))
        assertFalse(json.has("destination"))
        assertFalse(json.has("clipboard"))
    }

    @Test
    fun `deletion report contains no behavioral fields`() {
        val json = JsonParser.parseString(
            UsageReporter.deletionJson("A".repeat(43)),
        ).asJsonObject

        assertEquals(setOf("protocolVersion", "installationCode"), json.keySet())
        assertFalse(json.has("shareCount"))
    }
}
