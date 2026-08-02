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
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSessionStore
import java.util.UUID

class ShareAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rules: RuleRepository
    private val fallbackPanelLatch = HashSet<String>()
    private val scheduledScanSessions = HashSet<String>()
    private var launchBridgeView: View? = null
    private lateinit var overlayController: ShareOverlayController
    private var overlayPreview: WebSharePreview? = null
    private var overlayPreviewSessionId: String? = null
    private var previewWorker: Thread? = null
    private var pendingWeChat: PendingWeChat? = null
    private var weChatTimeout: Runnable? = null
    private val overlayEventListener = ShareOverlayEventListener(::onOverlayEvent)
    private val overlayActionListener = ShareOverlayActionListener(::onOverlayAction)

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = RuleRepository.get(this)
        rules.reload()
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
                    "已离开来源应用，本次悬浮分享会话已结束",
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
        removeLaunchBridge()
        val sessionId = AutomationRuntime.current().sessionId
        cancelOverlayWorkers()
        if (::overlayController.isInitialized) overlayController.remove(sessionId)
        AutomationRuntime.reset()
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, "服务已中断，本次自动化已终止", AutomationStage.IDLE)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeLaunchBridge()
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
        val sessionId = ShareSessionStore.begin(
            ruleKey = installed.key,
            sourcePackage = installed.rule.target.packageName,
        )
        val deadline = System.currentTimeMillis() + installed.rule.sharePanelTimeoutMs
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
        if (oldSessionId != null) {
            cancelOverlayWorkers()
            if (::overlayController.isInitialized) overlayController.remove(oldSessionId)
            ShareSessionStore.clear(oldSessionId)
        }
        if (ResultPresentationPreferences.get(this) ==
            ResultPresentationMode.ACCESSIBILITY_OVERLAY
        ) {
            overlayController.showProgress(sessionId)
        }
        ServiceStatus.update(
            this,
            if (fallback) {
                "已确认完整分享面板指纹，正在查找复制链接"
            } else {
                "已捕获分享点击，正在查找复制链接"
            },
            AutomationStage.SHARE_TRIGGERED,
        )
        scheduleCopyScan(installed, INITIAL_SCAN_DELAY_MS)
        handler.postDelayed({
            val current = AutomationRuntime.current()
            if (current.sessionId == sessionId && AutomationRuntime.isExpired(System.currentTimeMillis())) {
                cancelTask(sessionId, "查找复制链接超时，本次自动化已终止")
            }
        }, installed.rule.sharePanelTimeoutMs + 50L)
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
                scrollTarget.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
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
            ServiceStatus.update(this, "正在前台读取本次复制内容", AutomationStage.CAPTURE)
            launchClipboardCapture(sessionId)
        }, installed.rule.copySettleDelayMs)
    }

    /**
     * EMUI may reject an activity launch from a bound accessibility-service process unless that
     * UID currently owns a visible window. A tiny, non-touchable accessibility overlay provides
     * that launch identity without requesting the broad "display over other apps" permission.
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
            val loaded = runCatching {
                SharePreviewLoader().load(
                    cleanedUrl = cleanedUrl,
                    sourceName = installed.rule.displayName,
                    rule = previewRule,
                    networkPolicy = installed.rule.redirectPolicy,
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
                overlayPreview = loaded.getOrNull()
                val message = when {
                    loaded.isFailure -> "链接已净化；微信将使用默认卡片信息"
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
        val cleanedUrl = ShareSessionStore.get(sessionId)?.result?.cleanedUrl ?: return
        overlayController.hide(sessionId)
        val launchError = runCatching {
            startActivity(
                ShareIntentFactory.createChooser(cleanedUrl)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.exceptionOrNull()
        if (launchError != null) {
            if (isDebugBuild) Log.w(TAG, "Unable to open system share from overlay")
            overlayController.restore(sessionId, "系统分享面板打开失败，请重试")
            return
        }
        finishOverlaySession(sessionId, "已交给系统分享，本次悬浮会话已结束")
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
                if (!restored) fallbackToResultPage(pending.sessionId)
                ServiceStatus.update(this, "已取消微信分享，净化结果仍在等待确认", AutomationStage.SHOW_RESULT)
                true
            }
            OverlayCompletionAction.RESTORE_FAILED -> {
                val restored = overlayController.restore(pending.sessionId, "微信分享失败，可以重试")
                if (!restored) fallbackToResultPage(pending.sessionId)
                ServiceStatus.update(this, "微信分享失败，净化结果仍在等待确认", AutomationStage.SHOW_RESULT)
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
        overlayController.hide(sessionId)
        val launchError = runCatching { launchResultPage(sessionId) }.exceptionOrNull()
        if (launchError != null) {
            if (isDebugBuild) Log.w(TAG, "Unable to open full result", launchError)
            overlayController.restore(sessionId, "完整结果页打开失败")
        } else {
            cancelOverlayWorkers()
            overlayController.remove(sessionId)
        }
    }

    private fun launchResultPage(sessionId: String) {
        startActivity(
            Intent(this, ResultActivity::class.java)
                .putExtra(ResultActivity.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun fallbackToResultPage(sessionId: String) {
        val error = runCatching { launchResultPage(sessionId) }.exceptionOrNull()
        if (error == null) {
            cancelOverlayWorkers()
            overlayController.remove(sessionId)
        } else {
            if (isDebugBuild) Log.w(TAG, "Unable to restore overlay or result page", error)
            finishOverlaySession(sessionId, "无法恢复分享结果，本次会话已结束")
        }
    }

    private fun finishOverlaySession(sessionId: String, message: String) {
        if (sessionId.isBlank()) return
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
