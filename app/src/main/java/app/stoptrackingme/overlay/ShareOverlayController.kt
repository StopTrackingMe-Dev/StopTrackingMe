package app.stoptrackingme.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import androidx.annotation.DrawableRes
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.stoptrackingme.R
import app.stoptrackingme.rules.CleanResult
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ShareOverlayAction {
    START_COPY,
    SYSTEM_SHARE,
    WECHAT_FRIEND,
    WECHAT_TIMELINE,
    QQ_FRIEND,
    QQ_QZONE,
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
    private var draggedCardPosition: Pair<Int, Int>? = null
    private var userCollapsed = false

    val activeSessionId: String?
        get() = model?.sessionId

    fun showProgress(sessionId: String): Boolean {
        model = OverlayModel(
            sessionId = sessionId,
            title = "正在净化链接",
            status = "正在查找并复制链接…",
            result = null,
            previewReady = false,
            forceExpanded = false,
            awaitingCopyConfirmation = false,
        )
        draggedBubblePosition = null
        draggedCardPosition = null
        userCollapsed = false
        return render()
    }

    fun showCopyConfirmation(sessionId: String, sourceName: String): Boolean {
        model = OverlayModel(
            sessionId = sessionId,
            title = "$sourceName · 等待开始",
            status = "点一下圆形按钮，开始复制并净化链接",
            result = null,
            previewReady = false,
            forceExpanded = false,
            awaitingCopyConfirmation = true,
        )
        draggedBubblePosition = null
        draggedCardPosition = null
        userCollapsed = true
        return render()
    }

    fun beginConfirmedCopy(sessionId: String): Boolean {
        val current = model?.takeIf {
            it.sessionId == sessionId && it.awaitingCopyConfirmation
        } ?: return false
        model = current.copy(
            title = "正在净化链接",
            status = "正在查找“复制链接”…",
            forceExpanded = false,
            awaitingCopyConfirmation = false,
        )
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
        if (model?.sessionId != sessionId) {
            draggedBubblePosition = null
            draggedCardPosition = null
        }
        model = OverlayModel(
            sessionId = sessionId,
            title = if (result.isSuccess) "$sourceName · 已净化" else "$sourceName · 未能净化",
            status = status,
            result = result,
            previewReady = previewReady,
            forceExpanded = !keepCollapsed,
            awaitingCopyConfirmation = false,
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
        val nextSourceBounds = sourceWindowBounds?.takeUnless(Rect::isEmpty)?.toIntRect()
        val nextAvoidBounds = clickableBounds.asSequence()
            .filterNot(Rect::isEmpty)
            .map { it.toIntRect() }
            .distinct()
            .toList()
        val changed = sourceBounds != nextSourceBounds ||
            panelTop != detectedPanelTop ||
            avoidBounds != nextAvoidBounds
        sourceBounds = nextSourceBounds
        panelTop = detectedPanelTop
        avoidBounds = nextAvoidBounds
        if (changed && attachedView != null) render()
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
        draggedCardPosition = null
        userCollapsed = false
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        @Suppress("UNUSED_VARIABLE")
        val ignored = newConfig
        draggedBubblePosition = null
        draggedCardPosition = null
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
            val defaultPosition = if (calculated.form == OverlayForm.CARD) {
                calculated.x to calculated.y
            } else {
                bestEffortCardPosition(safeBounds, cardWidth, cardHeight, margin)
            }
            position = draggedCardPosition?.let { (x, y) ->
                OverlayPlacementCalculator.clamp(safeBounds, x, y, cardWidth, cardHeight)
            } ?: defaultPosition
            view.findViewWithTag<View>(DRAG_HANDLE_TAG)?.let { handle ->
                installCardDrag(handle, safeBounds, cardWidth, cardHeight)
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
        val palette = palette()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(MIN_CARD_WIDTH_DP)
            setPadding(dp(14), dp(10), dp(8), dp(12))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(palette.surface)
                setStroke(dp(1), palette.border)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "链接净化悬浮窗"
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dragHandle = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = DRAG_HANDLE_TAG
            contentDescription = "拖动悬浮窗：${current.title}"
            isClickable = true
            isFocusable = true
            addView(iconView(R.drawable.ic_brand_mark, palette.accent), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(textView(current.title, 15f, palette.foreground, bold = true).apply {
                setPadding(dp(8), 0, dp(4), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, weighted())
        }
        header.addView(dragHandle, weighted())
        header.addView(iconButton(R.drawable.ic_expand_more, "收起", palette.secondary) {
            model = model?.copy(forceExpanded = false)
            userCollapsed = true
            render()
        })
        if (current.result != null) {
            header.addView(iconButton(R.drawable.ic_close, "关闭", palette.secondary) {
                action(current.sessionId, ShareOverlayAction.CLOSE)
            })
        }
        card.addView(header, matchWrap())

        card.addView(textView(current.status, 13f, palette.secondary).apply {
            setPadding(0, dp(2), dp(6), dp(6))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }, matchWrap())

        val result = current.result
        result?.cleanedUrl?.let { cleanedUrl ->
            val urlRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(palette.accentContainer)
                }
                addView(iconView(R.drawable.ic_link, palette.accent), LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(textView(cleanedUrl, 12f, palette.foreground).apply {
                    setPadding(dp(8), 0, 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextIsSelectable(false)
                }, weighted())
            }
            card.addView(urlRow, matchWrap().apply { marginEnd = dp(6) })
        }

        if (result?.isSuccess == true) {
            card.addView(tileRow(
                actionTile(R.drawable.ic_chat, "微信好友", WECHAT_GREEN, current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.WECHAT_FRIEND)
                },
                actionTile(R.drawable.ic_moments, "朋友圈", WECHAT_GREEN, current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.WECHAT_TIMELINE)
                },
                actionTile(R.drawable.ic_person, "QQ 好友", QQ_BLUE, current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.QQ_FRIEND)
                },
                actionTile(R.drawable.ic_star, "QQ 空间", QQ_BLUE, current.previewReady) {
                    action(current.sessionId, ShareOverlayAction.QQ_QZONE)
                },
            ), matchWrap(top = 8))
            card.addView(tileRow(
                actionTile(R.drawable.ic_copy, "复制链接", palette.accent) {
                    action(current.sessionId, ShareOverlayAction.COPY)
                },
                actionTile(R.drawable.ic_share, "更多应用", palette.accent) {
                    action(current.sessionId, ShareOverlayAction.SYSTEM_SHARE)
                },
                actionTile(R.drawable.ic_open_in_new, "完整结果", palette.accent) {
                    action(current.sessionId, ShareOverlayAction.OPEN_FULL_RESULT)
                },
                null,
            ), matchWrap(top = 2))
        } else if (result != null) {
            card.addView(tileRow(
                actionTile(R.drawable.ic_info, "查看详情", palette.accent) {
                    action(current.sessionId, ShareOverlayAction.OPEN_FULL_RESULT)
                },
                null,
                null,
                null,
            ), matchWrap(top = 8))
        }
        return card
    }

    private fun createBubble(current: OverlayModel, size: Int): View = FrameLayout(context).apply {
        val failed = current.result?.isSuccess == false
        val working = current.result == null && !current.awaitingCopyConfirmation
        elevation = dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (failed) Color.rgb(186, 26, 26) else BRAND_TEAL)
            setStroke(dp(2), Color.WHITE)
        }
        contentDescription = when {
            current.awaitingCopyConfirmation -> "点按后复制并净化分享链接"
            current.result == null -> "正在净化链接"
            else -> "打开净化结果"
        }
        isClickable = true
        isFocusable = true
        if (working) {
            val spinnerSize = (size * 0.5f).roundToInt()
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            }, FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER))
        } else {
            val icon = when {
                failed -> R.drawable.ic_warning
                current.result != null -> R.drawable.ic_check_circle
                else -> R.drawable.ic_brand_mark
            }
            val iconSize = (size * 0.5f).roundToInt()
            addView(iconView(icon, Color.WHITE), FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
    }

    private fun installBubbleDrag(view: View, safeBounds: IntRect, size: Int) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnClickListener {
            val active = model ?: return@setOnClickListener
            if (active.awaitingCopyConfirmation) {
                action(active.sessionId, ShareOverlayAction.START_COPY)
            } else {
                model = model?.copy(forceExpanded = true)
                userCollapsed = false
                render()
            }
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

    private fun installCardDrag(
        handle: View,
        safeBounds: IntRect,
        width: Int,
        height: Int,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnClickListener { }
        handle.setOnTouchListener { _, event ->
            val params = attachedParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params?.x ?: draggedCardPosition?.first ?: 0
                    startY = params?.y ?: draggedCardPosition?.second ?: 0
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
                        width,
                        height,
                    )
                    draggedCardPosition = clamped
                    updateAttachedPosition(clamped.first, clamped.second)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) handle.performClick()
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
        val y = (safeBounds.top + margin)
            .coerceAtMost((safeBounds.bottom - height).coerceAtLeast(safeBounds.top))
        return OverlayPlacementCalculator.clamp(safeBounds, x, y, width, height)
    }

    private fun action(sessionId: String, action: ShareOverlayAction) {
        actionListener.onAction(sessionId, action)
    }

    private fun tileRow(vararg tiles: View?): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tiles.forEach { tile ->
            // A bare View fills an AT_MOST spec, so empty slots need an explicit zero height.
            val height = if (tile == null) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            addView(tile ?: View(context), LinearLayout.LayoutParams(0, height, 1f))
        }
    }

    /** Icon in a tinted circle above a short label, matching the in-app share grid. */
    private fun actionTile(
        @DrawableRes icon: Int,
        label: String,
        tint: Int,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        val palette = palette()
        setOnClickListener { if (enabled) onClick() }
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(6), dp(2), dp(6))
        isEnabled = enabled
        isClickable = enabled
        isFocusable = true
        alpha = if (enabled) 1f else 0.4f
        contentDescription = label
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(palette.foreground, 0.12f)),
            null,
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
            },
        )
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(tint, if (isDarkTheme()) 0.24f else 0.14f))
            }
            addView(
                iconView(icon, tint),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER),
            )
        }
        addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(textView(label, 11f, palette.foreground).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun iconButton(
        @DrawableRes icon: Int,
        label: String,
        tint: Int,
        onClick: () -> Unit,
    ): View = FrameLayout(context).apply {
        contentDescription = label
        setOnClickListener { onClick() }
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(tint, 0.2f)),
            null,
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
        )
        addView(iconView(icon, tint), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun iconView(@DrawableRes icon: Int, tint: Int): ImageView =
        ImageView(context).apply {
            setImageDrawable(context.getDrawable(icon)?.mutate()?.apply { setTint(tint) })
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun palette(): OverlayPalette = if (isDarkTheme()) {
        OverlayPalette(
            foreground = Color.rgb(222, 228, 226),
            secondary = Color.rgb(176, 190, 187),
            surface = Color.rgb(26, 33, 32),
            border = Color.rgb(63, 73, 71),
            accent = Color.rgb(138, 213, 203),
            accentContainer = Color.rgb(15, 79, 75),
        )
    } else {
        OverlayPalette(
            foreground = Color.rgb(23, 29, 28),
            secondary = Color.rgb(74, 99, 96),
            surface = Color.WHITE,
            border = Color.rgb(216, 226, 224),
            accent = BRAND_TEAL,
            accentContainer = Color.rgb(226, 245, 241),
        )
    }

    private data class OverlayPalette(
        val foreground: Int,
        val secondary: Int,
        val surface: Int,
        val border: Int,
        val accent: Int,
        val accentContainer: Int,
    )

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
        val awaitingCopyConfirmation: Boolean,
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
        const val DRAG_HANDLE_TAG = "share_overlay_drag_handle"
        val BRAND_TEAL = Color.rgb(21, 91, 87)
        val WECHAT_GREEN = Color.rgb(7, 193, 96)
        val QQ_BLUE = Color.rgb(30, 155, 240)
    }
}
