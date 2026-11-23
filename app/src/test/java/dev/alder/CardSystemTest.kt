package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSystemTest {
    @Test
    fun backdropSubstitutesKeepTheirTitleWhilePostersAndChannelLogosStayClean() {
        assertTrue(cardNeedsTitle(live = false, wide = false, loadedUrl = null, posterUrl = "poster"))
        assertFalse(cardNeedsTitle(live = false, wide = false, loadedUrl = "poster", posterUrl = "poster"))
        assertTrue(cardNeedsTitle(live = false, wide = false, loadedUrl = "backdrop", posterUrl = "poster"))
        assertTrue(cardNeedsTitle(live = false, wide = false, loadedUrl = "backdrop", posterUrl = null))
        assertTrue(cardNeedsTitle(live = false, wide = true, loadedUrl = "backdrop", posterUrl = "poster"))
        assertFalse(cardNeedsTitle(live = true, wide = true, loadedUrl = "logo", posterUrl = "logo"))
        assertTrue(cardNeedsTitle(live = true, wide = true, loadedUrl = null, posterUrl = null))
    }

    @Test
    fun pickCardImageUrlAndMetaRequestsFollowCardType() {
        // Portrait poster prefers posterUrl, falls back to artUrl, requests meta only when both are missing.
        assertEquals("p.jpg", pickCardImageUrl(live = false, wide = false, settled = false, posterUrl = "p.jpg", artUrl = "a.jpg"))
        assertEquals("a.jpg", pickCardImageUrl(live = false, wide = false, settled = false, posterUrl = null, artUrl = "a.jpg"))
        assertNull(pickCardImageUrl(live = false, wide = false, settled = false, posterUrl = null, artUrl = null))
        assertFalse(needsCardMeta(live = false, wide = false, posterUrl = "p.jpg", artUrl = null))
        assertTrue(needsCardMeta(live = false, wide = false, posterUrl = null, artUrl = null))

        // Wide non-live card waits for art when unsettled, then falls back to poster once settled.
        assertNull(pickCardImageUrl(live = false, wide = true, settled = false, posterUrl = "p.jpg", artUrl = null))
        assertEquals("p.jpg", pickCardImageUrl(live = false, wide = true, settled = true, posterUrl = "p.jpg", artUrl = null))
        assertEquals("a.jpg", pickCardImageUrl(live = false, wide = true, settled = false, posterUrl = "p.jpg", artUrl = "a.jpg"))
        assertTrue(needsCardMeta(live = false, wide = true, posterUrl = "p.jpg", artUrl = null))
        assertFalse(needsCardMeta(live = false, wide = true, posterUrl = "p.jpg", artUrl = "a.jpg"))

        // Live card prefers posterUrl immediately without waiting for settled.
        assertEquals("logo.png", pickCardImageUrl(live = true, wide = true, settled = false, posterUrl = "logo.png", artUrl = "bg.jpg"))
        assertEquals("bg.jpg", pickCardImageUrl(live = true, wide = true, settled = false, posterUrl = null, artUrl = "bg.jpg"))
        assertFalse(needsCardMeta(live = true, wide = true, posterUrl = "logo.png", artUrl = null))
    }

    @Test
    fun isLogoImageDetectsTransparencyAndAspectMismatch() {
        // Opaque bitmap matching card aspect ratio fills the card via CENTER_CROP.
        assertFalse(isLogoImage(hasAlpha = false, bitmapWidth = 200, bitmapHeight = 112, cardWidthPx = 200, cardHeightPx = 112))
        assertFalse(isLogoImage(hasAlpha = false, bitmapWidth = 100, bitmapHeight = 150, cardWidthPx = 88, cardHeightPx = 132))

        // Transparent bitmap or mismatched aspect ratio sits centered on glass.
        assertTrue(isLogoImage(hasAlpha = true, bitmapWidth = 200, bitmapHeight = 112, cardWidthPx = 200, cardHeightPx = 112))
        assertTrue(isLogoImage(hasAlpha = false, bitmapWidth = 128, bitmapHeight = 128, cardWidthPx = 200, cardHeightPx = 112))
        assertTrue(isLogoImage(hasAlpha = false, bitmapWidth = 200, bitmapHeight = 100, cardWidthPx = 88, cardHeightPx = 132))
    }

    @Test
    fun railTargetScrollSlidesToStartAndClampsAtRightMargin() {
        val step = 104
        val stripWidth = 10 * step + 96
        val scrollerWidth = 700
        assertEquals(0, railTargetScroll(index = 0, stepPx = step, stripWidthPx = stripWidth, scrollerWidthPx = scrollerWidth))
        assertEquals(312, railTargetScroll(index = 3, stepPx = step, stripWidthPx = stripWidth, scrollerWidthPx = scrollerWidth))
        assertEquals(stripWidth - scrollerWidth, railTargetScroll(index = 9, stepPx = step, stripWidthPx = stripWidth, scrollerWidthPx = scrollerWidth))
        assertEquals(0, railTargetScroll(index = 2, stepPx = step, stripWidthPx = 400, scrollerWidthPx = scrollerWidth))
    }

    @Test
    fun isCardVisibleInRailCullsLeftMarginSliversAndKeepsFocusedCard() {
        val pad = 48
        val width = 900
        // Card whose right edge slid into or past the left margin is hidden unless focused.
        assertFalse(isCardVisibleInRail(isFocused = false, cardLeftPx = 0, cardRightPx = 88, scrollXPx = 50, scrollerWidthPx = width, paddingStartPx = pad, cullLeft = true))
        assertTrue(isCardVisibleInRail(isFocused = true, cardLeftPx = 0, cardRightPx = 88, scrollXPx = 50, scrollerWidthPx = width, paddingStartPx = pad, cullLeft = true))

        // Card inside the viewport is visible; card past the right edge is hidden.
        assertTrue(isCardVisibleInRail(isFocused = false, cardLeftPx = 152, cardRightPx = 240, scrollXPx = 104, scrollerWidthPx = width, paddingStartPx = pad, cullLeft = true))
        assertFalse(isCardVisibleInRail(isFocused = false, cardLeftPx = 1010, cardRightPx = 1098, scrollXPx = 104, scrollerWidthPx = width, paddingStartPx = pad, cullLeft = true))

        // When cullLeft is false (Details overlay), cards remain visible regardless of scroll position.
        assertTrue(isCardVisibleInRail(isFocused = false, cardLeftPx = 0, cardRightPx = 88, scrollXPx = 50, scrollerWidthPx = width, paddingStartPx = pad, cullLeft = false))
    }
}
