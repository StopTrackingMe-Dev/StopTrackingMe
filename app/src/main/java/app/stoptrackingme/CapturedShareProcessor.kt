package app.stoptrackingme

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.automation.AutomationStage
import app.stoptrackingme.link.LinkProcessingStage
import app.stoptrackingme.link.LinkProcessor
import app.stoptrackingme.rules.CleanResult
import app.stoptrackingme.rules.ProcessingFailure
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSessionStore

/**
 * Converts one clipboard value into the process-local result for the active share session.
 * Clipboard access itself stays with the caller because Android grants it based on window focus.
 */
object CapturedShareProcessor {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun process(
        context: Context,
        sessionId: String,
        value: String,
        onResultReady: () -> Unit,
        onAborted: () -> Unit,
    ) {
        val appContext = context.applicationContext
        val session = ShareSessionStore.get(sessionId) ?: return post(onAborted)
        val installed = RuleRepository.get(appContext).findInstalledRule(session.ruleKey)
            ?: return post(onAborted)

        if (value.length > installed.rule.clipboardExtraction.maxInputLength) {
            publishFailure(
                context = appContext,
                sessionId = sessionId,
                failure = ProcessingFailure.URL_NOT_FOUND,
                message = "复制的内容太长，无法识别其中的链接",
                status = "复制的内容太长",
                onResultReady = onResultReady,
                onAborted = onAborted,
            )
            return
        }
        if (!ShareSessionStore.putSourceText(sessionId, value) ||
            !AutomationRuntime.transition(sessionId, AutomationStage.EXTRACT)
        ) {
            post(onAborted)
            return
        }
        ServiceStatus.update(appContext, "正在识别链接", AutomationStage.EXTRACT)
        Thread processing@{
            if (!ShareSessionStore.attachWorker(sessionId, Thread.currentThread())) {
                post(onAborted)
                return@processing
            }
            try {
                val result = LinkProcessor().process(value, installed.rule) { processingStage ->
                    when (processingStage) {
                        LinkProcessingStage.EXTRACT -> Unit
                        LinkProcessingStage.RESOLVE -> {
                            if (AutomationRuntime.transition(sessionId, AutomationStage.RESOLVE)) {
                                ServiceStatus.update(
                                    appContext,
                                    "正在展开短链接…",
                                    AutomationStage.RESOLVE,
                                )
                            }
                        }
                        LinkProcessingStage.CLEAN -> {
                            if (AutomationRuntime.transition(sessionId, AutomationStage.CLEAN)) {
                                ServiceStatus.update(
                                    appContext,
                                    "正在去除追踪参数…",
                                    AutomationStage.CLEAN,
                                )
                            }
                        }
                    }
                }
                if (!ShareSessionStore.putResult(sessionId, result)) {
                    post(onAborted)
                    return@processing
                }
                val current = AutomationRuntime.current()
                val ready = current.sessionId == sessionId &&
                    (current.stage == AutomationStage.SHOW_RESULT ||
                        current.stage in PROCESSING_STAGES &&
                        AutomationRuntime.transition(sessionId, AutomationStage.SHOW_RESULT))
                if (!ready) {
                    post(onAborted)
                    return@processing
                }
                post {
                    ServiceStatus.update(
                        appContext,
                        "链接已净化",
                        AutomationStage.SHOW_RESULT,
                    )
                    onResultReady()
                }
            } finally {
                ShareSessionStore.detachWorker(sessionId, Thread.currentThread())
            }
        }.start()
    }

    fun publishClipboardFailure(
        context: Context,
        sessionId: String,
        message: String,
        onResultReady: () -> Unit,
        onAborted: () -> Unit,
    ) {
        publishFailure(
            context = context.applicationContext,
            sessionId = sessionId,
            failure = ProcessingFailure.CLIPBOARD_EMPTY,
            message = message,
            status = "没能读取到复制的链接",
            onResultReady = onResultReady,
            onAborted = onAborted,
        )
    }

    private fun publishFailure(
        context: Context,
        sessionId: String,
        failure: ProcessingFailure,
        message: String,
        status: String,
        onResultReady: () -> Unit,
        onAborted: () -> Unit,
    ) {
        val result = CleanResult(
            sourceText = "",
            originalUrl = null,
            expandedUrl = null,
            cleanedUrl = null,
            removedParameters = emptyList(),
            warnings = emptyList(),
            urlCount = 0,
            failure = failure,
            failureMessage = message,
            retryable = false,
        )
        if (!ShareSessionStore.putResult(sessionId, result)) {
            post(onAborted)
            return
        }
        val current = AutomationRuntime.current()
        if (current.sessionId != sessionId ||
            current.stage != AutomationStage.CAPTURE ||
            !AutomationRuntime.transition(sessionId, AutomationStage.EXTRACT) ||
            !AutomationRuntime.transition(sessionId, AutomationStage.SHOW_RESULT)
        ) {
            post(onAborted)
            return
        }
        ServiceStatus.update(context, status, AutomationStage.SHOW_RESULT)
        post(onResultReady)
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { block() }
    }

    private val PROCESSING_STAGES = setOf(
        AutomationStage.EXTRACT,
        AutomationStage.RESOLVE,
        AutomationStage.CLEAN,
    )
}
