package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Search redraws its results only when what they show changes, so posters already in place stay. */
class SearchRenderKeyTest {
    private val movies = listOf("movie:tt1:p1:b1:Dune", "movie:tt2:p2:b2:Dune Part Two")

    @Test fun aLateAddonThatAddsNothingRedrawsNothing() =
        assertEquals(searchRenderKey(linkedMapOf("Movies" to movies), "dune"), searchRenderKey(linkedMapOf("Movies" to movies.toList()), "dune"))

    @Test fun aNewTitleOrOrderRedraws() {
        val before = searchRenderKey(linkedMapOf("Movies" to movies), "dune")
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies + "movie:tt3:p3:b3:Dune 1984"), "dune"))
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies.reversed()), "dune"))
        assertNotEquals(before, searchRenderKey(linkedMapOf("Movies" to movies, "Shows" to listOf("series:tt9:p9:b9:Dune Prophecy")), "dune"))
    }

    @Test fun anotherPosterForTheSameTitleRedraws() =
        assertNotEquals(searchRenderKey(linkedMapOf("Movies" to movies), "dune"),
            searchRenderKey(linkedMapOf("Movies" to listOf("movie:tt1:p1-other:b1:Dune", movies[1])), "dune"))

    @Test fun anotherQueryRedrawsTheSameTitles() =
        assertNotEquals(searchRenderKey(linkedMapOf("Movies" to movies), "dune"), searchRenderKey(linkedMapOf("Movies" to movies), "dun"))
}
