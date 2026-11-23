package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOrderTest {

    private fun stream(title: String, height: Int, vararg langs: String): Stream =
        Stream(
            title = title,
            description = "Movie ${height}p",
            url = "https://example.com/$title.mkv",
            headers = emptyMap(),
            size = 1_000_000_000L,
            filename = "",
            bingeGroup = null,
            taggedLanguages = langs.toSet(),
        )

    @Test
    fun triesEveryWantedLanguageSourceBeforeEnglishAndOthers() {
        val hi1080 = stream("hi1080", 1080, "hi")
        val hi720 = stream("hi720", 720, "hi")
        val en1080 = stream("en1080", 1080, "en")
        val en480 = stream("en480", 480, "en")
        val orig720 = stream("orig720", 720) // untagged = original
        val fr1080 = stream("fr1080", 1080, "fr")
        val list = listOf(en1080, fr1080, hi720, orig720, hi1080, en480)

        val ordered = PlaybackOrder.order(list, wantAudio = "hi", originalFirst = false)

        // Both Hindi first (best quality first), then English + original (all of them), then French.
        assertEquals(listOf(hi1080, hi720, en1080, orig720, en480, fr1080), ordered)
    }

    @Test
    fun fallsBackToEnglishOnlyAfterEveryWantedSource() {
        val hi480 = stream("hi480", 480, "hi")
        val en2160 = stream("en2160", 2160, "en") // higher quality, but a different language
        val ordered = PlaybackOrder.order(listOf(en2160, hi480), wantAudio = "hi", originalFirst = false)
        assertEquals(listOf(hi480, en2160), ordered)
    }

    @Test
    fun originalComesFirstWhenNoLanguageWantedAndDubOriginal() {
        val en1080 = stream("en1080", 1080, "en")
        val orig720 = stream("orig720", 720)
        val fr1080 = stream("fr1080", 1080, "fr")
        val ordered = PlaybackOrder.order(listOf(en1080, fr1080, orig720), wantAudio = "", originalFirst = true)
        assertEquals(listOf(orig720, en1080, fr1080), ordered)
    }

    @Test
    fun englishAndOriginalShareFallbackWhenNoLanguageWanted() {
        val en1080 = stream("en1080", 1080, "en")
        val orig720 = stream("orig720", 720)
        val fr1080 = stream("fr1080", 1080, "fr")
        val ordered = PlaybackOrder.order(listOf(fr1080, orig720, en1080), wantAudio = "", originalFirst = false)
        // English and original are one fallback group, best quality first (1080p), then French.
        assertEquals(listOf(en1080, orig720, fr1080), ordered)
    }

    @Test
    fun wantedLanguageBeatsOriginalEvenWhenOriginalIsHigherQuality() {
        val hi480 = stream("hi480", 480, "hi")
        val orig2160 = stream("orig2160", 2160)
        val ordered = PlaybackOrder.order(listOf(orig2160, hi480), wantAudio = "hi", originalFirst = false)
        assertEquals(listOf(hi480, orig2160), ordered)
    }

    @Test
    fun singleSourceIsReturnedUnchanged() {
        val only = stream("only", 1080, "hi")
        assertEquals(listOf(only), PlaybackOrder.order(listOf(only), wantAudio = "hi", originalFirst = false))
    }
}