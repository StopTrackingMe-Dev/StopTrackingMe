package app.stoptrackingme.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareOverlayCoordinatorTest {
    @Test
    fun `only attached listener can claim an event`() {
        val listener = ShareOverlayEventListener { event ->
            event == ShareOverlayEvent.ResultReady("session")
        }
        ShareOverlayCoordinator.attach(listener)
        try {
            assertTrue(
                ShareOverlayCoordinator.dispatch(ShareOverlayEvent.ResultReady("session")),
            )
        } finally {
            ShareOverlayCoordinator.detach(listener)
        }

        assertFalse(
            ShareOverlayCoordinator.dispatch(ShareOverlayEvent.ResultReady("session")),
        )
    }

    @Test
    fun `wechat callback policy completes or restores only matching transaction`() {
        assertEquals(
            OverlayCompletionAction.COMPLETE,
            OverlayCompletionPolicy.forWeChatCallback("expected", "expected", WeChatOutcome.SUCCESS),
        )
        assertEquals(
            OverlayCompletionAction.RESTORE_CANCELLED,
            OverlayCompletionPolicy.forWeChatCallback("expected", "expected", WeChatOutcome.CANCELLED),
        )
        assertEquals(
            OverlayCompletionAction.RESTORE_FAILED,
            OverlayCompletionPolicy.forWeChatCallback("expected", "expected", WeChatOutcome.FAILED),
        )
        assertEquals(
            OverlayCompletionAction.IGNORE,
            OverlayCompletionPolicy.forWeChatCallback("expected", "other", WeChatOutcome.SUCCESS),
        )
    }
}
