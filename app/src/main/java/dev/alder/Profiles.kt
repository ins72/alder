package dev.alder

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * One profile. [mod] is when it last changed on any device (epoch millis) and drives the per-profile merge on sync;
 * [deleted] marks a tombstone so a removal on one device reaches the others (the row is kept, flagged, until every
 * device has folded it in).
 */
data class Profile(
    val id: String, val name: String, val color: Int, val authKey: String, val pin: String? = null,
    val mod: Long = 0L, val deleted: Boolean = false
)

/**
 * The profiles on this TV, kept in app storage. Each one is its own Stremio account, so history, Continue Watching,
 * addons and recommendations (Watchly) all stay per person without anything of our own in the cloud.
 */
object Profiles {
    const val MAX = 5
    val COLORS = intArrayOf(0xFFF3D9B1.toInt(), 0xFFC98E6A.toInt(), 0xFF8FA39A.toInt(), 0xFFA6A2BD.toInt(), 0xFFD3A66B.toInt())

    fun all(context: Context): List<Profile> {
        val prefs = prefs(context)
        migrate(prefs)
        return all(prefs)
    }

    /** The profiles from an already-resolved prefs handle (avoids re-resolving the Context), tombstones excluded. */
    internal fun all(prefs: SharedPreferences): List<Profile> = entries(prefs).filter { !it.deleted }

    /** Every stored row including tombstones (deleted profiles kept so a removal can sync). For the merge, not the UI. */
    internal fun entries(prefs: SharedPreferences): List<Profile> =
        JSONArray(prefs.getString("profiles", "[]")).objects().map {
            Profile(
                it.getString("id"),
                it.optString("name"),
                it.optInt("color").takeIf { c -> c in COLORS } ?: COLORS[0],
                // "credential": profiles saved by a newer build (modular accounts) after a downgrade; reading only "authKey" crashed launch.
                it.optString("authKey").ifEmpty { it.optString("credential") },
                // A stored "null" pin is the string optString once wrote for a JSON null; treat it as no PIN.
                it.optString("pin").takeIf { p -> p.isNotEmpty() && p != "null" },
                it.optLong("mod", 0L),
                it.optBoolean("deleted", false)
            )
        }

    /** Every stored row including tombstones, for the sync merge. */
    internal fun entries(context: Context): List<Profile> = entries(prefs(context))

    /** The profile in use: the one picked last, or the only one there is. */
    fun active(context: Context): Profile? {
        val list = all(context)
        return list.firstOrNull { it.id == prefs(context).getString("active", null) } ?: list.singleOrNull()
    }

    fun setActive(context: Context, id: String) {
        val prefs = prefs(context)
        prefs.edit().putString("active", id).apply()
        ProfileSync.changed(prefs)
    }

    fun add(context: Context, name: String, authKey: String, color: Int = COLORS[0]): Profile {
        val list = all(context)
        val taken = entries(context).map { it.id }.toSet() // tombstones keep their id reserved, so a removed id is not reused
        val number = (1..MAX).first { n -> "$n" !in taken }
        val profile = Profile("$number", name, color, authKey, mod = System.currentTimeMillis())
        save(prefs(context), list + profile)
        setActive(context, profile.id)
        return profile
    }

    fun remove(context: Context, id: String) {
        val prefs = prefs(context)
        // Keep a tombstone (deleted, timestamped) so the removal syncs to the person's other devices; the row stays
        // flagged until every device has folded it in.
        val now = System.currentTimeMillis()
        save(prefs, entries(context).map { if (it.id == id) it.copy(deleted = true, mod = now) else it })
        if (prefs.getString("active", null) == id) prefs.edit().remove("active").apply()
        prefs.edit().remove("shareStreams:$id").remove("signedOut:$id").apply()
        Settings.forget(context, id)
        context.getSharedPreferences("search", Context.MODE_PRIVATE).edit().remove("recent:$id").apply()
    }

