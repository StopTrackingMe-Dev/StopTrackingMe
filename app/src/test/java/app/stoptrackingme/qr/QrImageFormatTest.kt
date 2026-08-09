package app.stoptrackingme.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImageFormatTest {
    @Test
    fun preservesPngAndJpegOutputContracts() {
        assertEquals("png", QrImageFormats.fromMimeType("image/png")?.extension)
        assertEquals(100, QrImageFormats.fromMimeType("image/png")?.quality)
        assertEquals("jpg", QrImageFormats.fromMimeType("image/jpeg")?.extension)
        assertEquals(95, QrImageFormats.fromMimeType("image/jpg")?.quality)
        assertNull(QrImageFormats.fromMimeType("image/webp"))
    }

    @Test
    fun cacheExpiresAtTwentyFourHoursButNotBefore() {
        val now = 100L * QrCachePolicy.MAX_AGE_MILLIS
        assertFalse(
            QrCachePolicy.isExpired(
                now - QrCachePolicy.MAX_AGE_MILLIS + 1,
                now,
            ),
        )
        assertTrue(
            QrCachePolicy.isExpired(
                now - QrCachePolicy.MAX_AGE_MILLIS,
                now,
            ),
        )
    }
}
