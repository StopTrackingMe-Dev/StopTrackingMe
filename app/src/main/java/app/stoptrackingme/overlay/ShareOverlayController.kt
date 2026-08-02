package app.stoptrackingme.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.stoptrackingme.rules.CleanResult
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ShareOverlayAction {
    SYSTEM_SHARE,
    WECHAT_FRIEND,
    WECHAT_TIMELINE,
    COPY,
    OPEN_FULL_RESULT,
    CLOSE,
}

fun interface ShareOverlayActionListener {
    fun onAction(sessionId: String, action: ShareOverlayAction)
}

/** Owns the one short-lived accessibility overlay for the current automation session. */
class ShareOverlayController(
    private val context: Context,
    private val actionListener: ShareOverlayActionListener,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var attachedView: View? = null
    private var attachedParams: WindowManager.LayoutParams? = null
    private var model: OverlayModel? = null
    private var sourceBounds: IntRect? = null
    private var panelTop: Int? = null
    private var avoidBounds: List<IntRect> = emptyList()
    private var draggedBubblePosition: Pair<Int, Int>? = null
    private var userCollapsed = false

    val activeSessionId: String?
        get() = model?.sessionId

    fun showProgress(sessionId: String): Boolean {
        model = OverlayModel(
            sessionId = sessionId,
            title = "正在净化分享链接",
            status = "正在查找并复制链接…",
            result = null,
            previewReady = false,
            forceExpanded = false,
        )
        draggedBubblePosition = null
        userCollapsed = false
        return render()
    }

    fun showResult(
        sessionId: String,
        result: CleanResult,
        sourceName: String,
        previewReady: Boolean,
        status: String,
    ): Boolean {
        val keepCollapsed = model?.sessionId == sessionId && userCollapsed
        if (model?.sessionId != sessionId) draggedBubblePosition = null
        model = OverlayModel(
            sessionId = sessionId,
            title = if (result.isSuccess) "$sourceName · 净化完成" else "$sourceName · 未能净化链接",
            status = status,
            result = result,
            previewReady = previewReady,
            forceExpanded = false,
        )
        userCollapsed = keepCollapsed
        return render()
    }

    fun updateStatus(sessionId: String, status: String, previewReady: Boolean? = null): Boolean {
        val current = model?.takeIf { it.sessionId == sessionId } ?: return false
        model = current.copy(
            status = status,
            previewReady = previewReady ?: current.previewReady,
        )
        return render()
    }

    fun updateGeometry(
        sourceWindowBounds: Rect?,
        detectedPanelTop: Int?,
        clickableBounds: List<Rect>,
    ) {
        sourceBounds = sourceWindowBounds?.takeUnless(Rect::isEmpty)?.toIntRect()
        panelTop = detectedPanelTop
        avoidBounds = clickableBounds.asSequence()
            .filterNot(Rect::isEmpty)
            .map { it.toIntRect() }
            .distinct()
            .toList()
        if (attachedView != null) render()
    }

    fun hide(sessionId: String): Boolean {
        if (model?.sessionId != sessionId) return false
        detachView()
        return true
    }

    fun restore(sessionId: String, message: String): Boolean {
        val current = model?.takeIf { it.sessionId == sessionId } ?: return false
        model = current.copy(status = message, forceExpanded = true)
        userCollapsed = false
        return render()
    }

    fun remove(sessionId: String? = null) {
        if (sessionId != null && model?.sessionId != sessionId) return
        detachView()
        model = null
        sourceBounds = null
        panelTop = null
        avoidBounds = emptyList()
        draggedBubblePosition = null
        userCollapsed = false
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = newConfig
        draggedBubblePosition = null
        if (attachedView != null) render()
    }

    private fun render(): Boolean {
        val current = model ?: return false
        val safeBounds = resolveSafeBounds()
        if (safeBounds.width <= 0 || safeBounds.height <= 0) return false

        val provisionalCard = createCard(current)
        val maxCardWidth = minOf(dp(MAX_CARD_WIDTH_DP), safeBounds.width - dp(2 * EDGE_MARGIN_DP))
            .coerceAtLeast(dp(MIN_CARD_WIDTH_DP).coerceAtMost(safeBounds.width))
        measureAtMost(provisionalCard, maxCardWidth, safeBounds.height)
        val cardWidth = provisionalCard.measuredWidth
        val cardHeight = provisionalCard.measuredHeight
        val bubbleSize = dp(BUBBLE_SIZE_DP).coerceAtMost(minOf(safeBounds.width, safeBounds.height))
        val margin = dp(EDGE_MARGIN_DP)

        val calculated = OverlayPlacementCalculator.calculate(
            safeBounds = safeBounds,
            panelTop = panelTop,
            avoidBounds = avoidBounds,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            bubbleSize = bubbleSize,
            margin = margin,
        )
        val showCard = !userCollapsed && (current.forceExpanded || calculated.form == OverlayForm.CARD)
        val view: View
        val position: Pair<Int, Int>
        if (showCard) {
            view = provisionalCard
            position = if (calculated.form == OverlayForm.CARD) {
                calculated.x to calculated.y
            } else {
                bestEffortCardPosition(safeBounds, cardWidth, cardHeight, margin)
            }
        } else {
            view = createBubble(current, bubbleSize)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(bubbleSize, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bubbleSize, View.MeasureSpec.EXACTLY),
            )
            position = draggedBubblePosition?.let { (x, y) ->
                OverlayPlacementCalculator.clamp(safeBounds, x, y, bubbleSize, bubbleSize)
            } ?: (calculated.x to calculated.y)
            installBubbleDrag(view, safeBounds, bubbleSize)
        }

        return attachView(view, position.first, position.second)
    }

    private fun createCard(current: OverlayModel): View {
        val foreground = if (isDarkTheme()) Color.WHITE else Color.rgb(28, 28, 30)
        val secondary = if (isDarkTheme()) Color.rgb(205, 205, 210) else Color.rgb(82, 82, 88)
        val surface = if (isDarkTheme()) Color.rgb(42, 42, 46) else Color.WHITE
        val border = if (isDarkTheme()) Color.rgb(95, 95, 102) else Color.rgb(215, 215, 220)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(MIN_CARD_WIDTH_DP)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(surface)
                setStroke(dp(1), border)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "净链分享悬浮窗"
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(textView(current.title, 17f, foreground, bold = true), weighted())
        header.addView(smallButton("收起") {
            model = model?.copy(forceExpanded = false)
            userCollapsed = true
            render()
        })
        card.addView(header, matchWrap())

        card.addView(textView(current.status, 13f, secondary).apply {
            setPadding(0, dp(4), 0, dp(4))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())

        val result = current.result
        result?.cleanedUrl?.let { cleanedUrl ->
            card.addView(textView(cleanedUrl, 12f, foreground).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextIsSelectable(false)
            }, matchWrap())
        }

        if (result?.isSuccess == true) {
            card.addView(actionRow(
                actionButton("微信好友", current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.WECHAT_FRIEND)
                },
                actionButton("朋友圈", current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.WECHAT_TIMELINE)
                },
            ), matchWrap(top = 6))
            card.addView(actionRow(
                actionButton("系统分享") {
                    action(current.sessionId, ShareOverlayAction.SYSTEM_SHARE)
                },
                actionButton("复制链接") {
                    action(current.sessionId, ShareOverlayAction.COPY)
                },
            ), matchWrap(top = 4))
            card.addView(actionRow(
                actionButton("完整结果") {
                    action(current.sessionId, ShareOverlayAction.OPEN_FULL_RESULT)
                },
                actionButton("关闭") {
                    action(current.sessionId, ShareOverlayAction.CLOSE)
                },
            ), matchWrap(top = 4))
        } else if (result != null) {
            card.addView(actionRow(
                actionButton("查看详情") {
                    action(current.sessionId, ShareOverlayAction.OPEN_FULL_RESULT)
                },
                actionButton("关闭") {
                    action(current.sessionId, ShareOverlayAction.CLOSE)
                },
            ), matchWrap(top = 6))
        }
        return card
    }

    private fun createBubble(current: OverlayModel, size: Int): View = FrameLayout(context).apply {
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (current.result?.isSuccess == false) Color.rgb(180, 55, 55) else Color.rgb(32, 105, 215))
            setStroke(dp(2), Color.WHITE)
        }
        contentDescription = if (current.result == null) "正在净化链接" else "打开净化结果"
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply {
            text = if (current.result == null) "…" else "净"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (current.result == null) 22f else 18f)
        }, FrameLayout.LayoutParams(size, size))
    }

    private fun installBubbleDrag(view: View, safeBounds: IntRect, size: Int) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnClickListener {
            model = model?.copy(forceExpanded = true)
            userCollapsed = false
            render()
        }
        view.setOnTouchListener { _, event ->
            val params = attachedParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params?.x ?: draggedBubblePosition?.first ?: 0
                    startY = params?.y ?: draggedBubblePosition?.second ?: 0
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (abs(dx) > dp(DRAG_SLOP_DP) || abs(dy) > dp(DRAG_SLOP_DP)) moved = true
                    val clamped = OverlayPlacementCalculator.clamp(
                        safeBounds,
                        startX + dx,
                        startY + dy,
                        size,
                        size,
                    )
                    draggedBubblePosition = clamped
                    updateAttachedPosition(clamped.first, clamped.second)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        view.performClick()
                    } else {
                        val current = draggedBubblePosition ?: (startX to startY)
                        val center = current.first + size / 2
                        val snappedX = if (center < safeBounds.left + safeBounds.width / 2) {
                            safeBounds.left
                        } else {
                            safeBounds.right - size
                        }
                        draggedBubblePosition = snappedX to current.second
                        updateAttachedPosition(snappedX, current.second)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun attachView(view: View, x: Int, y: Int): Boolean {
        detachView()
        val params = WindowManager.LayoutParams(
            view.measuredWidth,
            view.measuredHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            title = "StopTrackingShareOverlay"
        }
        return try {
            windowManager.addView(view, params)
            attachedView = view
            attachedParams = params
            true
        } catch (_: RuntimeException) {
            attachedView = null
            attachedParams = null
            false
        }
    }

    private fun detachView() {
        val view = attachedView ?: return
        attachedView = null
        attachedParams = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun updateAttachedPosition(x: Int, y: Int) {
        val view = attachedView ?: return
        val params = attachedParams ?: return
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun resolveSafeBounds(): IntRect {
        val displayBounds: IntRect
        val insetLeft: Int
        val insetTop: Int
        val insetRight: Int
        val insetBottom: Int
        val currentMetrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars() or
                        WindowInsets.Type.displayCutout(),
                )
                DisplayGeometry(metrics.bounds.toIntRect(), insets.left, insets.top, insets.right, insets.bottom)
            }.getOrNull()
        } else {
            null
        }
        if (currentMetrics != null) {
            displayBounds = currentMetrics.bounds
            insetLeft = currentMetrics.insetLeft
            insetTop = currentMetrics.insetTop
            insetRight = currentMetrics.insetRight
            insetBottom = currentMetrics.insetBottom
        } else {
            @Suppress("DEPRECATION")
            val realMetrics = android.util.DisplayMetrics().also(
                windowManager.defaultDisplay::getRealMetrics,
            )
            @Suppress("DEPRECATION")
            val usableMetrics = android.util.DisplayMetrics().also(
                windowManager.defaultDisplay::getMetrics,
            )
            displayBounds = IntRect(0, 0, realMetrics.widthPixels, realMetrics.heightPixels)
            val verticalInset = (realMetrics.heightPixels - usableMetrics.heightPixels).coerceAtLeast(0)
            val horizontalInset = (realMetrics.widthPixels - usableMetrics.widthPixels).coerceAtLeast(0)
            insetLeft = 0
            insetTop = minOf(dp(24), verticalInset)
            insetRight = horizontalInset
            insetBottom = (verticalInset - insetTop).coerceAtLeast(0)
        }
        val displaySafe = IntRect(
            displayBounds.left + insetLeft,
            displayBounds.top + insetTop,
            displayBounds.right - insetRight,
            displayBounds.bottom - insetBottom,
        )
        val source = sourceBounds ?: return displaySafe
        return IntRect(
            maxOf(displaySafe.left, source.left),
            maxOf(displaySafe.top, source.top),
            minOf(displaySafe.right, source.right),
            minOf(displaySafe.bottom, source.bottom),
        ).takeIf { it.width > 0 && it.height > 0 } ?: displaySafe
    }

    private fun bestEffortCardPosition(
        safeBounds: IntRect,
        cardWidth: Int,
        cardHeight: Int,
        margin: Int,
    ): Pair<Int, Int> {
        val width = cardWidth.coerceAtMost(safeBounds.width)
        val height = cardHeight.coerceAtMost(safeBounds.height)
        val x = safeBounds.left + (safeBounds.width - width) / 2
        val maxY = (safeBounds.bottom - height).coerceAtLeast(safeBounds.top)
        val candidates = listOfNotNull(
            (safeBounds.top + margin).coerceAtMost(maxY),
            panelTop?.minus(height + margin)?.coerceIn(safeBounds.top, maxY),
            maxY - margin.coerceAtMost(maxY - safeBounds.top),
        ).distinct()
        val y = candidates.minByOrNull { candidateY ->
            val rect = IntRect(x, candidateY, x + width, candidateY + height)
            avoidBounds.sumOf { rect.intersectArea(it) }
        } ?: safeBounds.top
        return x to y
    }

    private fun action(sessionId: String, action: ShareOverlayAction) {
        actionListener.onAction(sessionId, action)
    }

    private fun actionRow(vararg buttons: Button): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
    }

    private fun actionButton(
        label: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isEnabled = enabled
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { onClick() }
    }

    private fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun measureAtMost(view: View, maxWidth: Int, maxHeight: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun isDarkTheme(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

    private data class OverlayModel(
        val sessionId: String,
        val title: String,
        val status: String,
        val result: CleanResult?,
        val previewReady: Boolean,
        val forceExpanded: Boolean,
    )

    private data class DisplayGeometry(
        val bounds: IntRect,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int,
    )

    private companion object {
        const val MAX_CARD_WIDTH_DP = 360
        const val MIN_CARD_WIDTH_DP = 240
        const val BUBBLE_SIZE_DP = 52
        const val EDGE_MARGIN_DP = 12
        const val DRAG_SLOP_DP = 6
    }
}
