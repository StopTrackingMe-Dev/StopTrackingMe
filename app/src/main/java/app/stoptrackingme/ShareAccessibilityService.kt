package app.stoptrackingme

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.stoptrackingme.automation.AccessibilityTree
import app.stoptrackingme.automation.AutomationRuntime
import app.stoptrackingme.automation.AutomationSafety
import app.stoptrackingme.automation.AutomationStage
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.session.ShareSessionStore

class ShareAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rules: RuleRepository
    private val fallbackPanelLatch = HashSet<String>()
    private val scheduledScanSessions = HashSet<String>()
    private var launchBridgeView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = RuleRepository.get(this)
        rules.reload()
        val previous = AutomationRuntime.current()
        if (previous.stage == AutomationStage.SHOW_RESULT && previous.sessionId != null) {
            ServiceStatus.update(this, "服务已重新连接，净化结果仍在等待确认", AutomationStage.SHOW_RESULT)
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

        val stageBeforeEvent = AutomationRuntime.current().stage
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
        AutomationRuntime.reset()
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, "服务已中断，本次自动化已终止", AutomationStage.IDLE)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeLaunchBridge()
        super.onDestroy()
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
        if (oldSessionId != null) ShareSessionStore.clear(oldSessionId)
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
                AutomationRuntime.reset(sessionId)
                ShareSessionStore.clear(sessionId)
                ServiceStatus.update(this, "查找复制链接超时，本次自动化已终止", AutomationStage.IDLE)
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
        if (current.stage == AutomationStage.SHARE_TRIGGERED) {
            AutomationRuntime.transition(sessionId, AutomationStage.FIND_COPY)
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
        ServiceStatus.update(this, "正在执行本次任务唯一一次复制点击", AutomationStage.CLICK_ONCE)
        val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            cancelTask(sessionId, "复制链接节点点击失败，本次任务不会重试点击")
            return
        }
        handler.postDelayed({
            if (!AutomationRuntime.transition(sessionId, AutomationStage.CAPTURE)) return@postDelayed
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

    private fun cancelTask(sessionId: String, message: String) {
        scheduledScanSessions.remove(sessionId)
        AutomationRuntime.reset(sessionId)
        ShareSessionStore.clear(sessionId)
        ServiceStatus.update(this, message, AutomationStage.IDLE)
    }

    private val isDebugBuild: Boolean
        get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        private const val TAG = "StopTrackingAutomation"
        private const val INITIAL_SCAN_DELAY_MS = 120L
        private const val COPY_SCAN_RETRY_DELAY_MS = 140L
        private const val LAUNCH_BRIDGE_SIZE_PX = 1
        private const val LAUNCH_BRIDGE_ALPHA = 0.01f
        private const val LAUNCH_BRIDGE_SETTLE_MS = 100L
        private const val LAUNCH_BRIDGE_LIFETIME_MS = 1_500L
        private const val CAPTURE_LAUNCH_WATCHDOG_MS = 2_500L
        private val EVENT_INDEPENDENT_STAGES = setOf(
            AutomationStage.CLICK_ONCE,
            AutomationStage.CAPTURE,
            AutomationStage.EXTRACT,
            AutomationStage.RESOLVE,
            AutomationStage.CLEAN,
        )
    }
}
