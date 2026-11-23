package dev.alder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest

/** Last unsynced checkpoint per title, scoped to the same Stremio sign-in as the library cache. */
internal object ProgressSaves {
    private var prefs: SharedPreferences? = null
    private val syncFailures = mutableMapOf<String, Failure>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("pending_progress", Context.MODE_PRIVATE)
    }

    internal fun init(store: SharedPreferences?) {
        prefs = store
    }

    private fun prefix(authKey: String) = MessageDigest.getInstance("SHA-256").digest(authKey.toByteArray())
        .joinToString("") { "%02x".format(it) } + "/"

    @Synchronized
    fun keep(authKey: String, item: JSONObject, provisional: Boolean) {
        val store = prefs ?: return
        val key = prefix(authKey) + item.getString("_id")
        val old = store.getString(key, null)?.let(::JSONObject)
        if (old == null || item.optString("_mtime") >= old.optString("_mtime")) {
            val saved = JSONObject(item.toString())
            if (provisional) saved.put("alderProvisional", true)
            if (provisional || saved.optBoolean("alderProvisional")) {
                val (mergedWatched, _) = Library.mergeWatchedBitfields(
                    old?.optJSONObject("state")?.text("watched"),
                    saved.optJSONObject("state")?.text("watched"),
                )
                if (mergedWatched != null) saved.state.put("watched", mergedWatched)
            }
            store.edit().putString(key, saved.toString()).apply()
        }
    }

    @Synchronized
    fun pending(authKey: String): Map<String, String> {
        val prefix = prefix(authKey)
        return prefs?.all.orEmpty().filterKeys { it.startsWith(prefix) }.mapValues { it.value as String }
    }

    @Synchronized
    fun complete(sent: Map<String, String>) {
        val store = prefs ?: return
        val edit = store.edit()
        sent.forEach { (key, value) -> if (store.getString(key, null) == value) edit.remove(key) }
        edit.apply()
    }

    @Synchronized
    fun forget(authKey: String) {
        complete(pending(authKey))
        syncFailures.remove(authKey)
    }

    @Synchronized
    fun syncFailure(authKey: String, failure: Failure?): Failure? =
        if (failure == null) syncFailures.remove(authKey) else syncFailures.put(authKey, failure)
}

internal fun newerProgress(local: JSONObject, remote: JSONObject?) =
    remote == null || local.optString("_mtime") > remote.optString("_mtime")

/** If playback started without a library item, apply its progress to the real item when it becomes available. */
internal fun applyProgress(checkpoint: JSONObject, remote: JSONObject?): JSONObject {
    val local = JSONObject(checkpoint.toString())
    if (local.optBoolean("alderProvisional") != true) return local
    if (remote == null) return local
    local.remove("alderProvisional")
    if (local.optString("_mtime") <= remote.optString("_mtime")) return JSONObject(remote.toString())
    val merged = JSONObject(remote.toString())
    merged.put("_mtime", local.optString("_mtime"))
    val progress = local.optJSONObject("state") ?: return merged
    val sameVideo = Library.savedVideoId(local) == Library.savedVideoId(merged)
    val state = merged.state
    val alreadyWatched = sameVideo && state.optInt("flaggedWatched") != 0
    val flaggedWatched = progress.optInt("flaggedWatched")
    val (mergedWatched, addedWatched) = Library.mergeWatchedBitfields(state.text("watched"), progress.text("watched"))
    val completedOffline = flaggedWatched != 0 || progress.optInt("timesWatched") > 0 || addedWatched > 0
    val played = progress.optLong("overallTimeWatched")
    for (field in listOf("video_id", "timeOffset", "duration", "lastWatched")) state.put(field, progress.opt(field))
    state.put("timeWatched", (if (sameVideo) state.optLong("timeWatched") else 0L) + progress.optLong("timeWatched"))
    state.put("overallTimeWatched", state.optLong("overallTimeWatched") + played)
    if (mergedWatched != null) state.put("watched", mergedWatched)
    if (!alreadyWatched) state.put("flaggedWatched", flaggedWatched)
    val addedWatches = when {
        !alreadyWatched && completedOffline -> maxOf(1, progress.optInt("timesWatched"), addedWatched)
        alreadyWatched && addedWatched > 0 -> addedWatched
        else -> 0
    }
    if (addedWatches > 0) state.put("timesWatched", state.optInt("timesWatched") + addedWatches)
    return merged
}
