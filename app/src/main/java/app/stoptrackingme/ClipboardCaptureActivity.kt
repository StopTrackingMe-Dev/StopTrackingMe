package app.stoptrackingme

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.stoptrackingme.automation.AutomationStage
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.session.ShareSessionStore

/**
 * Transparent foreground activity used only to read the clipboard into the in-memory session.
 */
class ClipboardCaptureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var startedReading = false
    private lateinit var sessionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitionAnimation(opening = true)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isBlank() || ShareSessionStore.get(sessionId) == null) close()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !startedReading && ::sessionId.isInitialized && sessionId.isNotBlank()) {
            startedReading = true
            readClipboard(attempt = 1)
        }
    }

    private fun readClipboard(attempt: Int) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val value = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        if (ShareSessionStore.get(sessionId) == null) return close()

        if (value.isNotBlank()) {
            CapturedShareProcessor.process(
                context = this,
                sessionId = sessionId,
                value = value,
                onResultReady = {
                    presentResult()
                    close()
                },
                onAborted = ::close,
            )
        } else if (attempt < MAX_ATTEMPTS) {
            handler.postDelayed({ readClipboard(attempt + 1) }, RETRY_DELAY_MS)
        } else {
            showClipboardFailure()
        }
    }

    private fun showClipboardFailure() {
        CapturedShareProcessor.publishClipboardFailure(
            context = this,
            sessionId = sessionId,
            message = "剪贴板为空或复制尚未完成",
            onResultReady = {
                presentResult()
                close()
            },
            onAborted = ::close,
        )
    }

    private fun presentResult() {
        val presentationMode = ResultPresentationPreferences.get(this)
        if (!presentationMode.opensResultActivityAutomatically) {
            val handled = ShareOverlayCoordinator.dispatch(ShareOverlayEvent.ResultReady(sessionId))
            if (!handled) {
                ServiceStatus.update(
                    this,
                    "悬浮窗显示失败；已停止自动跳转，请重新分享或手动打开应用",
                    AutomationStage.SHOW_RESULT,
                )
            }
            return
        }
        startActivity(
            Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId),
        )
    }

    private fun close() {
        finish()
        disableTransitionAnimation(opening = false)
    }

    @Suppress("DEPRECATION")
    private fun disableTransitionAnimation(opening: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                if (opening) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE,
                0,
                0,
            )
        } else {
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val MAX_ATTEMPTS = 6
        private const val RETRY_DELAY_MS = 200L
    }
}
