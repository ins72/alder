package dev.alder

import android.content.SharedPreferences
import androidx.media3.common.PlaybackException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class FailuresTest {
    @Test fun failedOrPendingSearchCanNeverReportNoMatches() {
        assertNull(searchFailure(emptyList(), completed = 2, total = 2))
        assertEquals(Failure.TIMEOUT, searchFailure(emptyList(), completed = 1, total = 2))
        assertEquals(Failure.ACCESS, searchFailure(listOf(Failure.ACCESS), completed = 2, total = 2))
    }

    @Test fun classifyKnownFailuresWithoutUsingExceptionText() {
        assertEquals(Failure.SIGN_IN, failureOf(Stremio.ApiException(Stremio.NO_SESSION, "secret")))
        assertEquals(Failure.TIMEOUT, failureOf(SocketTimeoutException("secret")))
        assertEquals(Failure.BUSY, failureOf(HttpError(429)))
        assertEquals(Failure.WEB_PAGE, failureOf(WebPageAnswer())) // a closed add-on's host page, not "invalid response"
        assertEquals(Failure.FORMAT, playbackFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals("error", failureReason(IllegalStateException("https://example.org/key")))
    }

    @Test fun everyFailureSaysWhatToDo() {
        val advice = Failure.entries.map { it.advice }
        assertEquals(Failure.entries.size, advice.toSet().size) // each kind has its own line
        assertTrue(Failure.entries.all { it.advice != it.message })
    }

    @Test fun offlineProgressPreservesLibraryFieldsAndResumesFromTheLocalCheckpoint() {
        val remote = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt1","timeOffset":4000,"duration":100000,"timeWatched":4000,"overallTimeWatched":5000,"watched":"existing"}}""")
        val checkpoint = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:01:00.000Z","removed":true,
            "alderProvisional":true,"state":{"video_id":"tt1","timeOffset":12000,"duration":100000,"timeWatched":8000,"overallTimeWatched":8000}}""")
        assertTrue(newerProgress(checkpoint, remote))
        assertFalse(newerProgress(remote, checkpoint))
        val merged = applyProgress(checkpoint, remote)
        assertTrue(Library.inList(merged))
        assertEquals(12_000L, Library.resumeAt(merged, "tt1"))
        assertEquals(13_000L, merged.state.getLong("overallTimeWatched"))
        assertEquals("existing", merged.state.getString("watched"))
        assertFalse(merged.has("alderProvisional"))
        assertEquals(4000L, Library.resumeAt(remote, "tt1"))
    }

    @Test fun offlineProgressStaysProvisionalUntilRemoteLibraryIsAvailable() {
        val checkpoint = Library.newItem("tt1", "movie", "Movie", null).apply {
            Library.record(this, "tt1", 12_000L, 100_000L, 12_000L)
            put("_mtime", "2026-11-13T10:01:00.000Z")
            put("alderProvisional", true)
        }

        val reloaded = applyProgress(checkpoint, null)
        assertTrue(reloaded.optBoolean("alderProvisional"))

        Library.record(reloaded, "tt1", 25_000L, 100_000L, 13_000L)
        reloaded.put("_mtime", "2026-11-13T10:02:00.000Z")

        val remote = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt1","timeOffset":4000,"duration":100000,"timeWatched":4000,"overallTimeWatched":5000,"watched":"existing"}}""")
        val merged = applyProgress(reloaded, remote)
        assertTrue(Library.inList(merged))
        assertEquals(25_000L, Library.resumeAt(merged, "tt1"))
        assertEquals("existing", merged.state.getString("watched"))
        assertFalse(merged.has("alderProvisional"))
    }

    @Test fun offlineEpisodeCompletionMergesWatchedBitfieldAndTimesWatched() {
        val videos = listOf(
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "E3", 1, 3, null, null, "2026-01-15"),
        )
        val remote = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.record(this, "tt2:1:1", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videos, "tt2:1:1")
            Library.stopped(this, "tt2:1:2")
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val checkpoint = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videos, "tt2:1:2")
            Library.stopped(this, "tt2:1:3")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertTrue(Library.inList(merged))
        assertEquals("tt2:1:3", Library.savedVideoId(merged))
        assertTrue(Library.nextEpisodePending(merged))
        assertEquals(setOf("tt2:1:1", "tt2:1:2"), Library.watched(merged, videos))
        assertEquals(2, merged.state.optInt("timesWatched"))
        assertEquals(0, merged.state.optInt("flaggedWatched"))
        assertFalse(merged.has("alderProvisional"))
    }

    @Test fun offlineEpisodeCompletionPreservesAllCloudWatchedMarks() {
        val videos = listOf(
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "E3", 1, 3, null, null, "2026-01-15"),
        )
        val remote = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.markEpisodesWatched(this, videos, listOf("tt2:1:1", "tt2:1:3"))
            state.put("timesWatched", 2).put("video_id", "tt2:1:3").put("flaggedWatched", 1)
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val checkpoint = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videos, "tt2:1:2")
            Library.stopped(this, "tt2:1:3")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(merged, videos))
        assertEquals(3, merged.state.optInt("timesWatched"))
    }

    @Test fun offlineEpisodeCompletionMergesAcrossCatalogOffsetWithSpecials() {
        val videosWithoutSpecial = listOf(
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "E3", 1, 3, null, null, "2026-01-15"),
        )
        val videosWithSpecial = listOf(
            Video("tt2:0:1", "Special", 0, 1, null, null, "2025-12-25"),
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "E3", 1, 3, null, null, "2026-01-15"),
        )
        val remote = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.markEpisodesWatched(this, videosWithoutSpecial, listOf("tt2:1:1", "tt2:1:3"))
            state.put("timesWatched", 2).put("video_id", "tt2:1:3")
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val checkpoint = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videosWithSpecial, "tt2:1:2")
            Library.stopped(this, "tt2:1:3")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(merged, videosWithSpecial))
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(merged, videosWithoutSpecial))
        assertEquals(3, merged.state.optInt("timesWatched"))

        // Reverse offset: cloud created with S0E1, offline created without S0E1.
        val remoteWithSpecial = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.markEpisodesWatched(this, videosWithSpecial, listOf("tt2:1:1", "tt2:1:3"))
            state.put("timesWatched", 2).put("video_id", "tt2:1:3")
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val checkpointWithoutSpecial = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videosWithoutSpecial, "tt2:1:2")
            Library.stopped(this, "tt2:1:3")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }
        val mergedReverse = applyProgress(checkpointWithoutSpecial, remoteWithSpecial)
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(mergedReverse, videosWithSpecial))
        assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(mergedReverse, videosWithoutSpecial))
        assertEquals(3, mergedReverse.state.optInt("timesWatched"))
    }

    @Test fun offlineCrossSeasonEpisodeCompletionPreservesPriorSeasonWatchedMarks() {
        val videos = listOf(
            Video("tt2:1:1", "S1E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "S1E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "S1E3", 1, 3, null, null, "2026-01-15"),
            Video("tt2:2:1", "S2E1", 2, 1, null, null, "2026-02-01"),
            Video("tt2:2:2", "S2E2", 2, 2, null, null, "2026-02-08"),
        )
        val remote = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.markEpisodesWatched(this, videos, listOf("tt2:1:1", "tt2:1:3"))
            state.put("timesWatched", 2).put("video_id", "tt2:1:3")
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val checkpoint = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:2:1", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.markWatched(this, videos, "tt2:2:1")
            Library.stopped(this, "tt2:2:2")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertEquals(setOf("tt2:1:1", "tt2:1:3", "tt2:2:1"), Library.watched(merged, videos))
        assertEquals(3, merged.state.optInt("timesWatched"))
    }

    @Test fun partialOfflineWatchDoesNotClearRemoteWatchedOrInflateTimesWatched() {
        val videos = listOf(
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
        )
        val remote = Library.newItem("tt2", "series", "Show", null).apply {
            Library.setInList(this, true)
            Library.markWatched(this, videos, "tt2:1:1")
            state.put("video_id", "tt2:1:1").put("timeOffset", 50_000L).put("duration", 1_800_000L)
                .put("timeWatched", 50_000L).put("overallTimeWatched", 50_000L)
                .put("timesWatched", 1).put("flaggedWatched", 0)
            put("_mtime", "2026-11-13T10:00:00.000Z")
        }
        val remoteWatched = remote.state.text("watched")!!
        val partial = Library.newItem("tt2", "series", "Show", null).apply {
            Library.record(this, "tt2:1:2", 120_000L, 1_800_000L, 120_000L)
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val mergedPartial = applyProgress(partial, remote)
        assertEquals("tt2:1:2", Library.savedVideoId(mergedPartial))
        assertEquals(120_000L, Library.resumeAt(mergedPartial, "tt2:1:2"))
        assertEquals(remoteWatched, mergedPartial.state.text("watched"))
        assertEquals(1, mergedPartial.state.optInt("timesWatched"))

        // If a provisional item already carries remote's unchanged watched bitfield without completing offline, don't double-count.
        val copiedWatched = JSONObject(partial.toString()).apply { state.put("watched", remoteWatched) }
        val mergedCopied = applyProgress(copiedWatched, remote)
        assertEquals(1, mergedCopied.state.optInt("timesWatched"))

        // Older provisional checkpoint must not overwrite newer remote progress.
        val stale = JSONObject(partial.toString()).apply { put("_mtime", "2026-11-13T09:59:00.000Z") }
        val keptRemote = applyProgress(stale, remote)
        assertEquals("tt2:1:1", Library.savedVideoId(keptRemote))
        assertEquals(50_000L, Library.resumeAt(keptRemote, "tt2:1:1"))
    }

    @Test fun offlineEpisodeCompletionWithoutCachedVideosStillCountsWatchAndDoesNotMutateRemote() {
        val remote = JSONObject("""{"_id":"tt3","type":"series","_mtime":"2026-11-13T10:00:00.000Z","removed":false}""")
        val checkpoint = Library.newItem("tt3", "series", "Show", null).apply {
            Library.record(this, "tt3:1:1", 1_650_000L, 1_800_000L, 1_650_000L)
            Library.stopped(this, "tt3:1:2")
            put("_mtime", "2026-11-13T10:05:00.000Z")
            put("alderProvisional", true)
        }

        val merged = applyProgress(checkpoint, remote)
        assertFalse(remote.has("state"))
        assertEquals("tt3:1:2", Library.savedVideoId(merged))
        assertTrue(Library.nextEpisodePending(merged))
        assertEquals(1, merged.state.optInt("timesWatched"))
        assertEquals(0, merged.state.optInt("flaggedWatched"))
        assertFalse(merged.has("alderProvisional"))
    }

    @Test fun consecutiveOfflineEpisodeCompletionsAccumulateWithoutCloudCache() {
        val videos = listOf(
            Video("tt2:1:1", "E1", 1, 1, null, null, "2026-01-01"),
            Video("tt2:1:2", "E2", 1, 2, null, null, "2026-01-08"),
            Video("tt2:1:3", "E3", 1, 3, null, null, "2026-01-15"),
            Video("tt2:1:4", "E4", 1, 4, null, null, "2026-01-22"),
        )
        val prefs = InMemoryPrefs()
        ProgressSaves.init(prefs)
        try {
            val ep2 = Library.newItem("tt2", "series", "Show", null).apply {
                Library.record(this, "tt2:1:2", 1_650_000L, 1_800_000L, 1_650_000L)
                Library.markWatched(this, videos, "tt2:1:2")
                Library.stopped(this, "tt2:1:3")
                put("_mtime", "2026-11-13T10:02:00.000Z")
            }
            ProgressSaves.keep("auth", ep2, provisional = true)

            val ep3 = Library.newItem("tt2", "series", "Show", null).apply {
                Library.record(this, "tt2:1:3", 1_650_000L, 1_800_000L, 1_650_000L)
                Library.markWatched(this, videos, "tt2:1:3")
                Library.stopped(this, "tt2:1:4")
                put("_mtime", "2026-11-13T10:05:00.000Z")
            }
            ProgressSaves.keep("auth", ep3, provisional = true)

            val pending = ProgressSaves.pending("auth").values.single().let(::JSONObject)
            assertEquals(setOf("tt2:1:2", "tt2:1:3"), Library.watched(pending, videos))

            val remote = Library.newItem("tt2", "series", "Show", null).apply {
                Library.setInList(this, true)
                Library.markWatched(this, videos, "tt2:1:1")
                state.put("timesWatched", 1).put("video_id", "tt2:1:2").put("timeOffset", 1L)
                put("_mtime", "2026-11-13T10:00:00.000Z")
            }
            val merged = applyProgress(pending, remote)
            assertTrue(Library.inList(merged))
            assertEquals(setOf("tt2:1:1", "tt2:1:2", "tt2:1:3"), Library.watched(merged, videos))
            assertEquals(3, merged.state.optInt("timesWatched"))
            assertEquals("tt2:1:4", Library.savedVideoId(merged))
            assertTrue(Library.nextEpisodePending(merged))
            assertFalse(merged.has("alderProvisional"))

            // A subsequent partial offline watch without new marks keeps the prior offline watched marks intact.
            val ep4Partial = Library.newItem("tt2", "series", "Show", null).apply {
                Library.record(this, "tt2:1:4", 120_000L, 1_800_000L, 120_000L)
                put("_mtime", "2026-11-13T10:08:00.000Z")
            }
            ProgressSaves.keep("auth", ep4Partial, provisional = true)
            val pendingAfterPartial = ProgressSaves.pending("auth").values.single().let(::JSONObject)
            assertEquals(setOf("tt2:1:2", "tt2:1:3"), Library.watched(pendingAfterPartial, videos))
        } finally {
            ProgressSaves.init(null as SharedPreferences?)
        }
    }

    private class InMemoryPrefs : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key in data
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var cleared = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) updates[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) updates[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) updates[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) updates[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) updates[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) updates[key] = value }
            override fun remove(key: String?) = apply { if (key != null) updates[key] = null }
            override fun clear() = apply { cleared = true; updates.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (cleared) data.clear()
                updates.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }
}
