package app.stoptrackingme.automation

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityEventPolicyTest {
    private val targets = listOf("target.two", "target.one", "target.one", "")

    @Test
    fun idleScopeListensOnlyToTargetClicksAndWindowChanges() {
        val scope = scope(AutomationStage.IDLE)

        assertEquals(
            AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            scope.eventTypes,
        )
        assertEquals(listOf("target.one", "target.two"), scope.packageNames)
        assertEquals(150L, scope.notificationTimeoutMillis)
    }

    @Test
    fun discoveryWindowTemporarilyAddsContentChanges() {
        val scope = scope(
            stage = AutomationStage.IDLE,
            contentDiscoveryArmed = true,
        )

        assertEquals(
            AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            scope.eventTypes,
        )
    }

    @Test
    fun activeSearchStagesUseOnlyTheirSourcePackageAndContentChanges() {
        val stages = listOf(
            AutomationStage.SHARE_TRIGGERED,
            AutomationStage.AWAIT_COPY_CONFIRMATION,
            AutomationStage.FIND_COPY,
        )

        stages.forEach { stage ->
            val scope = scope(stage = stage, sourcePackage = "source.app")

            assertEquals(listOf("source.app"), scope.packageNames)
            assertEquals(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                scope.eventTypes,
            )
        }
    }

    @Test
    fun eventIndependentStagesDisableAccessibilityCallbacks() {
        val stages = listOf(
            AutomationStage.CLICK_ONCE,
            AutomationStage.CAPTURE,
            AutomationStage.EXTRACT,
            AutomationStage.RESOLVE,
            AutomationStage.CLEAN,
        )

        stages.forEach { stage ->
            assertEquals(0, scope(stage).eventTypes)
        }
    }

    @Test
    fun activeOverlayListensOnlyForGlobalWindowTransitions() {
        val scope = scope(
            stage = AutomationStage.SHOW_RESULT,
            overlayResultActive = true,
        )

        assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, scope.eventTypes)
        assertNull(scope.packageNames)
    }

    @Test
    fun nonOverlayResultAndEmptyCatalogReceiveNoEvents() {
        val resultScope = scope(AutomationStage.SHOW_RESULT)
        val emptyIdleScope = AccessibilityEventPolicy.scopeFor(
            stage = AutomationStage.IDLE,
            rulePackageNames = emptyList(),
            sourcePackage = null,
            contentDiscoveryArmed = false,
            overlayResultActive = false,
        )

        assertEquals(0, resultScope.eventTypes)
        assertEquals(listOf("target.one", "target.two"), resultScope.packageNames)
        assertEquals(0, emptyIdleScope.eventTypes)
        assertEquals(emptyList<String>(), emptyIdleScope.packageNames)
    }

    private fun scope(
        stage: AutomationStage,
        sourcePackage: String? = null,
        contentDiscoveryArmed: Boolean = false,
        overlayResultActive: Boolean = false,
    ): AccessibilityEventScope = AccessibilityEventPolicy.scopeFor(
        stage = stage,
        rulePackageNames = targets,
        sourcePackage = sourcePackage,
        contentDiscoveryArmed = contentDiscoveryArmed,
        overlayResultActive = overlayResultActive,
    )
}
