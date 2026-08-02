package app.stoptrackingme.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultPresentationModeTest {
    @Test
    fun `missing or invalid stored values keep existing app page behavior`() {
        assertEquals(ResultPresentationMode.APP_PAGE, ResultPresentationMode.fromStored(null))
        assertEquals(ResultPresentationMode.APP_PAGE, ResultPresentationMode.fromStored("UNKNOWN"))
    }

    @Test
    fun `overlay mode round trips from preference value`() {
        assertEquals(
            ResultPresentationMode.ACCESSIBILITY_OVERLAY,
            ResultPresentationMode.fromStored(ResultPresentationMode.ACCESSIBILITY_OVERLAY.name),
        )
    }

    @Test
    fun `only app page mode opens result activity automatically`() {
        assertTrue(ResultPresentationMode.APP_PAGE.opensResultActivityAutomatically)
        assertFalse(ResultPresentationMode.ACCESSIBILITY_OVERLAY.opensResultActivityAutomatically)
    }

    @Test
    fun `only overlay mode uses clipboard focus bridge`() {
        assertFalse(ResultPresentationMode.APP_PAGE.usesClipboardFocusBridge)
        assertTrue(ResultPresentationMode.ACCESSIBILITY_OVERLAY.usesClipboardFocusBridge)
    }
}
