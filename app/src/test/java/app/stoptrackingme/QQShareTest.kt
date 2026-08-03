package app.stoptrackingme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QQShareTest {
    @Test
    fun `payload enforces SDK text limits`() {
        val payload = QQSharePayload.create(
            url = "https://example.com/clean",
            title = "标".repeat(QQSharePayload.MAX_TITLE_LENGTH + 10),
            description = "摘".repeat(QQSharePayload.MAX_DESCRIPTION_LENGTH + 10),
            thumbnail = byteArrayOf(1, 2, 3),
        )

        assertEquals(QQSharePayload.MAX_TITLE_LENGTH, payload.title.length)
        assertEquals(QQSharePayload.MAX_DESCRIPTION_LENGTH, payload.description.length)
        assertEquals(3, payload.thumbnail?.size)
    }

    @Test
    fun `payload rejects non-web target`() {
        assertThrows(IllegalArgumentException::class.java) {
            QQSharePayload.create(
                url = "file:///data/local",
                title = "标题",
                description = "摘要",
                thumbnail = null,
            )
        }
    }

    @Test
    fun `payload drops a thumbnail that is unsafe for an intent extra`() {
        val payload = QQSharePayload.create(
            url = "https://example.com/clean",
            title = "标题",
            description = "摘要",
            thumbnail = ByteArray(QQSharePayload.MAX_THUMBNAIL_BYTES + 1),
        )

        assertNull(payload.thumbnail)
    }
}
