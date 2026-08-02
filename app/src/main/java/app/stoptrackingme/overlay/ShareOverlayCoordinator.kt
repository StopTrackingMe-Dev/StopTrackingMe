package app.stoptrackingme.overlay

sealed interface ShareOverlayEvent {
    data class ResultReady(val sessionId: String) : ShareOverlayEvent

    data class ResultPageOpened(val sessionId: String) : ShareOverlayEvent

    data class WeChatFinished(
        val transaction: String?,
        val outcome: WeChatOutcome,
    ) : ShareOverlayEvent
}

enum class WeChatOutcome {
    SUCCESS,
    CANCELLED,
    FAILED,
}

enum class OverlayCompletionAction {
    IGNORE,
    COMPLETE,
    RESTORE_CANCELLED,
    RESTORE_FAILED,
}

object OverlayCompletionPolicy {
    fun forWeChatCallback(
        expectedTransaction: String?,
        receivedTransaction: String?,
        outcome: WeChatOutcome,
    ): OverlayCompletionAction {
        if (expectedTransaction == null || receivedTransaction != expectedTransaction) {
            return OverlayCompletionAction.IGNORE
        }
        return when (outcome) {
            WeChatOutcome.SUCCESS -> OverlayCompletionAction.COMPLETE
            WeChatOutcome.CANCELLED -> OverlayCompletionAction.RESTORE_CANCELLED
            WeChatOutcome.FAILED -> OverlayCompletionAction.RESTORE_FAILED
        }
    }
}

fun interface ShareOverlayEventListener {
    /** Returns true when this listener has taken ownership of presenting the event. */
    fun onOverlayEvent(event: ShareOverlayEvent): Boolean
}

/**
 * Process-local coordination only. URLs and share text never enter broadcasts or persistent state.
 */
object ShareOverlayCoordinator {
    private val lock = Any()
    private var listener: ShareOverlayEventListener? = null

    fun attach(candidate: ShareOverlayEventListener) = synchronized(lock) {
        listener = candidate
    }

    fun detach(candidate: ShareOverlayEventListener) = synchronized(lock) {
        if (listener === candidate) listener = null
    }

    fun dispatch(event: ShareOverlayEvent): Boolean {
        val current = synchronized(lock) { listener }
        return current?.onOverlayEvent(event) == true
    }
}