    /**
     * Stremio no longer knows this profile's sign-in (signed out on the phone or elsewhere, or the session expired). The
     * profile stays, with its name, PIN and settings, and asks to sign in again instead of being removed.
     */
    fun signedOut(context: Context, id: String) = prefs(context).getBoolean("signedOut:$id", false)

    fun markSignedOut(context: Context, id: String) = prefs(context).edit().putBoolean("signedOut:$id", true).apply()

    /**
     * A household member who is a profile card but whose sign-in this device does not hold yet: the card shows their
     * name/colour and asks them to sign in (which fills the auth key) before it opens. Keyed by a stable id from email.
     */
    fun placeholder(context: Context, id: String, name: String, color: Int): Profile {
        val prefs = prefs(context)
        val list = all(context)
        if (list.any { it.id == id }) return list.first { it.id == id }
        val profile = Profile(id, name, color, "", mod = System.currentTimeMillis())
        save(prefs, list + profile)
        prefs.edit().putBoolean("signedOut:$id", true).apply()
        return profile
    }

    fun hasProfile(context: Context, id: String): Boolean = all(context).any { it.id == id }

    /** A fresh sign-in for an existing profile: its new auth key, everything else kept. */
    fun signIn(context: Context, id: String, authKey: String) {
        val now = System.currentTimeMillis()
        save(prefs(context), all(context).map {
            if (it.id != id) it else it.copy(authKey = authKey, deleted = false, mod = now)
        })
        prefs(context).edit().remove("signedOut:$id").apply()
    }

    /** Whether [id] lends its stream add-ons (`Addon.shareable`) to the other profiles on this TV. */
    fun sharesStreams(context: Context, id: String) = prefs(context).getBoolean("shareStreams:$id", false)

    fun setSharesStreams(context: Context, id: String, on: Boolean) = prefs(context).edit().putBoolean("shareStreams:$id", on).apply()

    /** The other profiles lending their stream add-ons to the active one; watch progress still uses the active account. */
    fun streamLenders(context: Context): List<Profile> {
        val current = active(context) ?: return emptyList()
        return all(context).filter { it.id != current.id && sharesStreams(context, it.id) }
    }

    /** Rename or set the PIN of a profile (clearPin removes it), keeping its place in the list. */
    fun update(context: Context, id: String, name: String? = null, pin: String? = null, clearPin: Boolean = false, color: Int? = null) {
        val now = System.currentTimeMillis()
        save(prefs(context), entries(context).map {
            if (it.id != id) it
            else it.copy(
                name = name ?: it.name, color = color ?: it.color,
                pin = if (clearPin) null else pin ?: it.pin, deleted = false, mod = now
            )
        })
    }

    private fun save(prefs: SharedPreferences, list: List<Profile>) {
        val json = JSONArray(list.map {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                .put("authKey", it.authKey).put("pin", it.pin ?: "").put("mod", it.mod)
            if (it.deleted) o.put("deleted", true)
            o
        })
        prefs.edit().putString("profiles", json.toString()).apply()
        ProfileSync.changed(prefs)
    }

    /** Replace the whole profile list in one write (household materialization adds, renames and prunes together). */
    fun replaceAll(context: Context, list: List<Profile>) = save(prefs(context), list)

    /** Serialize the list into prefs without pushing — the sync owns when to push. */
    internal fun writeRaw(prefs: SharedPreferences, list: List<Profile>) {
        val json = JSONArray(list.map {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                .put("authKey", it.authKey).put("pin", it.pin ?: "").put("mod", it.mod)
            if (it.deleted) o.put("deleted", true)
            o
        })
        prefs.edit().putString("profiles", json.toString()).apply()
    }

