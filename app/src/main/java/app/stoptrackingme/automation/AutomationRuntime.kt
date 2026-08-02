package app.stoptrackingme.automation

enum class AutomationStage {
    IDLE,
    SHARE_TRIGGERED,
    FIND_COPY,
    CLICK_ONCE,
    CAPTURE,
    EXTRACT,
    RESOLVE,
    CLEAN,
    SHOW_RESULT,
}

data class AutomationSnapshot(
    val sessionId: String?,
    val ruleKey: String?,
    val sourcePackage: String?,
    val stage: AutomationStage,
    val deadlineMillis: Long,
    val scrollAttempted: Boolean,
    val clickAttempted: Boolean,
)

/**
 * Shared only inside this process so the service and foreground activities use one task.
 */
object AutomationRuntime {
    private val lock = Any()
    private var snapshot = idle()

    /** Starts a task only when idle or when an earlier result has been left unclosed. */
    fun start(
        sessionId: String,
        ruleKey: String,
        sourcePackage: String,
        deadlineMillis: Long,
    ): AutomationSnapshot? = synchronized(lock) {
        if (snapshot.stage !in startableStages) return@synchronized null
        snapshot = AutomationSnapshot(
            sessionId = sessionId,
            ruleKey = ruleKey,
            sourcePackage = sourcePackage,
            stage = AutomationStage.SHARE_TRIGGERED,
            deadlineMillis = deadlineMillis,
            scrollAttempted = false,
            clickAttempted = false,
        )
        snapshot
    }

    fun current(): AutomationSnapshot = synchronized(lock) { snapshot }

    /** Records the single optional search scroll before the accessibility action is invoked. */
    fun markScrollAttempt(sessionId: String): Boolean = synchronized(lock) {
        if (snapshot.sessionId != sessionId ||
            snapshot.stage != AutomationStage.FIND_COPY ||
            snapshot.scrollAttempted ||
            snapshot.clickAttempted
        ) {
            return@synchronized false
        }
        snapshot = snapshot.copy(scrollAttempted = true)
        true
    }

    fun transition(sessionId: String, next: AutomationStage): Boolean = synchronized(lock) {
        if (snapshot.sessionId != sessionId) return@synchronized false
        if (next !in allowedNext(snapshot.stage)) return@synchronized false
        snapshot = snapshot.copy(stage = next)
        true
    }

    /** Records the only permitted click attempt before performAction is invoked. */
    fun markClickAttempt(sessionId: String): Boolean = synchronized(lock) {
        if (snapshot.sessionId != sessionId ||
            snapshot.stage != AutomationStage.FIND_COPY ||
            snapshot.clickAttempted
        ) {
            return@synchronized false
        }
        snapshot = snapshot.copy(
            stage = AutomationStage.CLICK_ONCE,
            clickAttempted = true,
        )
        true
    }

    fun isExpired(nowMillis: Long): Boolean = synchronized(lock) {
        snapshot.stage in setOf(AutomationStage.SHARE_TRIGGERED, AutomationStage.FIND_COPY) &&
            nowMillis > snapshot.deadlineMillis
    }

    fun cancelIfSwitchedTo(packageName: String, ownPackageName: String): Boolean = synchronized(lock) {
        val source = snapshot.sourcePackage ?: return@synchronized false
        if (packageName == source || packageName == ownPackageName) return@synchronized false

        // A foreign accessibility event is only relevant while we are still looking for the
        // single permitted click target. After the click, OEM clipboard/assistant windows can
        // legitimately become the event source while the copied value settles. No more node
        // actions are possible in those stages, so keep the session alive for foreground capture.
        if (snapshot.stage != AutomationStage.SHARE_TRIGGERED &&
            snapshot.stage != AutomationStage.FIND_COPY
        ) {
            return@synchronized false
        }
        snapshot = idle()
        true
    }

    fun reset(sessionId: String? = null): Boolean = synchronized(lock) {
        if (sessionId != null && snapshot.sessionId != sessionId) return@synchronized false
        snapshot = idle()
        true
    }

    private fun allowedNext(stage: AutomationStage): Set<AutomationStage> = when (stage) {
        AutomationStage.IDLE -> setOf(AutomationStage.SHARE_TRIGGERED)
        AutomationStage.SHARE_TRIGGERED -> setOf(AutomationStage.FIND_COPY)
        AutomationStage.FIND_COPY -> setOf(AutomationStage.CLICK_ONCE)
        AutomationStage.CLICK_ONCE -> setOf(AutomationStage.CAPTURE)
        AutomationStage.CAPTURE -> setOf(AutomationStage.EXTRACT)
        AutomationStage.EXTRACT -> setOf(AutomationStage.RESOLVE, AutomationStage.SHOW_RESULT)
        AutomationStage.RESOLVE -> setOf(AutomationStage.CLEAN, AutomationStage.SHOW_RESULT)
        AutomationStage.CLEAN -> setOf(AutomationStage.SHOW_RESULT)
        AutomationStage.SHOW_RESULT -> setOf(AutomationStage.IDLE)
    }

    private fun idle() = AutomationSnapshot(
        sessionId = null,
        ruleKey = null,
        sourcePackage = null,
        stage = AutomationStage.IDLE,
        deadlineMillis = 0L,
        scrollAttempted = false,
        clickAttempted = false,
    )

    private val startableStages = setOf(
        AutomationStage.IDLE,
        AutomationStage.SHOW_RESULT,
    )
}
