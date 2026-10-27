package com.voiceagent.oneplus13.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionTest {
    @Test fun keyboardMovesPanelOnlyAsFarAsNeeded() {
        val p = fitOverlayPosition(900, 1500, 900, 800, 0, 80, 1440, 1800)
        assertEquals(OverlayPosition(540, 1000), p)
        assertEquals(1800, p.y + 800) // bottom flush with the available frame, no second IME gap
    }
    @Test fun hidingKeyboardRestoresRequestedPosition() {
        val requestedY = 1500
        val open = fitOverlayPosition(100, requestedY, 900, 800, 0, 80, 1440, 1800)
        val closed = fitOverlayPosition(100, requestedY, 900, 800, 0, 80, 1440, 3100)
        assertEquals(1000, open.y)
        assertEquals(requestedY, closed.y)
    }
    @Test fun alreadyVisiblePanelDoesNotJumpWhenKeyboardAppears() {
        assertEquals(OverlayPosition(100, 200), fitOverlayPosition(100, 200, 900, 800, 0, 80, 1440, 1800))
    }
    @Test fun expandingBubbleUsesActualPanelWidth() {
        val bubble = fitOverlayPosition(1250, 500, 150, 150, 0, 80, 1440, 3100)
        val panel = fitOverlayPosition(1250, 500, 900, 800, 0, 80, 1440, 3100)
        assertEquals(1250, bubble.x)
        assertEquals(540, panel.x)
    }
    @Test fun temporarilyOversizedWindowCannotProduceInvalidRange() {
        assertEquals(OverlayPosition(20, 80), fitOverlayPosition(-100, -200, 3000, 4000, 20, 80, 1440, 1800))
    }
    @Test fun draggingPastEdgesRemainsInsideVisibleFrame() {
        assertEquals(OverlayPosition(20, 80), fitOverlayPosition(-100, -200, 500, 700, 20, 80, 1440, 1800))
        assertEquals(OverlayPosition(940, 1100), fitOverlayPosition(9000, 9000, 500, 700, 20, 80, 1440, 1800))
    }
}
