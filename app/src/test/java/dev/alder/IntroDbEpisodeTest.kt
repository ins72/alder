package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which IMDb id, season and episode Skip intro asks IntroDB for. */
class IntroDbEpisodeTest {
    private fun video(id: String, season: Int, episode: Int) = Video(id, "", season, episode, null, null, null)

    @Test fun anImdbEpisodeIdIsUsedAsItIs() {
        assertEquals(Triple("tt3581920", 1, 1), introDbEpisode("tt3581920", "tt3581920:1:1", null))
    }

    @Test fun aKitsuEpisodeUnderAnImdbShowTakesTheShowsIdAndItsOwnNumbers() {
        // AIOMetadata's shape for Overlord: an IMDb show whose season 1 episodes have Kitsu ids.
        val videos = listOf(video("tt4869896:0:1", 0, 1), video("kitsu:9965:1", 1, 1), video("kitsu:9965:2", 1, 2))
        assertEquals(Triple("tt4869896", 1, 2), introDbEpisode("tt4869896", "kitsu:9965:2", videos))
    }

    @Test fun aKitsuEpisodeWaitsForTheShowsVideos() {
        assertNull(introDbEpisode("tt4869896", "kitsu:9965:1", null))
        assertNull(introDbEpisode("tt4869896", "kitsu:9965:1", emptyList()))
    }

    @Test fun aShowWithNoImdbIdIsNotAsked() {
        assertNull(introDbEpisode("kitsu:9965", "kitsu:9965:1", listOf(video("kitsu:9965:1", 1, 1))))
    }

    @Test fun anEpisodeWithNoNumberIsNotAsked() {
        assertNull(introDbEpisode("tt4869896", "kitsu:9965:1", listOf(video("kitsu:9965:1", 1, 0))))
        assertNull(introDbEpisode("tt3581920", "tt3581920", null))
    }
}
