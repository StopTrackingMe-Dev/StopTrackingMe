package app.stoptrackingme

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import app.stoptrackingme.automation.AccessibilityTree
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.automation.AutomationSafety
import app.stoptrackingme.automation.AutomationStage
import app.stoptrackingme.overlay.ShareOverlayAction
import app.stoptrackingme.overlay.ShareOverlayActionListener
import app.stoptrackingme.overlay.ShareOverlayController
import app.stoptrackingme.overlay.ShareOverlayCoordinator
import app.stoptrackingme.overlay.ShareOverlayEvent
import app.stoptrackingme.overlay.ShareOverlayEventListener
import app.stoptrackingme.overlay.OverlayCompletionAction
import app.stoptrackingme.overlay.OverlayCompletionPolicy
import app.stoptrackingme.presentation.ResultPresentationMode
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.preview.SharePreviewLoader
import app.stoptrackingme.preview.WebSharePreview
import app.stoptrackingme.preview.copiedTextPreview
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.CopyTriggerMode
import app.stoptrackingme.rules.CopyTriggerPreferences
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSessionStore
import java.util.UUID

class ShareAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rules: RuleRepository
    private val fallbackPanelLatch = HashSet<String>()
    private val scheduledScanSessions = HashSet<String>()
    private var awaitingPanelSeenSessionId: String? = null
    private var launchBridgeView: View? = null
    private var clipboardFocusBridgeView: View? = null
    private var clipboardFocusBridgeSessionId: String? = null
    private var clipboardFocusReadStarted = false
    private lateinit var overlayController: ShareOverlayController
    private var overlayPreview: WebSharePreview? = null
    private var overlayPreviewSessionId: String? = null
    private var previewWorker: Thread? = null
    private var pendingWeChat: PendingWeChat? = null
    private var weChatTimeout: Runnable? = null
    private var pendingSystemShareSessionId: String? = null
    private var systemShareLaunchTimeout: Runnable? = null
    private var pendingResultPageSessionId: String? = null
    private var resultPageLaunchTimeout: Runnable? = null
    private val overlayEventListener = ShareOverlayEventListener(::onOverlayEvent)
    private val overlayActionListener = ShareOverlayActionListener(::onOverlayAction)

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = RuleRepository.get(this)
        rules.reload()
        awaitingPanelSeenSessionId = null
        cancelSystemShareLaunch()
        cancelResultPageLaunch()
        removeClipboardFocusBridge()
        cancelOverlayWorkers()
        if (::overlayController.isInitialized) overlayController.remove()
        overlayController = ShareOverlayController(this, overlayActionListener)
        ShareOverlayCoordinator.attach(overlayEventListener)
        val previous = AutomationRuntime.current()
        if (previous.stage == AutomationStage.SHOW_RESULT && previous.sessionId != null) {
            val restored =
                ResultPresentationPreferences.get(this) ==
                    ResultPresentationMode.ACCESSIBILITY_OVERLAY &&
                    presentOverlayResult(previous.sessionId)
            ServiceStatus.update(
                this,
                if (restored) "服务已重新连接，已恢复净化结果悬浮窗" else "服务已重新连接，净化结果仍在等待确认",
                AutomationStage.SHOW_RESULT,
            )
        } else {
            AutomationRuntime.reset()
            ShareSessionStore.clear(previous.sessionId)
            ServiceStatus.update(this, "服务已连接，等待受支持应用中的分享操作", AutomationStage.IDLE)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        val snapshotBeforeEvent = AutomationRuntime.current()
        val stageBeforeEvent = snapshotBeforeEvent.stage
        if (stageBeforeEvent == AutomationStage.SHOW_RESULT &&
            overlayController.activeSessionId == snapshotBeforeEvent.sessionId
        ) {
            if (AutomationSafety.isTransientUiPackage(eventPackage)) return
            val sourceStillActive = rootInActiveWindow?.packageName?.toString() ==
                snapshotBeforeEvent.sourcePackage
            if (eventPackage != snapshotBeforeEvent.sourcePackage &&
                !sourceStillActive &&
                pendingWeChat == null
            ) {
                finishOverlaySession(
                    snapshotBeforeEvent.sessionId.orEmpty(),
                    if (pendingSystemShareSessionId == snapshotBeforeEvent.sessionId) {
                        "已打开系统分享，本次悬浮分享会话已结束"
                    } else {
                        "已离开来源应用，本次悬浮分享会话已结束"
                    },
                )
            }
            return
        }
        if (stageBeforeEvent in EVENT_INDEPENDENT_STAGES) {
            // Once the only permitted click has been attempted, no later accessibility event is
            // needed to finish the task. Bilibili can emit a large backlog of content changes and
            // even a delayed duplicate share click here; consuming it could starve the capture
            // callback or replace its session just as the clipboard settles.
            return
        }
        if (isDebugBuild && stageBeforeEvent != AutomationStage.IDLE) {
            Log.d(
                TAG,
                "Automation event package=$eventPackage type=${event.eventType} stage=$stageBeforeEvent",
            )
        }
        if (AutomationSafety.isTransientUiPackage(eventPackage) &&
            stageBeforeEvent != AutomationStage.IDLE
        ) {
            // Clipboard notices, Huawei assistant workspace events, emulator overlays, and other
            // transient UI must not look like a deliberate application switch. Every scheduled
            // scan still verifies that the clickable root belongs to the selected source rule.
            return
        }
        val activeSource = AutomationRuntime.current().sourcePackage
        if (activeSource != null &&
            eventPackage != activeSource &&
            rootInActiveWindow?.packageName?.toString() == activeSource
        ) {
            // Ignore unrelated accessibility events while the supported source remains active.
            return
        }
        if (AutomationRuntime.cancelIfSwitchedTo(eventPackage, packageName)) {
            awaitingPanelSeenSessionId = null
            overlayController.remove()
            ShareSessionStore.clear()
            ServiceStatus.update(
                this,
                if (isDebugBuild) {
                    "检测到其他应用事件（$eventPackage），本次自动化已终止"
                } else {
                    "已切换应用，本次自动化已终止"
                },
                AutomationStage.IDLE,
            )
        }

        val installed = when (val resolution = rules.resolveActiveRule(eventPackage)) {
            is ActiveRuleResolution.Active -> resolution.installed
            is ActiveRuleResolution.Conflict -> {
                ServiceStatus.update(this, "检测到规则冲突，请先在应用内选择唯一活动规则")
                return
            }
            is ActiveRuleResolution.InvalidSelection -> {
                ServiceStatus.update(this, "此前选择的规则已失效，请重新选择活动规则")
                return
            }
            ActiveRuleResolution.NoRule -> return
        }
        if (AutomationSafety.isSensitivePackage(eventPackage)) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            AccessibilityTree.matchesNodeOrAncestors(
                event.source,
                installed.rule.shareTriggerSelectors,
                installed.rule.maxClickableParentDepth,
            )
        ) {
            beginTask(installed)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val current = AutomationRuntime.current()
            if (current.sourcePackage == eventPackage &&
                current.stage == AutomationStage.AWAIT_COPY_CONFIRMATION
            ) {
                observeAwaitingCopyPanel(installed)
                return
            }
            if (current.sourcePackage == eventPackage &&
                current.stage in setOf(AutomationStage.SHARE_TRIGGERED, AutomationStage.FIND_COPY)
            ) {
                tryClickCopyLink(installed)
                return
            }

            val root = rootInActiveWindow ?: return
            val fingerprintPresent = AccessibilityTree.hasCompleteFingerprint(
                root,
                installed.rule.sharePanelFingerprint,
            )
            if (!fingerprintPresent) {
                fallbackPanelLatch.remove(eventPackage)
            } else if (fallbackPanelLatch.add(eventPackage)) {
                beginTask(installed, fallback = true)
            }
        }
    }

    override fun onInterrupt() {
        awaitingPanelSeenSessionId = null
        cancelSystemShareLaunch()
        cancelResultPageLaunch()
        removeClipboardFocusBridge()
        val sessionId = AutomationRuntime.current().sessionId
        cancelOverlayWorkers()
        if (::overlayController.isInitialized) overlayController.remove(sessionId)
        AutomationRuntime.reset()
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, "服务已中断，本次自动化已终止", AutomationStage.IDLE)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        awaitingPanelSeenSessionId = null
        cancelSystemShareLaunch()
        cancelResultPageLaunch()
        removeClipboardFocusBridge()
        cancelOverlayWorkers()
        ShareOverlayCoordinator.detach(overlayEventListener)
        if (::overlayController.isInitialized) overlayController.remove()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayController.isInitialized) overlayController.onConfigurationChanged(newConfig)
    }

    private fun beginTask(installed: InstalledRule, fallback: Boolean = false) {
        val oldSessionId = AutomationRuntime.current().sessionId
        val awaitCopyConfirmation = shouldAwaitCopyConfirmation(installed)
        val sessionId = ShareSessionStore.begin(
            ruleKey = installed.key,
            sourcePackage = installed.rule.target.packageName,
        )
        val taskTimeoutMs = if (awaitCopyConfirmation) {
            maxOf(installed.rule.sharePanelTimeoutMs, USER_CONFIRMATION_TIMEOUT_MS)
        } else {
            installed.rule.sharePanelTimeoutMs
        }
        val deadline = System.currentTimeMillis() + taskTimeoutMs
        val started = AutomationRuntime.start(
            sessionId = sessionId,
            ruleKey = installed.key,
            sourcePackage = installed.rule.target.packageName,
            deadlineMillis = deadline,
        )
        if (started == null) {
            ShareSessionStore.clear(sessionId)
            return
        }
        fallbackPanelLatch += installed.rule.target.packageName
        awaitingPanelSeenSessionId = if (awaitCopyConfirmation && fallback) sessionId else null
        if (oldSessionId != null) {
            cancelOverlayWorkers()
            if (::overlayController.isInitialized) overlayController.remove(oldSessionId)
            ShareSessionStore.clear(oldSessionId)
        }
        if (awaitCopyConfirmation) {
            if (!AutomationRuntime.transition(
                    sessionId,
                    AutomationStage.AWAIT_COPY_CONFIRMATION,
                ) ||
                !overlayController.showCopyConfirmation(
                    sessionId,
                    installed.rule.displayName,
                )
            ) {
                cancelTask(sessionId, "未能显示按需净化悬浮入口")
                return
            }
        } else if (ResultPresentationPreferences.get(this) ==
            ResultPresentationMode.ACCESSIBILITY_OVERLAY
        ) {
            overlayController.showProgress(sessionId)
        }
        ServiceStatus.update(
            this,
            if (awaitCopyConfirmation) {
                "分享面板已打开；点击悬浮按钮后才会复制链接"
            } else if (fallback) {
                "已确认完整分享面板指纹，正在查找复制链接"
            } else {
                "已捕获分享点击，正在查找复制链接"
            },
            if (awaitCopyConfirmation) {
                AutomationStage.AWAIT_COPY_CONFIRMATION
            } else {
                AutomationStage.SHARE_TRIGGERED
            },
        )
        if (awaitCopyConfirmation && fallback) observeAwaitingCopyPanel(installed)
        if (!awaitCopyConfirmation) scheduleCopyScan(installed, INITIAL_SCAN_DELAY_MS)
        handler.postDelayed({
            val current = AutomationRuntime.current()
            if (current.sessionId == sessionId && AutomationRuntime.isExpired(System.currentTimeMillis())) {
                cancelTask(
                    sessionId,
                    if (current.stage == AutomationStage.AWAIT_COPY_CONFIRMATION) {
                        "等待用户点击悬浮按钮超时，本次自动化已结束"
                    } else {
                        "查找复制链接超时，本次自动化已终止"
                    },
                )
            }
        }, taskTimeoutMs + 50L)
    }

    private fun shouldAwaitCopyConfirmation(installed: InstalledRule): Boolean =
        ResultPresentationPreferences.get(this) == ResultPresentationMode.ACCESSIBILITY_OVERLAY &&
            CopyTriggerPreferences.get(this, installed) == CopyTriggerMode.USER_CONFIRMATION

    private fun observeAwaitingCopyPanel(installed: InstalledRule) {
        val current = AutomationRuntime.current()
        val sessionId = current.sessionId ?: return
        if (current.stage != AutomationStage.AWAIT_COPY_CONFIRMATION ||
            current.ruleKey != installed.key
        ) {
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != current.sourcePackage) return
        updateOverlayGeometry(root, installed)
        if (AccessibilityTree.hasCompleteFingerprint(
                root,
                installed.rule.sharePanelFingerprint,
            )
        ) {
            awaitingPanelSeenSessionId = sessionId
        } else if (awaitingPanelSeenSessionId == sessionId) {
            fallbackPanelLatch.remove(installed.rule.target.packageName)
            cancelTask(sessionId, "分享面板已关闭，未执行复制链接")
        }
    }

    private fun confirmDeferredCopy(sessionId: String) {
        val current = AutomationRuntime.current()
        if (current.sessionId != sessionId ||
            current.stage != AutomationStage.AWAIT_COPY_CONFIRMATION
        ) {
            return
        }
        val session = ShareSessionStore.get(sessionId) ?: return
        val installed = rules.findInstalledRule(session.ruleKey) ?: return
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != current.sourcePackage) {
            cancelTask(sessionId, "已离开来源应用，未执行复制链接")
            return
        }
        if (!AccessibilityTree.hasCompleteFingerprint(
                root,
                installed.rule.sharePanelFingerprint,
            )
        ) {
            if (awaitingPanelSeenSessionId == sessionId) {
                cancelTask(sessionId, "分享面板已关闭，未执行复制链接")
            } else {
                overlayController.updateStatus(sessionId, "分享面板尚未准备好，请稍后再点")
                ServiceStatus.update(
                    this,
                    "分享面板尚未准备好，仍在等待用户确认",
                    AutomationStage.AWAIT_COPY_CONFIRMATION,
                )
            }
            return
        }
        if (!AutomationRuntime.transition(sessionId, AutomationStage.FIND_COPY) ||
            !overlayController.beginConfirmedCopy(sessionId)
        ) {
            cancelTask(sessionId, "无法开始按需净化")
            return
        }
        awaitingPanelSeenSessionId = null
        updateOverlayGeometry(root, installed)
        ServiceStatus.update(this, "用户已确认，正在查找复制链接", AutomationStage.FIND_COPY)
        scheduleCopyScan(installed, delayMillis = 0L)
    }

    private fun tryClickCopyLink(installed: InstalledRule) {
        val current = AutomationRuntime.current()
        val sessionId = current.sessionId ?: return
        if (current.ruleKey != installed.key ||
            current.sourcePackage != installed.rule.target.packageName ||
            current.clickAttempted ||
            AutomationRuntime.isExpired(System.currentTimeMillis())
        ) {
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            scheduleCopyScan(installed)
            return
        }
        val rootPackage = root.packageName?.toString().orEmpty()
        if (AutomationSafety.isTransientUiPackage(rootPackage)) {
            scheduleCopyScan(installed)
            return
        }
        if (rootPackage != installed.rule.target.packageName) {
            cancelTask(sessionId, "活动窗口已变化，本次自动化已终止")
            return
        }
        updateOverlayGeometry(root, installed)
        if (current.stage == AutomationStage.SHARE_TRIGGERED) {
            AutomationRuntime.transition(sessionId, AutomationStage.FIND_COPY)
            overlayController.updateStatus(sessionId, "正在定位“复制链接”…")
            ServiceStatus.update(this, "正在匹配复制链接节点", AutomationStage.FIND_COPY)
        }
        val match = AccessibilityTree.findFirstClickable(
            root,
            installed.rule.copyLinkSelectors,
            installed.rule.maxClickableParentDepth,
        )
        if (match == null) {
            val scrollTarget = if (!current.scrollAttempted) {
                AccessibilityTree.findFirstScrollableAncestor(
                    root,
                    installed.rule.copyLinkScrollAnchorSelectors,
                    installed.rule.maxClickableParentDepth,
                )
            } else {
                null
            }
            if (scrollTarget != null && AutomationRuntime.markScrollAttempt(sessionId)) {
                overlayController.updateStatus(sessionId, "正在展开分享渠道…")
                ServiceStatus.update(this, "正在滚动分享渠道以查找复制链接", AutomationStage.FIND_COPY)
                val scrolled = scrollTowardCopyLink(scrollTarget)
                if (isDebugBuild) Log.i(TAG, "Copy-link channel scroll performed=$scrolled")
                overlayController.updateStatus(
                    sessionId,
                    if (scrolled) {
                        "已滑动分享渠道，正在继续查找“复制链接”…"
                    } else {
                        "分享渠道暂时无法滑动，正在继续查找…"
                    },
                )
            }
            scheduleCopyScan(installed)
            return
        }
        val labelNode = match.labelNode
        val clickable = match.clickableNode
        val labels = AccessibilityTree.labelsToAncestor(
            labelNode,
            clickable,
            installed.rule.maxClickableParentDepth,
        )
        if (AutomationSafety.hasDangerousLabel(labels)) {
            cancelTask(sessionId, "节点包含危险操作文字，已阻止点击")
            return
        }
        if (!AutomationRuntime.markClickAttempt(sessionId)) return
        overlayController.updateStatus(sessionId, "已找到链接，正在复制…")
        ServiceStatus.update(this, "正在执行本次任务唯一一次复制点击", AutomationStage.CLICK_ONCE)
        val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            cancelTask(sessionId, "复制链接节点点击失败，本次任务不会重试点击")
            return
        }
        handler.postDelayed({
            if (!AutomationRuntime.transition(sessionId, AutomationStage.CAPTURE)) return@postDelayed
            overlayController.updateStatus(sessionId, "正在读取并解析复制内容…")
            ServiceStatus.update(this, "正在读取本次复制内容", AutomationStage.CAPTURE)
            captureClipboard(sessionId)
        }, installed.rule.copySettleDelayMs)
    }

    private fun scrollTowardCopyLink(node: AccessibilityNodeInfo): Boolean {
        val supportedActionIds = node.actionList.mapTo(HashSet()) { it.id }
        val directionalActions = listOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN,
        )
        directionalActions.forEach { action ->
            if (action.id in supportedActionIds && node.performAction(action.id)) return true
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun captureClipboard(sessionId: String) {
        if (ResultPresentationPreferences.get(this).usesClipboardFocusBridge) {
            captureClipboardWithFocusBridge(sessionId)
        } else {
            launchClipboardCapture(sessionId)
        }
    }

    /**
     * A focusable accessibility overlay can make this UID eligible for clipboard access without
     * starting an Activity. The one-pixel window never receives touch input and is removed as
     * soon as the copied value has been captured, leaving the source share Activity resumed.
     */
    private fun captureClipboardWithFocusBridge(sessionId: String) {
        if (!attachClipboardFocusBridge(sessionId)) {
            publishClipboardFocusFailure(
                sessionId,
                "系统未能创建无干扰剪贴板读取窗口；分享页已保持打开",
            )
            return
        }
        handler.postDelayed({
            val current = AutomationRuntime.current()
            if (clipboardFocusBridgeSessionId == sessionId &&
                !clipboardFocusReadStarted &&
                current.sessionId == sessionId &&
                current.stage == AutomationStage.CAPTURE
            ) {
                publishClipboardFocusFailure(
                    sessionId,
                    "系统未授予悬浮窗剪贴板焦点；分享页已保持打开",
                )
            }
        }, CLIPBOARD_FOCUS_WATCHDOG_MS)
    }

    private fun attachClipboardFocusBridge(sessionId: String): Boolean {
        removeClipboardFocusBridge()
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = true
            isFocusableInTouchMode = true
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            // EMUI 12 crashes ViewRootImpl if this callback removes its own window synchronously.
            // Defer all clipboard work so the framework can finish dispatching the focus change.
            if (hasFocus) handler.post { beginClipboardFocusRead(sessionId, view) }
        }
        val params = WindowManager.LayoutParams(
            CLIPBOARD_FOCUS_BRIDGE_SIZE_PX,
            CLIPBOARD_FOCUS_BRIDGE_SIZE_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = CLIPBOARD_FOCUS_BRIDGE_ALPHA
            title = "StopTrackingClipboardFocusBridge"
        }
        clipboardFocusBridgeView = view
        clipboardFocusBridgeSessionId = sessionId
        clipboardFocusReadStarted = false
        return try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            view.post { if (clipboardFocusBridgeView === view) view.requestFocus() }
            if (isDebugBuild) Log.i(TAG, "Clipboard focus bridge attached")
            true
        } catch (error: RuntimeException) {
            clipboardFocusBridgeView = null
            clipboardFocusBridgeSessionId = null
            if (isDebugBuild) Log.w(TAG, "Unable to attach clipboard focus bridge", error)
            false
        }
    }

    private fun beginClipboardFocusRead(sessionId: String, view: View) {
        if (clipboardFocusBridgeView !== view ||
            clipboardFocusBridgeSessionId != sessionId ||
            clipboardFocusReadStarted
        ) {
            return
        }
        val current = AutomationRuntime.current()
        if (current.sessionId != sessionId || current.stage != AutomationStage.CAPTURE) {
            removeClipboardFocusBridge()
            return
        }
        clipboardFocusReadStarted = true
        if (isDebugBuild) Log.i(TAG, "Clipboard focus bridge gained window focus")
        readClipboardFromFocusBridge(sessionId, view, attempt = 1)
    }

    private fun readClipboardFromFocusBridge(sessionId: String, view: View, attempt: Int) {
        val current = AutomationRuntime.current()
        if (clipboardFocusBridgeView !== view ||
            clipboardFocusBridgeSessionId != sessionId ||
            current.sessionId != sessionId ||
            current.stage != AutomationStage.CAPTURE
        ) {
            return
        }
        val value = if (view.hasWindowFocus()) {
            runCatching {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
                } else {
                    ""
                }
            }.onFailure { error ->
                if (isDebugBuild) Log.w(TAG, "Clipboard focus bridge read failed", error)
            }.getOrDefault("")
        } else {
            ""
        }
        if (value.isNotBlank()) {
            if (isDebugBuild) Log.i(TAG, "Clipboard focus bridge read succeeded")
            removeClipboardFocusBridge()
            CapturedShareProcessor.process(
                context = this,
                sessionId = sessionId,
                value = value,
                onResultReady = { presentCapturedOverlayResult(sessionId) },
                onAborted = { abortCapturedShareProcessing(sessionId) },
            )
        } else if (attempt < CLIPBOARD_READ_MAX_ATTEMPTS) {
            handler.postDelayed(
                { readClipboardFromFocusBridge(sessionId, view, attempt + 1) },
                CLIPBOARD_READ_RETRY_MS,
            )
        } else {
            publishClipboardFocusFailure(
                sessionId,
                "系统未允许悬浮窗读取剪贴板；分享页已保持打开",
            )
        }
    }

    private fun publishClipboardFocusFailure(sessionId: String, message: String) {
        if (clipboardFocusBridgeSessionId != null &&
            clipboardFocusBridgeSessionId != sessionId
        ) {
            return
        }
        removeClipboardFocusBridge()
        val current = AutomationRuntime.current()
        if (current.sessionId != sessionId || current.stage != AutomationStage.CAPTURE) return
        CapturedShareProcessor.publishClipboardFailure(
            context = this,
            sessionId = sessionId,
            message = message,
            onResultReady = { presentCapturedOverlayResult(sessionId) },
            onAborted = { abortCapturedShareProcessing(sessionId) },
        )
    }

    private fun presentCapturedOverlayResult(sessionId: String) {
        if (!presentOverlayResult(sessionId)) {
            ServiceStatus.update(
                this,
                "悬浮窗显示失败；分享页已保持打开，请重新分享",
                AutomationStage.SHOW_RESULT,
            )
        }
    }

    private fun abortCapturedShareProcessing(sessionId: String) {
        val current = AutomationRuntime.current()
        if (current.sessionId == sessionId) {
            cancelTask(sessionId, "复制内容处理已终止")
        }
    }

    private fun removeClipboardFocusBridge() {
        val view = clipboardFocusBridgeView ?: return
        clipboardFocusBridgeView = null
        clipboardFocusBridgeSessionId = null
        clipboardFocusReadStarted = false
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure { error ->
            if (isDebugBuild) Log.w(TAG, "Unable to remove clipboard focus bridge", error)
        }
    }

    /**
     * APP_PAGE mode still needs a foreground Activity. EMUI may reject that launch unless this
     * UID first owns a visible accessibility window, so retain the non-focusable launch bridge.
     */
    private fun launchClipboardCapture(sessionId: String) {
        val bridgeAttached = attachLaunchBridge()
        handler.postDelayed({
            val current = AutomationRuntime.current()
            if (current.sessionId != sessionId || current.stage != AutomationStage.CAPTURE) {
                removeLaunchBridge()
                return@postDelayed
            }
            val launchError = runCatching {
                startActivity(
                    Intent(this, ClipboardCaptureActivity::class.java)
                        .putExtra(ClipboardCaptureActivity.EXTRA_SESSION_ID, sessionId)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                        ),
                )
            }.exceptionOrNull()
            if (launchError != null) {
                removeLaunchBridge()
                if (isDebugBuild) Log.w(TAG, "Unable to launch clipboard capture", launchError)
                ServiceStatus.update(
                    this,
                    "系统未能自动打开，请点开净链分享助手继续",
                    AutomationStage.CAPTURE,
                )
                return@postDelayed
            }
            handler.postDelayed(::removeLaunchBridge, LAUNCH_BRIDGE_LIFETIME_MS)
            handler.postDelayed({
                val latest = AutomationRuntime.current()
                if (latest.sessionId == sessionId && latest.stage == AutomationStage.CAPTURE) {
                    ServiceStatus.update(
                        this,
                        "系统暂未允许自动打开，请点开净链分享助手继续",
                        AutomationStage.CAPTURE,
                    )
                }
            }, CAPTURE_LAUNCH_WATCHDOG_MS)
        }, if (bridgeAttached) LAUNCH_BRIDGE_SETTLE_MS else 0L)
    }

    private fun attachLaunchBridge(): Boolean {
        removeLaunchBridge()
        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = WindowManager.LayoutParams(
            LAUNCH_BRIDGE_SIZE_PX,
            LAUNCH_BRIDGE_SIZE_PX,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = LAUNCH_BRIDGE_ALPHA
            title = "StopTrackingLaunchBridge"
        }
        return try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            launchBridgeView = view
            true
        } catch (error: RuntimeException) {
            if (isDebugBuild) Log.w(TAG, "Unable to attach accessibility launch bridge", error)
            false
        }
    }

    private fun removeLaunchBridge() {
        val view = launchBridgeView ?: return
        launchBridgeView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view)
        }.onFailure { error ->
            if (isDebugBuild) Log.w(TAG, "Unable to remove accessibility launch bridge", error)
        }
    }

    private fun scheduleCopyScan(
        installed: InstalledRule,
        delayMillis: Long = COPY_SCAN_RETRY_DELAY_MS,
    ) {
        val current = AutomationRuntime.current()
        val sessionId = current.sessionId ?: return
        if (current.ruleKey != installed.key ||
            current.sourcePackage != installed.rule.target.packageName ||
            current.stage !in setOf(AutomationStage.SHARE_TRIGGERED, AutomationStage.FIND_COPY) ||
            current.clickAttempted ||
            AutomationRuntime.isExpired(System.currentTimeMillis()) ||
            !scheduledScanSessions.add(sessionId)
        ) {
            return
        }
        handler.postDelayed({
            scheduledScanSessions.remove(sessionId)
            val latest = AutomationRuntime.current()
            if (latest.sessionId == sessionId &&
                latest.stage in setOf(AutomationStage.SHARE_TRIGGERED, AutomationStage.FIND_COPY)
            ) {
                tryClickCopyLink(installed)
            }
        }, delayMillis)
    }

    private fun updateOverlayGeometry(root: AccessibilityNodeInfo, installed: InstalledRule) {
        if (overlayController.activeSessionId != AutomationRuntime.current().sessionId) return
        val windowBounds = Rect()
        root.window?.getBoundsInScreen(windowBounds)
        if (windowBounds.isEmpty) root.getBoundsInScreen(windowBounds)
        val fingerprintBounds = AccessibilityTree.findMatchingBounds(
            root,
            installed.rule.sharePanelFingerprint,
        )
        val panelTop = if (
            AccessibilityTree.hasCompleteFingerprint(root, installed.rule.sharePanelFingerprint)
        ) {
            fingerprintBounds.minOfOrNull(Rect::top)
        } else {
            null
        }
        val clickableBounds = AccessibilityTree.collectClickableBounds(root)
            .filter { bounds -> panelTop == null || bounds.bottom >= panelTop }
        overlayController.updateGeometry(
            sourceWindowBounds = windowBounds.takeUnless { it.isEmpty },
            detectedPanelTop = panelTop,
            clickableBounds = clickableBounds,
        )
    }

    private fun onOverlayEvent(event: ShareOverlayEvent): Boolean = when (event) {
        is ShareOverlayEvent.ResultReady -> presentOverlayResult(event.sessionId)
        is ShareOverlayEvent.ResultPageOpened -> handleResultPageOpened(event.sessionId)
        is ShareOverlayEvent.WeChatFinished -> handleWeChatFinished(event)
    }

    private fun presentOverlayResult(sessionId: String): Boolean {
        if (ResultPresentationPreferences.get(this) !=
            ResultPresentationMode.ACCESSIBILITY_OVERLAY
        ) {
            overlayController.remove(sessionId)
            return false
        }
        val snapshot = AutomationRuntime.current()
        if (snapshot.sessionId != sessionId || snapshot.stage != AutomationStage.SHOW_RESULT) {
            return false
        }
        val session = ShareSessionStore.get(sessionId) ?: return false
        val result = session.result ?: return false
        val installed = rules.findInstalledRule(session.ruleKey) ?: return false
        cancelOverlayWorkers()
        val needsPreview = result.isSuccess && installed.rule.sharePreview != null
        val status = when {
            !result.isSuccess -> result.failureMessage ?: "没有生成可分享的净化链接"
            needsPreview -> "链接已净化，正在准备微信卡片…"
            else -> "链接已净化，可以复制或分享"
        }
        if (!overlayController.showResult(
                sessionId = sessionId,
                result = result,
                sourceName = installed.rule.displayName,
                previewReady = result.isSuccess && !needsPreview,
                status = status,
            )
        ) {
            overlayController.remove(sessionId)
            return false
        }
        ServiceStatus.update(this, "处理完成，已显示净化结果悬浮窗", AutomationStage.SHOW_RESULT)
        if (needsPreview) loadOverlayPreview(sessionId, installed)
        return true
    }

    private fun loadOverlayPreview(sessionId: String, installed: InstalledRule) {
        val result = ShareSessionStore.get(sessionId)?.result ?: return
        val cleanedUrl = result.cleanedUrl ?: return
        val previewRule = installed.rule.sharePreview ?: return
        val worker = Thread {
            val currentWorker = Thread.currentThread()
            val fallback = copiedTextPreview(
                sourceName = installed.rule.displayName,
                sourceText = result.sourceText,
                urlRegex = installed.rule.clipboardExtraction.urlRegex,
                defaultHost = cleanedUrl.toUri().host.orEmpty(),
            )
            val loaded = runCatching {
                SharePreviewLoader().load(
                    cleanedUrl = cleanedUrl,
                    sourceName = installed.rule.displayName,
                    rule = previewRule,
                    networkPolicy = installed.rule.redirectPolicy,
                    fallbackText = fallback.description,
                )
            }
            handler.post {
                if (currentWorker.isInterrupted || previewWorker !== currentWorker ||
                    AutomationRuntime.current().sessionId != sessionId ||
                    overlayController.activeSessionId != sessionId
                ) {
                    return@post
                }
                overlayPreviewSessionId = sessionId
                overlayPreview = loaded.getOrElse { fallback }
                val message = when {
                    loaded.isFailure -> "链接已净化；微信卡片已回退为应用复制文案"
                    loaded.getOrNull()?.thumbnail == null -> "链接已净化；网页没有可用封面"
                    else -> "链接和微信卡片已准备完成"
                }
                overlayController.updateStatus(sessionId, message, previewReady = true)
            }
        }.apply {
            name = "share-overlay-preview"
            isDaemon = true
        }
        previewWorker = worker
        worker.start()
    }

    private fun onOverlayAction(sessionId: String, action: ShareOverlayAction) {
        if (overlayController.activeSessionId != sessionId) return
        when (action) {
            ShareOverlayAction.START_COPY -> confirmDeferredCopy(sessionId)
            ShareOverlayAction.SYSTEM_SHARE -> openOverlaySystemShare(sessionId)
            ShareOverlayAction.WECHAT_FRIEND -> openOverlayWeChat(
                sessionId,
                WeChatShare.Destination.FRIEND,
            )
            ShareOverlayAction.WECHAT_TIMELINE -> openOverlayWeChat(
                sessionId,
                WeChatShare.Destination.TIMELINE,
            )
            ShareOverlayAction.COPY -> copyOverlayLink(sessionId)
            ShareOverlayAction.OPEN_FULL_RESULT -> openFullResult(sessionId)
            ShareOverlayAction.CLOSE -> finishOverlaySession(sessionId, "本次分享会话已关闭")
        }
    }

    private fun openOverlaySystemShare(sessionId: String) {
        if (pendingSystemShareSessionId != null ||
            pendingResultPageSessionId != null ||
            overlayController.activeSessionId != sessionId
        ) {
            return
        }
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        pendingSystemShareSessionId = sessionId

        // Keep a visible window owned by this UID until the chooser launch has crossed the
        // system-server boundary. Some OEMs silently block startActivity() from a bound service
        // once the tappable overlay is removed, without returning an error to this process.
        val launchDelay = if (attachLaunchBridge()) LAUNCH_BRIDGE_SETTLE_MS else 0L
        val timeout = Runnable {
            if (pendingSystemShareSessionId != sessionId) return@Runnable
            cancelSystemShareLaunch(sessionId)
            overlayController.restore(sessionId, "系统未允许打开分享面板，请重试")
            ServiceStatus.update(
                this,
                "系统分享未能打开，净化结果仍在悬浮窗中",
                AutomationStage.SHOW_RESULT,
            )
        }
        systemShareLaunchTimeout = timeout
        handler.postDelayed(timeout, launchDelay + SYSTEM_SHARE_LAUNCH_TIMEOUT_MS)
        handler.postDelayed({
            if (pendingSystemShareSessionId != sessionId ||
                overlayController.activeSessionId != sessionId
            ) {
                return@postDelayed
            }
            val launchError = runCatching {
                startActivity(
                    ShareIntentFactory.createChooser(cleanedUrl)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.exceptionOrNull()
            if (launchError != null) {
                if (isDebugBuild) {
                    Log.w(TAG, "Unable to open system share from overlay", launchError)
                }
                cancelSystemShareLaunch(sessionId)
                overlayController.updateStatus(sessionId, "系统分享面板打开失败，请重试")
                return@postDelayed
            }

            // A blocked background launch has no direct return value. Hide the result while we
            // wait for the chooser's accessibility event; the watchdog restores it if none arrives.
            overlayController.hide(sessionId)
            handler.postDelayed(::removeLaunchBridge, LAUNCH_BRIDGE_LIFETIME_MS)
        }, launchDelay)
    }

    private fun openOverlayWeChat(sessionId: String, destination: WeChatShare.Destination) {
        val session = ShareSessionStore.get(sessionId) ?: return
        val cleanedUrl = session.result?.cleanedUrl ?: return
        val installed = rules.findInstalledRule(session.ruleKey) ?: return
        val preview = overlayPreview.takeIf { overlayPreviewSessionId == sessionId }
        val defaultHost = cleanedUrl.toUri().host.orEmpty()
        val transaction = "overlay_${UUID.randomUUID()}"
        when (
            WeChatShare.shareWebPageMessage(
                context = this,
                url = cleanedUrl,
                title = preview?.title ?: "【${installed.rule.displayName}】网页内容",
                description = preview?.description ?: "来自 $defaultHost 的净化链接",
                thumbnail = preview?.thumbnail,
                destination = destination,
                transaction = transaction,
            )
        ) {
            WeChatShare.Result.REQUEST_SENT -> {
                pendingWeChat = PendingWeChat(sessionId, transaction)
                overlayController.hide(sessionId)
                val timeout = Runnable {
                    val pending = pendingWeChat
                    if (pending?.sessionId == sessionId && pending.transaction == transaction) {
                        finishOverlaySession(sessionId, "微信未返回分享结果，本次悬浮会话已超时结束")
                    }
                }
                weChatTimeout = timeout
                handler.postDelayed(timeout, WECHAT_CALLBACK_TIMEOUT_MS)
                ServiceStatus.update(this, "等待微信分享结果", AutomationStage.SHOW_RESULT)
            }
            WeChatShare.Result.WECHAT_NOT_INSTALLED -> {
                overlayController.updateStatus(sessionId, "未安装微信，无法使用微信分享")
            }
            WeChatShare.Result.REQUEST_REJECTED -> {
                overlayController.updateStatus(sessionId, "微信未接受分享请求，请检查应用签名配置")
            }
        }
    }

    private fun handleWeChatFinished(event: ShareOverlayEvent.WeChatFinished): Boolean {
        val pending = pendingWeChat ?: return false
        val completion = OverlayCompletionPolicy.forWeChatCallback(
            expectedTransaction = pending.transaction,
            receivedTransaction = event.transaction,
            outcome = event.outcome,
        )
        if (completion == OverlayCompletionAction.IGNORE) return false
        weChatTimeout?.let(handler::removeCallbacks)
        weChatTimeout = null
        pendingWeChat = null
        return when (completion) {
            OverlayCompletionAction.COMPLETE -> {
                finishOverlaySession(pending.sessionId, "微信分享已完成")
                true
            }
            OverlayCompletionAction.RESTORE_CANCELLED -> {
                val restored = overlayController.restore(pending.sessionId, "已取消微信分享，可以重新选择")
                if (restored) {
                    ServiceStatus.update(this, "已取消微信分享，净化结果仍在等待确认", AutomationStage.SHOW_RESULT)
                } else {
                    finishOverlaySession(pending.sessionId, "悬浮窗恢复失败，本次会话已结束")
                }
                true
            }
            OverlayCompletionAction.RESTORE_FAILED -> {
                val restored = overlayController.restore(pending.sessionId, "微信分享失败，可以重试")
                if (restored) {
                    ServiceStatus.update(this, "微信分享失败，净化结果仍在等待确认", AutomationStage.SHOW_RESULT)
                } else {
                    finishOverlaySession(pending.sessionId, "悬浮窗恢复失败，本次会话已结束")
                }
                true
            }
            OverlayCompletionAction.IGNORE -> false
        }
    }

    private fun copyOverlayLink(sessionId: String) {
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("净化链接", cleanedUrl))
        overlayController.updateStatus(sessionId, "净化链接已复制")
    }

    private fun openFullResult(sessionId: String) {
        if (pendingResultPageSessionId != null ||
            overlayController.activeSessionId != sessionId ||
            ShareSessionStore.get(sessionId)?.result == null
        ) {
            return
        }
        pendingResultPageSessionId = sessionId
        overlayController.updateStatus(sessionId, "正在打开完整结果页…")
        val launchDelay = if (attachLaunchBridge()) LAUNCH_BRIDGE_SETTLE_MS else 0L
        val timeout = Runnable {
            if (pendingResultPageSessionId != sessionId) return@Runnable
            cancelResultPageLaunch(sessionId)
            overlayController.updateStatus(sessionId, "系统未允许打开完整结果页，请重试")
            ServiceStatus.update(this, "完整结果页未能打开，净化结果仍在悬浮窗中", AutomationStage.SHOW_RESULT)
        }
        resultPageLaunchTimeout = timeout
        handler.postDelayed(timeout, launchDelay + RESULT_PAGE_LAUNCH_TIMEOUT_MS)
        handler.postDelayed({
            if (pendingResultPageSessionId != sessionId) return@postDelayed
            val launchError = runCatching { launchResultPage(sessionId) }.exceptionOrNull()
            if (launchError != null) {
                if (isDebugBuild) Log.w(TAG, "Unable to open full result", launchError)
                cancelResultPageLaunch(sessionId)
                overlayController.updateStatus(sessionId, "完整结果页打开失败，请重试")
                ServiceStatus.update(
                    this,
                    "完整结果页打开失败，净化结果仍在悬浮窗中",
                    AutomationStage.SHOW_RESULT,
                )
            }
        }, launchDelay)
    }

    private fun handleResultPageOpened(sessionId: String): Boolean {
        if (pendingResultPageSessionId != sessionId) return false
        cancelResultPageLaunch(sessionId)
        cancelOverlayWorkers()
        overlayController.remove(sessionId)
        ServiceStatus.update(this, "已打开完整净化结果页", AutomationStage.SHOW_RESULT)
        return true
    }

    private fun launchResultPage(sessionId: String) {
        startActivity(
            Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun cancelResultPageLaunch(sessionId: String? = null) {
        if (sessionId != null && pendingResultPageSessionId != sessionId) return
        resultPageLaunchTimeout?.let(handler::removeCallbacks)
        resultPageLaunchTimeout = null
        pendingResultPageSessionId = null
        removeLaunchBridge()
    }

    private fun cancelSystemShareLaunch(sessionId: String? = null) {
        if (sessionId != null && pendingSystemShareSessionId != sessionId) return
        systemShareLaunchTimeout?.let(handler::removeCallbacks)
        systemShareLaunchTimeout = null
        pendingSystemShareSessionId = null
        removeLaunchBridge()
    }

    private fun finishOverlaySession(sessionId: String, message: String) {
        if (sessionId.isBlank()) return
        if (awaitingPanelSeenSessionId == sessionId) awaitingPanelSeenSessionId = null
        cancelSystemShareLaunch(sessionId)
        cancelResultPageLaunch(sessionId)
        removeClipboardFocusBridge()
        cancelOverlayWorkers()
        overlayController.remove(sessionId)
        scheduledScanSessions.remove(sessionId)
        AutomationRuntime.reset(sessionId)
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, message, AutomationStage.IDLE)
    }

    private fun cancelOverlayWorkers() {
        previewWorker?.interrupt()
        previewWorker = null
        overlayPreview = null
        overlayPreviewSessionId = null
        weChatTimeout?.let(handler::removeCallbacks)
        weChatTimeout = null
        pendingWeChat = null
    }

    private fun cancelTask(sessionId: String, message: String) {
        if (awaitingPanelSeenSessionId == sessionId) awaitingPanelSeenSessionId = null
        cancelSystemShareLaunch(sessionId)
        cancelResultPageLaunch(sessionId)
        removeClipboardFocusBridge()
        scheduledScanSessions.remove(sessionId)
        cancelOverlayWorkers()
        overlayController.remove(sessionId)
        AutomationRuntime.reset(sessionId)
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, message, AutomationStage.IDLE)
    }

    private val isDebugBuild: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private data class PendingWeChat(
        val sessionId: String,
        val transaction: String,
    )

    companion object {
        private const val TAG = "StopTrackingAutomation"
        private const val INITIAL_SCAN_DELAY_MS = 120L
        private const val COPY_SCAN_RETRY_DELAY_MS = 140L
        private const val LAUNCH_BRIDGE_SIZE_PX = 1
        private const val LAUNCH_BRIDGE_ALPHA = 0.01f
        private const val LAUNCH_BRIDGE_SETTLE_MS = 100L
        private const val LAUNCH_BRIDGE_LIFETIME_MS = 1_500L
        private const val CAPTURE_LAUNCH_WATCHDOG_MS = 2_500L
        private const val CLIPBOARD_FOCUS_BRIDGE_SIZE_PX = 1
        private const val CLIPBOARD_FOCUS_BRIDGE_ALPHA = 0.01f
        private const val CLIPBOARD_FOCUS_WATCHDOG_MS = 1_500L
        private const val CLIPBOARD_READ_MAX_ATTEMPTS = 6
        private const val CLIPBOARD_READ_RETRY_MS = 200L
        private const val USER_CONFIRMATION_TIMEOUT_MS = 30_000L
        private const val SYSTEM_SHARE_LAUNCH_TIMEOUT_MS = 2_500L
        private const val RESULT_PAGE_LAUNCH_TIMEOUT_MS = 2_500L
        private const val WECHAT_CALLBACK_TIMEOUT_MS = 120_000L
        private val EVENT_INDEPENDENT_STAGES = setOf(
            AutomationStage.CLICK_ONCE,
            AutomationStage.CAPTURE,
            AutomationStage.EXTRACT,
            AutomationStage.RESOLVE,
            AutomationStage.CLEAN,
        )
    }
}
