package app.stoptrackingme

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.automation.AutomationStage
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.link.LinkProcessingStage
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.ProcessingFailure
import app.stoptrackingme.rules.RuleRepository
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
        val session = ShareSessionStore.get(sessionId) ?: return close()
        val installed = RuleRepository.get(this).findInstalledRule(session.ruleKey) ?: return close()

        if (value.isNotBlank()) {
            if (value.length > installed.rule.clipboardExtraction.maxInputLength) {
                return showInputTooLargeFailure()
            }
            if (!ShareSessionStore.putSourceText(sessionId, value)) return close()
            if (!AutomationRuntime.transition(sessionId, AutomationStage.EXTRACT)) return close()
            ServiceStatus.update(this, "正在提取分享 URL", AutomationStage.EXTRACT)
            Thread processing@{
                if (!ShareSessionStore.attachWorker(sessionId, Thread.currentThread())) {
                    runOnUiThread(::close)
                    return@processing
                }
                try {
                    val result = LinkProcessor().process(value, installed.rule) { processingStage ->
                        when (processingStage) {
                            LinkProcessingStage.EXTRACT -> Unit
                            LinkProcessingStage.RESOLVE -> {
                                if (AutomationRuntime.transition(sessionId, AutomationStage.RESOLVE)) {
                                    ServiceStatus.update(this, "正在验证并展开链接", AutomationStage.RESOLVE)
                                }
                            }
                            LinkProcessingStage.CLEAN -> {
                                if (AutomationRuntime.transition(sessionId, AutomationStage.CLEAN)) {
                                    ServiceStatus.update(this, "正在删除追踪参数", AutomationStage.CLEAN)
                                }
                            }
                        }
                    }
                    if (!ShareSessionStore.putResult(sessionId, result)) {
                        runOnUiThread(::close)
                        return@processing
                    }
                    when (AutomationRuntime.current().stage) {
                        AutomationStage.EXTRACT,
                        AutomationStage.RESOLVE,
                        AutomationStage.CLEAN,
                        -> AutomationRuntime.transition(sessionId, AutomationStage.SHOW_RESULT)
                        else -> Unit
                    }
                    runOnUiThread {
                        ServiceStatus.update(this, "处理完成，等待用户确认", AutomationStage.SHOW_RESULT)
                        presentResult()
                        close()
                    }
                } finally {
                    ShareSessionStore.detachWorker(sessionId, Thread.currentThread())
                }
            }.start()
        } else if (attempt < MAX_ATTEMPTS) {
            handler.postDelayed({ readClipboard(attempt + 1) }, RETRY_DELAY_MS)
        } else {
            showClipboardFailure()
        }
    }

    private fun showClipboardFailure() {
        val result = CleanResult(
            sourceText = "",
            originalUrl = null,
            expandedUrl = null,
            cleanedUrl = null,
            removedParameters = emptyList(),
            warnings = emptyList(),
            urlCount = 0,
            failure = ProcessingFailure.CLIPBOARD_EMPTY,
            failureMessage = "剪贴板为空或复制尚未完成",
            retryable = false,
        )
        ShareSessionStore.putResult(sessionId, result)
        AutomationRuntime.transition(sessionId, AutomationStage.EXTRACT)
        AutomationRuntime.transition(sessionId, AutomationStage.SHOW_RESULT)
        ServiceStatus.update(this, "未能读取复制内容", AutomationStage.SHOW_RESULT)
        presentResult()
        close()
    }

    private fun showInputTooLargeFailure() {
        val result = CleanResult(
            sourceText = "",
            originalUrl = null,
            expandedUrl = null,
            cleanedUrl = null,
            removedParameters = emptyList(),
            warnings = emptyList(),
            urlCount = 0,
            failure = ProcessingFailure.URL_NOT_FOUND,
            failureMessage = "剪贴板内容超过规则允许的长度",
            retryable = false,
        )
        ShareSessionStore.putResult(sessionId, result)
        AutomationRuntime.transition(sessionId, AutomationStage.EXTRACT)
        AutomationRuntime.transition(sessionId, AutomationStage.SHOW_RESULT)
        ServiceStatus.update(this, "复制内容超过安全长度限制", AutomationStage.SHOW_RESULT)
        presentResult()
        close()
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
