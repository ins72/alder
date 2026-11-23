package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ReviewFixesTest {
    @Test fun addonWithBrokenManifestStaysInTheListWithNothingToShow() {
        val broken = Addon(JSONObject().put("transportUrl", "https://x.example/manifest.json"))
        assertEquals("https://x.example/manifest.json", broken.transportUrl)
        assertTrue(broken.catalogs.isEmpty())
        // Its entry is kept whole, so saving the list keeps it on the account.
        assertEquals("https://x.example/manifest.json", broken.descriptor.getString("transportUrl"))
    }

    @Test fun brokenCatalogLeavesOutOnlyItself() {
        val manifest = JSONObject().put("id", "a").put("catalogs", JSONArray()
            .put(JSONObject().put("type", "movie"))
            .put(JSONObject().put("type", "movie").put("id", "top")))
        val addon = Addon(JSONObject().put("transportUrl", "https://a.example/manifest.json").put("manifest", manifest))
        assertEquals(listOf("top"), addon.catalogs.map { it.id })
    }

    @Test fun titleWithoutIdLeavesOutOnlyItself() {
        val metas = listOf(JSONObject().put("name", "No id"), JSONObject().put("id", "tt1").put("name", "Fine")).metas()
        assertEquals(listOf("tt1"), metas.map { it.id })
    }

    @Test fun readLimitedRefusesAnswersPastTheLimit() {
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readLimited(10).size)
        try {
            ByteArrayInputStream(ByteArray(11)).readLimited(10)
            fail("read past the limit")
        } catch (_: IOException) {}
    }

    @Test fun packedSubtitleCannotOpenPastTheLimit() {
        val big = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(ByteArray(17 * 1024 * 1024)) }
        }.toByteArray()
        try {
            SubtitleFiles.unpack(big)
            fail("opened past the limit")
        } catch (_: IOException) {}
    }
}