    // Before profiles there was one sign-in; it becomes the first profile.
    private fun migrate(prefs: SharedPreferences) {
        // Sharing used to lend one add-on to one profile (2026-10-26); an owner who lent one now shares with everyone.
        prefs.all.keys.filter { it.startsWith("streamSource:") || it.startsWith("streamAddon:") }.takeIf { it.isNotEmpty() }?.let { old ->
            val edit = prefs.edit()
            old.forEach { key ->
                if (key.startsWith("streamSource:")) prefs.getString(key, null)?.let { edit.putBoolean("shareStreams:$it", true) }
                edit.remove(key)
            }
            edit.apply()
        }
        val key = prefs.getString("authKey", null) ?: return
        if (!prefs.contains("profiles")) save(prefs, listOf(Profile("1", "Me", COLORS[0], key)))
        prefs.edit().remove("authKey").putString("active", "1").apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("account", Context.MODE_PRIVATE)
}

/**
 * Carries the profile list and the active selection to the server (under the signed-in account) so they survive a
 * reinstall or follow the person to a new device. Each mutation pushes in the background; [pull] runs at start and
 * adopts the server copy when it is newer than what this device last incorporated. The server's revision is the
 * ordering truth, so skewed device clocks never pick the wrong copy.
 */
object ProfileSync {
    private const val REV = "profilesRev"
    private val pool = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * The account the shared profile list is stored under. It is deliberately NOT the active profile: the list is the
     * person's family, so it must sync to the same account on every device no matter which profile is in use, or devices
     * with different active profiles would write to different accounts and never converge. The first profile with a
     * sign-in (the primary account, usually the owner) is stable across devices once the list has synced.
     */
    private fun activeAuthKey(prefs: SharedPreferences): String? {
        val list = Profiles.all(prefs)
        return list.firstOrNull { it.authKey.isNotEmpty() }?.authKey
    }

    /** After a local edit: fold in the server's newer profiles, then push the merged list. Call off the main thread. */
    fun changed(prefs: SharedPreferences) {
        pool.execute { sync(prefs) }
    }

    /** At start: run the same per-profile merge as [changed]. Call off the main thread. True when the shown list changed. */
    fun pull(context: Context): Boolean {
        val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        if (activeAuthKey(prefs) == null) return false
        val before = signature(Profiles.all(prefs))
        sync(prefs)
        return signature(Profiles.all(prefs)) != before
    }

    /** A comparable snapshot of the visible profiles (tombstones excluded), so a caller can tell if the screen changed. */
    private fun signature(list: List<Profile>): List<String> =
        list.map { "${it.id}|${it.name}|${it.color}|${it.authKey}|${it.pin ?: ""}" }

    /**
     * Read the server's list, merge it with this device's by each profile's [Profile.mod] (newest wins per profile;
     * a newer tombstone deletes), write the result, and push it back when this device had something newer so the merge
     * reaches the person's other devices. The server's revision orders whole-list writes; the per-profile mod orders
     * edits, so concurrent changes on different devices combine instead of overwriting each other.
     */
    private fun sync(prefs: SharedPreferences) {
        val key = activeAuthKey(prefs) ?: return
        val server = Stremio.profilesGet(key) ?: return // offline or unknown: keep the local copy
        val merged = merge(Profiles.entries(prefs), server.list)
        Profiles.writeRaw(prefs, merged)
        // Active selection: keep the current one if still live, else the server's, else the first live profile.
        val liveIds = merged.filter { !it.deleted }.map { it.id }
        val current = prefs.getString("active", null)
        val chosen = when {
            current != null && current in liveIds -> current
            server.active != null && server.active in liveIds -> server.active
            else -> liveIds.firstOrNull()
        }
        prefs.edit().apply {
            if (chosen != null) putString("active", chosen) else remove("active")
            putLong(REV, server.rev)
        }.apply()
        // Push only when this device changed the merged set, so the merge propagates without a needless write loop.
        if (merged != server.list) {
            val rev = Stremio.profilesSet(key, merged, chosen)
            if (rev != null) prefs.edit().putLong(REV, rev).apply()
        }
    }

