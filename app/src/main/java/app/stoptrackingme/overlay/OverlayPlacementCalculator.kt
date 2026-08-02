package app.stoptrackingme.overlay

import kotlin.math.max

data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun intersectArea(other: IntRect): Long {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return intersectionWidth.toLong() * intersectionHeight.toLong()
    }
}

enum class OverlayForm {
    CARD,
    BUBBLE,
}

data class OverlayPlacement(
    val form: OverlayForm,
    val x: Int,
    val y: Int,
)

object OverlayPlacementCalculator {
    fun calculate(
        safeBounds: IntRect,
        panelTop: Int?,
        avoidBounds: List<IntRect>,
        cardWidth: Int,
        cardHeight: Int,
        bubbleSize: Int,
        margin: Int,
    ): OverlayPlacement {
        require(cardWidth > 0 && cardHeight > 0 && bubbleSize > 0 && margin >= 0)

        val boundedCardWidth = cardWidth.coerceAtMost(safeBounds.width)
        val cardX = safeBounds.left + max(0, (safeBounds.width - boundedCardWidth) / 2)
        val availableBottom = panelTop
            ?.coerceIn(safeBounds.top, safeBounds.bottom)
            ?.minus(margin)
        if (availableBottom != null) {
            val cardY = safeBounds.top + margin
            val cardRect = IntRect(cardX, cardY, cardX + boundedCardWidth, cardY + cardHeight)
            if (cardRect.bottom <= availableBottom &&
                avoidBounds.none { cardRect.intersectArea(it) > 0 }
            ) {
                return OverlayPlacement(OverlayForm.CARD, cardX, cardY)
            }
        }

        val maxX = max(safeBounds.left, safeBounds.right - bubbleSize)
        val maxY = max(safeBounds.top, safeBounds.bottom - bubbleSize)
        val preferredY = (safeBounds.top + margin).coerceAtMost(maxY)
        val middleY = (safeBounds.top + (safeBounds.height - bubbleSize) / 2)
            .coerceIn(safeBounds.top, maxY)
        val candidates = listOf(
            maxX to preferredY,
            safeBounds.left to preferredY,
            maxX to (safeBounds.top + margin).coerceAtMost(maxY),
            safeBounds.left to (safeBounds.top + margin).coerceAtMost(maxY),
            maxX to middleY,
            safeBounds.left to middleY,
        ).distinct()

        val best = candidates.minWithOrNull(
            compareBy<Pair<Int, Int>> { (x, y) ->
                val rect = IntRect(x, y, x + bubbleSize, y + bubbleSize)
                avoidBounds.sumOf { rect.intersectArea(it) }
            }.thenBy { (_, y) -> kotlin.math.abs(y - preferredY) }
                .thenByDescending { (x, _) -> x },
        ) ?: (safeBounds.left to safeBounds.top)

        return OverlayPlacement(OverlayForm.BUBBLE, best.first, best.second)
    }

    fun clamp(
        safeBounds: IntRect,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val maxX = max(safeBounds.left, safeBounds.right - width)
        val maxY = max(safeBounds.top, safeBounds.bottom - height)
        return x.coerceIn(safeBounds.left, maxX) to y.coerceIn(safeBounds.top, maxY)
    }
}
