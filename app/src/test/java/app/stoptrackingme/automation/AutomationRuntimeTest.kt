package app.stoptrackingme.automation

import app.stoptrackingme.rules.NodeSelector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRuntimeTest {
    @After
    fun reset() {
        AutomationRuntime.reset()
    }

    @Test
    fun happyPathUsesFixedStagesAndAllowsOnlyOneClickAttempt() {
        AutomationRuntime.start("session", "rule", "source.app", 1_000)
        assertTrue(AutomationRuntime.transition("session", AutomationStage.FIND_COPY))
        assertTrue(AutomationRuntime.markScrollAttempt("session"))
        assertFalse(AutomationRuntime.markScrollAttempt("session"))
        assertTrue(AutomationRuntime.markClickAttempt("session"))
        assertFalse(AutomationRuntime.markClickAttempt("session"))
        assertTrue(AutomationRuntime.transition("session", AutomationStage.CAPTURE))
        assertTrue(AutomationRuntime.transition("session", AutomationStage.EXTRACT))
        assertTrue(AutomationRuntime.transition("session", AutomationStage.RESOLVE))
        assertTrue(AutomationRuntime.transition("session", AutomationStage.CLEAN))
        assertTrue(AutomationRuntime.transition("session", AutomationStage.SHOW_RESULT))
    }

    @Test
    fun illegalTransitionIsRejected() {
        AutomationRuntime.start("session", "rule", "source.app", 1_000)

        assertFalse(AutomationRuntime.transition("session", AutomationStage.CLICK_ONCE))
        assertEquals(AutomationStage.SHARE_TRIGGERED, AutomationRuntime.current().stage)
    }

    @Test
    fun timeoutAndApplicationSwitchAreDetected() {
        AutomationRuntime.start("session", "rule", "source.app", 100)
        assertTrue(AutomationRuntime.isExpired(101))
        assertFalse(AutomationRuntime.cancelIfSwitchedTo("source.app", "our.app"))
        assertTrue(AutomationRuntime.cancelIfSwitchedTo("other.app", "our.app"))
        assertEquals(AutomationStage.IDLE, AutomationRuntime.current().stage)
    }

    @Test
    fun unrelatedOemEventAfterClickDoesNotDiscardPendingCapture() {
        AutomationRuntime.start("session", "rule", "source.app", 1_000)
        assertTrue(AutomationRuntime.transition("session", AutomationStage.FIND_COPY))
        assertTrue(AutomationRuntime.markClickAttempt("session"))

        assertFalse(AutomationRuntime.cancelIfSwitchedTo("com.huawei.intelligent", "our.app"))
        assertEquals(AutomationStage.CLICK_ONCE, AutomationRuntime.current().stage)
        assertTrue(AutomationRuntime.transition("session", AutomationStage.CAPTURE))
    }

    @Test
    fun transientUiPackageExemptionsStayNarrow() {
        assertTrue(AutomationSafety.isTransientUiPackage("com.android.systemui"))
        assertTrue(AutomationSafety.isTransientUiPackage("com.huawei.intelligent"))
        assertFalse(AutomationSafety.isTransientUiPackage("com.huawei.android.launcher"))
        assertFalse(AutomationSafety.isTransientUiPackage("com.example.other"))
    }

    @Test
    fun selectorMatchesResourceIdAndEitherLabelChannel() {
        val node = FakeNode(
            resourceId = "source.app:id/share",
            text = "",
            contentDescription = "Share",
            className = "android.widget.ImageButton",
            isClickable = true,
        )
        val selector = NodeSelector(
            resourceId = "share",
            textRegex = "^分享$",
            descriptionRegex = "^Share$",
            className = "android.widget.ImageButton",
            clickable = true,
        )

        assertTrue(SelectorMatcher.matches(node, selector))
    }

    @Test
    fun dangerousLabelsRemainBlockedByCode() {
        assertTrue(AutomationSafety.hasDangerousLabel(listOf("发送")))
        assertTrue(AutomationSafety.hasDangerousLabel(listOf("Confirm payment")))
        assertFalse(AutomationSafety.hasDangerousLabel(listOf("复制链接")))
        assertTrue(AutomationSafety.isSensitivePackage("com.android.settings"))
    }

    private data class FakeNode(
        override val resourceId: String,
        override val text: String,
        override val contentDescription: String,
        override val className: String,
        override val isClickable: Boolean,
    ) : AccessibilityNodeView
}