    /**
     * Combine two profile lists (tombstones included) by id; the row with the higher [Profile.mod] wins each id. On an
     * equal mod (legacy rows saved before mod existed, or two edits in the same millisecond) a live row beats a tombstone,
     * then a stable content key decides — so every device makes the same choice and the lists converge instead of each
     * keeping its own conflicting copy.
     */
    private fun merge(local: List<Profile>, server: List<Profile>): List<Profile> {
        val byId = LinkedHashMap<String, Profile>()
        local.forEach { byId[it.id] = it }
        server.forEach { p ->
            val e = byId[p.id]
            if (e == null || newer(p, e)) byId[p.id] = p
        }
        return byId.values.toList()
    }

    private fun newer(p: Profile, e: Profile): Boolean = when {
        p.mod != e.mod -> p.mod > e.mod
        p.deleted != e.deleted -> !p.deleted // a live row beats a tombstone at the same time
        else -> "${p.authKey}|${p.name}|${p.pin ?: ""}" > "${e.authKey}|${e.name}|${e.pin ?: ""}"
    }

    /**
     * Materialize the household as local profiles: when any member signs in, every OTHER joined member of the shared
     * plan (never the person signed in here) becomes a profile card on this device, so the whole family is switchable
     * here. Members whose sign-in this device does not hold appear as "sign in again" cards (their auth key is filled on
     * first use). Also heals cards whose name came out blank/"null" and prunes cards for people who left the household.
     * Call off the main thread. Returns the number of new profiles added.
     */
    fun materializeHousehold(context: Context, key: String): Int {
        val house = Stremio.household(key) ?: return 0
        if (!house.active) return 0
        // The person signed in here already has their own profile; never card them a second time.
        val selfEmail = runCatching { Stremio.email(key) }.getOrNull()?.takeIf { it.isNotBlank() }?.lowercase()
        // Anyone who already has a real (non-household) profile on this device must not get a second card: the owner is
        // usually signed in as the primary profile, and without this their materialized card is healed back on every
        // sync while someone else is active, so a duplicate never goes away.
        val covered = Profiles.all(context)
            .filter { it.authKey.isNotEmpty() && !it.id.startsWith("h") }
            .mapNotNull { runCatching { Stremio.email(it.authKey) }.getOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }
            .toSet()
        val people = buildList {
            house.owner?.let { add(it) }
            house.members.filter { it.joined }.forEach { add(it) }
        }.filter { it.email.lowercase() != selfEmail && it.email.lowercase() !in covered }
        val validIds = people.map { "h" + it.email.hashCode().toUInt().toString(16) }.toSet()
        val list = Profiles.entries(context).toMutableList() // tombstones included, so a prune can sync as a delete
        val now = System.currentTimeMillis()
        var added = 0
        var dirty = false
        people.forEach { m ->
            val id = "h" + m.email.hashCode().toUInt().toString(16)
            val name = m.name?.takeIf { it.isNotBlank() } ?: m.email.substringBefore('@')
            val color = m.color ?: Profiles.COLORS[Math.floorMod(m.email.hashCode(), Profiles.COLORS.size)]
            val at = list.indexOfFirst { it.id == id }
            if (at < 0) {
                list.add(Profile(id, name, color, "", mod = now))
                added++; dirty = true
            } else {
                val cur = list[at]
                // Heal a placeholder whose name came out blank/"null", or one that was pruned but is back in the
                // household; never touch a real signed-in card.
                if (cur.authKey.isEmpty() && (cur.deleted || cur.name.isBlank() || cur.name == "null")) {
                    list[at] = cur.copy(name = name, color = color, deleted = false, mod = now); dirty = true
                }
            }
        }
        // Tombstone "sign in again" cards for people no longer in the household (left or removed); signed-in cards stay.
        val pi = list.indices.filter { i ->
            val p = list[i]
            p.id.startsWith("h") && p.authKey.isEmpty() && !p.deleted && p.id !in validIds
        }
        if (pi.isNotEmpty()) { pi.forEach { list[it] = list[it].copy(deleted = true, mod = now) }; dirty = true }
        if (dirty) Profiles.replaceAll(context, list)
        return added
    }
}
