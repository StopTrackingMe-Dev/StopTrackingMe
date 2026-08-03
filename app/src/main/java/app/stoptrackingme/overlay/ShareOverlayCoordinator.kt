package app.stoptrackingme.overlay

sealed interface ShareOverlayEvent {
    data class ResultReady(val sessionId: String) : ShareOverlayEvent

    data class ResultPageOpened(val sessionId: String) : ShareOverlayEvent

    data class QQShareStarted(val sessionId: String) : ShareOverlayEvent

    data class WeChatFinished(
        val transaction: String?,
        val outcome: WeChatOutcome,
    ) : ShareOverlayEvent

    data class QQShareFinished(
        val sessionId: String,
        val outcome: QQOutcome,
    ) : ShareOverlayEvent
}

enum class WeChatOutcome {
    SUCCESS,
    CANCELLED,
    FAILED,
}

enum class QQOutcome {
    SUCCESS,
    CANCELLED,
    NOT_INSTALLED,
    UNSUPPORTED,
    FAILED,
}

enum class OverlayCompletionAction {
    IGNORE,
    COMPLETE,
    RESTORE_CANCELLED,
    RESTORE_FAILED,
}

enum class WeChatNavigationAction {
    IGNORE,
    MARK_WECHAT_OPENED,
    COMPLETE,
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

    fun forQQCallback(
        expectedSessionId: String?,
        receivedSessionId: String,
        outcome: QQOutcome,
    ): OverlayCompletionAction {
        if (expectedSessionId == null || receivedSessionId != expectedSessionId) {
            return OverlayCompletionAction.IGNORE
        }
        return when (outcome) {
            QQOutcome.SUCCESS -> OverlayCompletionAction.COMPLETE
            QQOutcome.CANCELLED -> OverlayCompletionAction.RESTORE_CANCELLED
            QQOutcome.NOT_INSTALLED,
            QQOutcome.UNSUPPORTED,
            QQOutcome.FAILED,
            -> OverlayCompletionAction.RESTORE_FAILED
        }
    }

    fun forWeChatNavigation(
        sourcePackage: String?,
        eventPackage: String,
        activeWindowPackage: String?,
        weChatWasOpened: Boolean,
    ): WeChatNavigationAction {
        if (sourcePackage.isNullOrBlank()) return WeChatNavigationAction.IGNORE
        if (eventPackage == WECHAT_PACKAGE_NAME && activeWindowPackage != sourcePackage) {
            return WeChatNavigationAction.MARK_WECHAT_OPENED
        }
        if (weChatWasOpened &&
            eventPackage == sourcePackage &&
            activeWindowPackage == sourcePackage
        ) {
            return WeChatNavigationAction.COMPLETE
        }
        return WeChatNavigationAction.IGNORE
    }

    private const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
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
