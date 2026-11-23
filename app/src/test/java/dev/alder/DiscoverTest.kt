package dev.alder

import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discover's TMDB search: the filters as TMDB takes them, and results as titles under their IMDb ids. */
class DiscoverTest {
    @Test fun largerPostersRemainFullyVisibleWithLessThanTwoRowsOfSpace() {
        assertEquals(442, gridScrollTarget(scrollY = 0, viewportHeight = 696, rowTop = 442, rowHeight = 442))
        assertEquals(188, gridScrollTarget(scrollY = 188, viewportHeight = 696, rowTop = 442, rowHeight = 442))
        assertEquals(0, gridScrollTarget(scrollY = 188, viewportHeight = 696, rowTop = 0, rowHeight = 442))
        assertEquals(884, gridScrollTarget(scrollY = 188, viewportHeight = 696, rowTop = 884, rowHeight = 442))
        assertEquals(442, gridScrollTarget(scrollY = 0, viewportHeight = 640, rowTop = 442, rowHeight = 442))
    }

    @Test fun filterFocusSurvivesAServiceListChange() {
        val withServices = listOf("movie", "series", "anime", "discover_genre", "discover_year", "discover_on", "sort")
        val withoutServices = withServices - "discover_on"
        assertEquals(6, discoverChipIndex(withServices, "sort", "movie"))
        assertEquals(5, discoverChipIndex(withoutServices, "sort", "movie"))
        assertEquals(1, discoverChipIndex(withoutServices, "discover_on", "series"))
        assertEquals(2, discoverChipIndex(withServices, null, "anime"))
        assertEquals(null, discoverChipIndex(emptyList(), "sort", "movie"))
    }

    @Test fun filterStripOnlyUsesRtlFocusWhenAppSupportsRtl() {
        assertFalse(isStripRtl(supportsRtl = false, configLayoutDirection = View.LAYOUT_DIRECTION_RTL))
        assertTrue(isStripRtl(supportsRtl = true, configLayoutDirection = View.LAYOUT_DIRECTION_RTL))
        assertFalse(isStripRtl(supportsRtl = true, configLayoutDirection = View.LAYOUT_DIRECTION_LTR))
    }

    private fun params(q: DiscoverQuery) =
        discoverUrl(q, 2, "CA", "en-CA", "2026-11-18", "k").substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test fun popularMoviesAreTheStart() {
        assertEquals("movie", DiscoverQuery().type)
        val p = params(DiscoverQuery(type = "movie"))
        assertTrue(discoverUrl(DiscoverQuery(type = "movie"), 1, "CA", "en", "2026-11-18", "k").contains("/discover/movie?"))
        assertEquals("popularity.desc", p["sort_by"])
        assertEquals("false", p["include_adult"])
        assertEquals("2", p["page"])
        assertFalse("with_genres" in p)
    }

    @Test fun animeIsJapaneseAnimationShows() {
        val url = discoverUrl(DiscoverQuery(type = "anime", genre = 10759), 1, "CA", "en", "2026-11-18", "k")
        assertTrue(url.contains("/discover/tv?"))
        val p = params(DiscoverQuery(type = "anime", genre = 10759))
        assertEquals("16%2C10759", p["with_genres"])
        assertEquals("ja", p["with_original_language"])
    }

    @Test fun decadeServiceAndNewestUseTheRightDates() {
        val p = params(DiscoverQuery(type = "series", decade = 2010, service = 8, sort = "new"))
        assertEquals("2010-01-01", p["first_air_date.gte"])
        assertEquals("2019-12-31", p["first_air_date.lte"])
        assertEquals("8", p["with_watch_providers"])
        assertEquals("CA", p["watch_region"])
        assertEquals("first_air_date.desc", p["sort_by"])
        // Newest in this decade: nothing later than today, which is already past the decade's end.
        assertEquals("2026-11-18", params(DiscoverQuery(type = "movie", sort = "new"))["primary_release_date.lte"])
        assertEquals("1979-12-31", params(DiscoverQuery(type = "movie", decade = 0))["primary_release_date.lte"])
        assertEquals("300", params(DiscoverQuery(type = "movie", sort = "top"))["vote_count.gte"])
        val exact = params(DiscoverQuery(type = "movie", year = 2024))
        assertEquals("2024-01-01", exact["primary_release_date.gte"])
        assertEquals("2024-12-31", exact["primary_release_date.lte"])
    }

    @Test fun aResultBecomesATitleUnderItsImdbId() {
        val t = JSONObject("""{"id":603,"title":"The Matrix","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
            "overview":"A hacker.","release_date":"1999-03-30","genre_ids":[28,878]}""")
        val m = discoverMeta(t, "tt0133093", movie = true, genreNames = mapOf(28 to "Action", 878 to "Science Fiction"))
        assertEquals("tt0133093", m.id)
        assertEquals("movie", m.type)
        assertEquals("The Matrix", m.name)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", m.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", m.background)
        assertEquals("1999", m.year)
        assertEquals(listOf("Action", "Science Fiction"), m.genres)
    }

    @Test fun myListYearsGoInTheirDecade() {
        assertTrue(inDecade("2019", 2010))
        assertTrue(inDecade("2019–2023", 2010))
        assertFalse(inDecade("2020", 2010))
        assertTrue(inDecade("1975", 0))
        assertFalse(inDecade("1980", 0))
        assertFalse(inDecade(null, 2010))
        assertTrue(inYear("2024", 2024))
        assertTrue(inYear("2024–2026", 2024))
        assertFalse(inYear("2023", 2024))
    }
}
