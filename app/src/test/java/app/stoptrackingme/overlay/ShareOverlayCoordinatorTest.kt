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
    fun `result page open event is delivered to the attached listener`() {
        val listener = ShareOverlayEventListener { event ->
            event == ShareOverlayEvent.ResultPageOpened("session")
        }
        ShareOverlayCoordinator.attach(listener)
        try {
            assertTrue(
                ShareOverlayCoordinator.dispatch(ShareOverlayEvent.ResultPageOpened("session")),
            )
        } finally {
            ShareOverlayCoordinator.detach(listener)
        }
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

    @Test
    fun `wechat navigation completes only after wechat was opened and source is active again`() {
        val sourcePackage = "com.example.source"

        assertEquals(
            WeChatNavigationAction.IGNORE,
            OverlayCompletionPolicy.forWeChatNavigation(
                sourcePackage = sourcePackage,
                eventPackage = "com.tencent.mm",
                activeWindowPackage = sourcePackage,
                weChatWasOpened = false,
            ),
        )
        assertEquals(
            WeChatNavigationAction.MARK_WECHAT_OPENED,
            OverlayCompletionPolicy.forWeChatNavigation(
                sourcePackage = sourcePackage,
                eventPackage = "com.tencent.mm",
                activeWindowPackage = "com.tencent.mm",
                weChatWasOpened = false,
            ),
        )
        assertEquals(
            WeChatNavigationAction.IGNORE,
            OverlayCompletionPolicy.forWeChatNavigation(
                sourcePackage = sourcePackage,
                eventPackage = sourcePackage,
                activeWindowPackage = sourcePackage,
                weChatWasOpened = false,
            ),
        )
        assertEquals(
            WeChatNavigationAction.IGNORE,
            OverlayCompletionPolicy.forWeChatNavigation(
                sourcePackage = sourcePackage,
                eventPackage = sourcePackage,
                activeWindowPackage = "com.tencent.mm",
                weChatWasOpened = true,
            ),
        )
        assertEquals(
            WeChatNavigationAction.COMPLETE,
            OverlayCompletionPolicy.forWeChatNavigation(
                sourcePackage = sourcePackage,
                eventPackage = sourcePackage,
                activeWindowPackage = sourcePackage,
                weChatWasOpened = true,
            ),
        )
    }

    @Test
    fun `qq callback policy completes or restores only matching session`() {
        assertEquals(
            OverlayCompletionAction.COMPLETE,
            OverlayCompletionPolicy.forQQCallback("session", "session", QQOutcome.SUCCESS),
        )
        assertEquals(
            OverlayCompletionAction.RESTORE_CANCELLED,
            OverlayCompletionPolicy.forQQCallback("session", "session", QQOutcome.CANCELLED),
        )
        assertEquals(
            OverlayCompletionAction.RESTORE_FAILED,
            OverlayCompletionPolicy.forQQCallback("session", "session", QQOutcome.NOT_INSTALLED),
        )
        assertEquals(
            OverlayCompletionAction.RESTORE_FAILED,
            OverlayCompletionPolicy.forQQCallback("session", "session", QQOutcome.UNSUPPORTED),
        )
        assertEquals(
            OverlayCompletionAction.IGNORE,
            OverlayCompletionPolicy.forQQCallback("session", "other", QQOutcome.FAILED),
        )
    }
}
