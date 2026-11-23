package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WatchNextLogoTest {
    @Test
    fun logoResourceIsReferencedAndResolvesToDrawableUri() {
        assertEquals(R.drawable.logo_transparent, WatchNext.LOGO_RES)
        assertNotEquals(0, WatchNext.LOGO_RES)
        assertEquals(
            "android.resource://dev.alder/drawable/logo_transparent",
            WatchNext.logoUri("dev.alder")
        )
    }
}
