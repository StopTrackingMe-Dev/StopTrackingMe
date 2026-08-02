package app.stoptrackingme.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementCalculatorTest {
    @Test
    fun `places card at safe top while remaining above detected share panel`() {
        val placement = OverlayPlacementCalculator.calculate(
            safeBounds = IntRect(0, 80, 1080, 2320),
            panelTop = 1500,
            avoidBounds = listOf(IntRect(0, 1500, 1080, 2320)),
            cardWidth = 900,
            cardHeight = 420,
            bubbleSize = 144,
            margin = 24,
        )

        assertEquals(OverlayForm.CARD, placement.form)
        assertEquals(90, placement.x)
        assertEquals(104, placement.y)
    }

    @Test
    fun `uses bubble when panel geometry is unavailable`() {
        val placement = OverlayPlacementCalculator.calculate(
            safeBounds = IntRect(0, 90, 720, 1500),
            panelTop = null,
            avoidBounds = emptyList(),
            cardWidth = 620,
            cardHeight = 500,
            bubbleSize = 96,
            margin = 16,
        )

        assertEquals(OverlayForm.BUBBLE, placement.form)
        assertEquals(624, placement.x)
        assertEquals(106, placement.y)
    }

    @Test
    fun `bubble candidate avoids known clickable region`() {
        val placement = OverlayPlacementCalculator.calculate(
            safeBounds = IntRect(0, 0, 1000, 1800),
            panelTop = 250,
            avoidBounds = listOf(IntRect(880, 0, 1000, 400)),
            cardWidth = 800,
            cardHeight = 500,
            bubbleSize = 100,
            margin = 20,
        )

        assertEquals(OverlayForm.BUBBLE, placement.form)
        assertEquals(0, placement.x)
        assertEquals(20, placement.y)
    }

    @Test
    fun `clamps dragged bubble inside split screen bounds`() {
        val clamped = OverlayPlacementCalculator.clamp(
            safeBounds = IntRect(300, 50, 900, 900),
            x = 1200,
            y = -200,
            width = 100,
            height = 100,
        )

        assertEquals(800 to 50, clamped)
    }
}
