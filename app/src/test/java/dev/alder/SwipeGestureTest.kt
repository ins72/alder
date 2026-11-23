package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player's touch classification, off-device: which way a drag on the picture goes, and — the phone fix — that a
 * horizontal drag beginning near a screen edge is swallowed (4) so reaching the system's back/home buttons never seeks.
 * A 1080 x 2400 phone with a 28 px edge margin.
 */
class SwipeGestureTest {
    private val w = 1080
    private val h = 2400
    private val edge = 28
    private fun swipe(sx: Float, sy: Float, dx: Float, dy: Float) = swipeGesture(sx, sy, dx, dy, w, h, edge)

    @Test fun horizontalInTheMiddleSeeks() {
        assertEquals(3, swipe(540f, 1200f, 200f, 10f))
        assertEquals(3, swipe(540f, 1200f, -200f, 8f))
    }

    @Test fun horizontalNearTheLeftOrRightEdgeIsSwallowed() {
        assertEquals(4, swipe(10f, 1200f, 200f, 5f))
        assertEquals(4, swipe(1070f, 1200f, -200f, 5f))
    }

    @Test fun horizontalNearTheBottomEdgeIsSwallowed() {
        assertEquals(4, swipe(540f, 2390f, 200f, 5f))
    }

    @Test fun verticalLeftIsBrightnessRightIsVolume() {
        assertEquals(1, swipe(300f, 1200f, 5f, 200f))
        assertEquals(2, swipe(700f, 1200f, 5f, 200f))
    }

    @Test fun diagonalIsUndecided() {
        assertEquals(0, swipe(540f, 1200f, 100f, 100f))
    }

    @Test fun justInsideTheEdgeStillSeeks() {
        // Past the 28 px margin the gesture is the player's again.
        assertEquals(3, swipe(40f, 1200f, 200f, 5f))
    }
}