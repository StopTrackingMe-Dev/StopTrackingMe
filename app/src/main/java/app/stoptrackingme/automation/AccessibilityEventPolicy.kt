package app.stoptrackingme.automation

import android.view.accessibility.AccessibilityEvent

data class AccessibilityEventScope(
    val eventTypes: Int,
    val packageNames: List<String>?,
    val notificationTimeoutMillis: Long,
)

/**
 * Keeps the accessibility service quiet unless an automation stage needs fresh UI events.
 * A null package list means all packages, matching AccessibilityServiceInfo.packageNames.
 */
object AccessibilityEventPolicy {
    const val NOTIFICATION_TIMEOUT_MILLIS = 150L

    private const val IDLE_EVENT_TYPES =
        AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    private const val DISCOVERY_EVENT_TYPES =
        IDLE_EVENT_TYPES or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    private const val ACTIVE_EVENT_TYPES =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    fun scopeFor(
        stage: AutomationStage,
        rulePackageNames: Collection<String>,
        sourcePackage: String?,
        contentDiscoveryArmed: Boolean,
        overlayResultActive: Boolean,
    ): AccessibilityEventScope {
        val targets = rulePackageNames
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .toList()
        val source = sourcePackage?.takeIf(String::isNotBlank)
        return when (stage) {
            AutomationStage.IDLE -> AccessibilityEventScope(
                eventTypes = when {
                    targets.isEmpty() -> 0
                    contentDiscoveryArmed -> DISCOVERY_EVENT_TYPES
                    else -> IDLE_EVENT_TYPES
                },
                packageNames = targets,
                notificationTimeoutMillis = NOTIFICATION_TIMEOUT_MILLIS,
            )

            AutomationStage.SHARE_TRIGGERED,
            AutomationStage.AWAIT_COPY_CONFIRMATION,
            AutomationStage.FIND_COPY,
            -> AccessibilityEventScope(
                eventTypes = if (source != null || targets.isNotEmpty()) {
                    ACTIVE_EVENT_TYPES
                } else {
                    0
                },
                packageNames = source?.let(::listOf) ?: targets,
                notificationTimeoutMillis = NOTIFICATION_TIMEOUT_MILLIS,
            )

            AutomationStage.SHOW_RESULT -> AccessibilityEventScope(
                eventTypes = if (overlayResultActive) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                } else {
                    0
                },
                packageNames = if (overlayResultActive) null else targets,
                notificationTimeoutMillis = NOTIFICATION_TIMEOUT_MILLIS,
            )

            AutomationStage.CLICK_ONCE,
            AutomationStage.CAPTURE,
            AutomationStage.EXTRACT,
            AutomationStage.RESOLVE,
            AutomationStage.CLEAN,
            -> AccessibilityEventScope(
                eventTypes = 0,
                packageNames = targets,
                notificationTimeoutMillis = NOTIFICATION_TIMEOUT_MILLIS,
            )
        }
    }
}
