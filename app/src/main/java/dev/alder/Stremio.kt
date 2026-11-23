package dev.alder

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * A string field that treats a missing value, JSON null and the literal "null" Android's optString returns for a JSON
 * null all as absent. Server fields like a member's name or a profile's PIN are optional; reading them with optString
 * alone turns a JSON null into the string "null", which then looks like a real value (a phantom PIN, a card named
 * "null"). Callers pass the result straight through, so null means "not set".
 */
internal fun JSONObject.optText(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key)
    return if (value.isBlank() || value == "null") null else value
}

/**
 * Thin client for Stremio's own services (request shapes from stremio-core). Every call blocks,
 * so run them off the main thread.
 */
object Stremio {
    private const val API = "http://192.168.1.100:8080/api/"
    private const val LINK = "http://192.168.1.100:8080/api/v2/"
    private const val AUTH = "http://192.168.1.100:8080/auth/session"
    private const val CONFIG = "http://192.168.1.100:8080/config"

    class Link(val code: String, val url: String)
    class ApiException(val code: Int, message: String) : IOException(message)

    /** One subscription plan the account may buy; price ids stay on the server. */
    class Plan(val id: String, val name: String, val priceLabel: String, val seats: Int)
    /** What the paywall should show for this account: whether a plan is required, whether it is entitled, and where to pay. */
    class BillingStatus(val paywallEnabled: Boolean, val active: Boolean, val role: String, val plans: List<Plan>, val webUrl: String?)

    /** Error code for a session that no longer exists (signed out elsewhere). */
    const val NO_SESSION = 1

    private val addonsCache = java.util.concurrent.ConcurrentHashMap<String, List<Addon>>() // per profile's auth key

    /**
     * TV sign-in, the same flow as Stremio's own TV app: show [Link.url] or the code, the person approves
     * it on their phone, and [readLink] then returns the account's auth key. The app never sees a password.
     */
    fun createLink(): Link = result(Http.get(LINK + "create?type=Create"))
        .let { Link(it.getString("code"), it.getString("link")) }

    /** Email and password sign-in, for a phone where the TV's code-and-QR flow has no second device. Throws [ApiException] when refused. */
    fun login(email: String, password: String): String =
        result(post("login", JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false)))
            .getString("authKey")

    /** Email and password sign-up: creates an account on our own service (terms and privacy accepted). Throws [ApiException] when refused. */
    fun register(email: String, password: String): String =
        result(post("register", JSONObject().put("type", "Register").put("email", email).put("password", password)
            .put("gdpr_consent", JSONObject().put("tos", true).put("privacy", true).put("marketing", false).put("from", "tv"))))
            .getString("authKey")

    /** Auth key once the code is approved, null until then. Stremio answers 101 for both "waiting" and "expired". */
    fun readLink(code: String): String? = try {
        result(Http.get(LINK + "read?type=Read&code=" + URLEncoder.encode(code, "UTF-8"))).getString("authKey")
    } catch (e: ApiException) {
        if (e.code == 101) null else throw e
    }

    /**
     * Registers this sign-in with our own server: it verifies the key, records the login and marks the account active,
     * so the account shows up in the operator's People list and in stats. Fire-and-forget: a failure here never blocks
     * playback, and the next launch tries again.
     */
    fun startSession(authKey: String) {
        if (authKey.isBlank()) return
        runCatching { Http.post(AUTH, JSONObject().put("authKey", authKey).toString()) }
            .onFailure { android.util.Log.w("Alder", "startSession failed", it) }
    }

    /**
     * Reports a slice of playback to our server's usage stats (the web player reports the same through /auth/activity,
     * but the native app has no cookie session, so it authenticates with the authKey). Fire-and-forget.
     * start = first slice of this play (counts one play); completed = reached the end; event="error" reports a failure.
     */
    fun reportActivity(
        authKey: String, seconds: Int, type: String, id: String, title: String,
        start: Boolean, completed: Boolean, source: String?, addon: String?,
        event: String? = null, code: String? = null, critical: Boolean = false
    ) {
        if (authKey.isBlank()) return
        val body = JSONObject().put("authKey", authKey)
        if (seconds > 0 || event == null) body.put("seconds", seconds)
        if (type.isNotBlank()) body.put("type", type)
        if (id.isNotBlank()) body.put("id", id)
        if (title.isNotBlank()) body.put("title", title)
        if (start) body.put("start", true)
        if (completed) body.put("completed", true)
        source?.let { body.put("source", it) }
        addon?.let { body.put("addon", it) }
        event?.let { body.put("event", it) }
        code?.let { body.put("code", it) }
        if (critical) body.put("critical", true)
        runCatching { Http.post(API + "statsActivity", body.toString()) }
            .onFailure { android.util.Log.w("Alder", "reportActivity failed", it) }
    }

    /** The addons installed on the account, in the account's order. Kept in memory until [refresh]. */
    fun addons(authKey: String, refresh: Boolean = false): List<Addon> {
        if (!refresh) addonsCache[authKey]?.let { return it }
        val body = addonsBody(authKey)
        val answer = Http.post(API + "addonCollectionGet", body)
        return result(answer).getJSONArray("addons").objects().map(::Addon)
            .also { addonsCache[authKey] = it; Http.keep(API + "addonCollectionGet", body, answer) }
    }

    /** The addon list as it was last time, from disk (null if never fetched), so Home can start before the network answers. */
    fun savedAddons(authKey: String): List<Addon>? = Http.savedAnswer(API + "addonCollectionGet", addonsBody(authKey))
        ?.let { runCatching { result(it).getJSONArray("addons").objects().map(::Addon) }.getOrNull() }
        ?.also { addonsCache.putIfAbsent(authKey, it) } // until the fresh list arrives, details are asked of these

    private fun addonsBody(authKey: String) =
        JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true).toString()

    /** Replaces the account's addon list (and order) with [addons]. */
    fun setAddons(authKey: String, addons: List<Addon>) {
        val body = JSONObject().put("type", "AddonCollectionSet").put("authKey", authKey)
            .put("addons", JSONArray(addons.map { it.descriptor }))
        result(post("addonCollectionSet", body))
        addonsCache[authKey] = addons
    }

    /** Library items (watch history included): the given ids, or every item when [ids] is empty. */
    fun library(authKey: String, ids: List<String> = emptyList()): List<JSONObject> {
        return newest(authKey, ids, readLibrary(authKey, ids))
    }

    private fun readLibrary(authKey: String, ids: List<String>): List<JSONObject> {
        val body = libraryBody(authKey, ids)
        val answer = Http.post(API + "datastoreGet", body)
        return libraryItems(answer).also { if (ids.isEmpty()) Http.keep(API + "datastoreGet", body, answer) }
    }

    // Items saved here whose save may not have reached Stremio yet: the player saves as it closes, and the page behind
    // it asks Stremio at the same moment, so without this it showed the progress from before.
    // Once Stremio took a save, the copy is kept 30 s more for answers already on the way, then Stremio's own copy
    // counts again: before, a TV clock running fast kept the TV's copy over newer progress from the phone.
    private val saving = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val savedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun pendingSave(key: String): JSONObject? =
        saving[key]?.takeIf { savedAt[key]?.let { System.currentTimeMillis() - it < 30_000 } ?: true }

    private fun newest(authKey: String, ids: List<String>, items: List<JSONObject>): List<JSONObject> {
        val pending = ProgressSaves.pending(authKey).values.map(::JSONObject)
        val latest = items.associateByTo(linkedMapOf()) { it.optString("_id") }
        pending.filter { ids.isEmpty() || it.optString("_id") in ids }.forEach { item ->
            val id = item.optString("_id")
            if (newerProgress(item, latest[id])) latest[id] = applyProgress(item, latest[id])
        }
        return latest.map { (id, item) -> pendingSave("$authKey/$id")?.takeIf { newerProgress(it, item) } ?: item }
    }

    /** The whole library as it was last time, from disk, or null. */
    fun savedLibrary(authKey: String): List<JSONObject>? {
        val cached = Http.savedAnswer(API + "datastoreGet", libraryBody(authKey, emptyList()))
            ?.let { runCatching { libraryItems(it) }.getOrNull() }
        return newest(authKey, emptyList(), cached.orEmpty()).takeIf { cached != null || it.isNotEmpty() }
    }

    private fun libraryBody(authKey: String, ids: List<String>) = JSONObject().put("authKey", authKey)
        .put("collection", "libraryItem").put("ids", JSONArray(ids)).put("all", ids.isEmpty()).toString()

    private fun libraryItems(answer: String): List<JSONObject> {
        val json = JSONObject(answer)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        val list = json.getJSONArray("result")
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    @Synchronized
    fun saveLibrary(authKey: String, items: List<JSONObject>) {
        val keys = items.map { "$authKey/${it.optString("_id")}" }
        items.forEachIndexed { i, item -> saving[keys[i]] = item; savedAt.remove(keys[i]) }
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", JSONArray(items))
        try { result(post("datastorePut", body)) }
        catch (error: Throwable) {
            items.forEachIndexed { i, item -> saving.remove(keys[i], item) }
            throw error
        }
        val now = System.currentTimeMillis()
        items.forEachIndexed { i, item -> if (saving[keys[i]] === item) savedAt[keys[i]] = now }
    }

    @Synchronized
    internal fun editLibrary(authKey: String, id: String, fallback: JSONObject, change: (JSONObject) -> Unit): JSONObject {
        val entry = library(authKey, listOf(id)).firstOrNull()?.let { JSONObject(it.toString()) } ?: fallback
        change(entry)
        saveLibrary(authKey, listOf(entry))
        return entry
    }

    @Synchronized
    internal fun syncProgress(authKey: String) {
        val pending = ProgressSaves.pending(authKey)
        if (pending.isEmpty()) return
        val items = pending.values.map(::JSONObject)
        val remote = readLibrary(authKey, items.map { it.getString("_id") }).associateBy { it.getString("_id") }
        val changes = items.mapNotNull { item ->
            val id = item.getString("_id")
            val saved = remote[id]
            if (newerProgress(item, saved) && newerProgress(item, pendingSave("$authKey/$id"))) applyProgress(item, saved) else null
        }
        if (changes.isNotEmpty()) saveLibrary(authKey, changes)
        val body = libraryBody(authKey, emptyList())
        val cached = Http.savedAnswer(API + "datastoreGet", body)?.let { runCatching { libraryItems(it) }.getOrNull() }
        if (cached != null) {
            val latest = cached.associateByTo(linkedMapOf()) { it.getString("_id") }
            (remote.values + changes).forEach { item ->
                val id = item.getString("_id")
                if (newerProgress(item, latest[id])) latest[id] = item
            }
            Http.keep(API + "datastoreGet", body, JSONObject().put("result", JSONArray(latest.values)).toString())
        }
        ProgressSaves.complete(pending)
    }

    /** The account behind a sign-in: each sign-in has its own auth key, so two keys can be the same account. */
    fun userId(authKey: String): String =
        result(post("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))).getString("_id")

    /** The email on this account (used to link a freshly signed-in profile into the owner's household). */
    fun email(authKey: String): String =
        result(post("getUser", JSONObject().put("type", "GetUser").put("authKey", authKey))).optText("email").orEmpty()

    /**
     * The paywall for this account: whether a subscription is required and whether it is entitled. The server links a
     * payment made on the web to the same account this key resolves to, so a subscriber reads active here.
     */
    fun billingStatus(authKey: String): BillingStatus {
        val body = result(post("billingStatus", JSONObject().put("authKey", authKey)))
        val ent = body.optJSONObject("entitlement")
        val plans = body.optJSONArray("plans")?.objects()?.map {
            Plan(it.optString("id"), it.optString("name"), it.optString("priceLabel"), it.optInt("seats"))
        }.orEmpty()
        return BillingStatus(
            paywallEnabled = body.optBoolean("paywallEnabled"),
            active = ent?.optBoolean("active") ?: true,
            role = ent?.optString("role") ?: "free",
            plans = plans,
            webUrl = body.optString("webUrl").ifEmpty { null }
        )
    }

    /**
     * Starts a web payment for this account and returns the address to open (a Stripe Checkout page), or null when a
     * checkout cannot be started (payments not set up, already entitled, etc.). The account is linked to the payment.
     */
    fun billingCheckout(authKey: String, planId: String? = null): String? {
        val body = JSONObject().put("authKey", authKey)
        if (planId != null) body.put("planId", planId)
        return result(post("billingCheckout", body)).optString("url").ifEmpty { null }
    }

    fun logout(authKey: String) {
        addonsCache.remove(authKey)
        post("logout", JSONObject().put("type", "Logout").put("authKey", authKey))
    }

    /** A profile's synced player settings: the flat values and the server's last-modified time (0 when never synced). */
    class AppSettings(val values: Map<String, Any>, val lastModified: Long)

    /**
     * The player settings this profile saved on the server (quality, dub, audio/subtitle language, picture), as a flat
     * map of small values. Empty when the profile never synced, so callers keep their local defaults. Each device pushes
     * its own copy; a profile's settings follow them from device to device. Best-effort: a failure returns an empty snapshot.
     */
    fun appSettings(authKey: String): AppSettings = runCatching {
        val body = JSONObject().put("type", "appSettingsGet").put("authKey", authKey)
        val result = result(post("appSettingsGet", body))
        val settings = result.optJSONObject("settings") ?: JSONObject()
        val values: Map<String, Any> = settings.keys().asSequence().associateWith { key ->
            when (val v = settings.get(key)) {
                is Boolean -> v
                is Number -> v
                is String -> v
                else -> v.toString()
            }
        }
        AppSettings(values, result.optLong("lastModified", 0L))
    }.getOrDefault(AppSettings(emptyMap(), 0L))

    /**
     * Pushes this profile's player settings to the server so they carry to the person's other devices. Returns the
     * server's new last-modified time, or null on failure. Best-effort: a failure never blocks the caller.
     */
    fun saveAppSettings(authKey: String, settings: Map<String, Any>): Long? {
        if (settings.isEmpty()) return null
        return runCatching {
            val body = JSONObject().put("type", "appSettingsSet").put("authKey", authKey).put("settings", JSONObject(settings))
            result(post("appSettingsSet", body)).optLong("lastModified", 0L)
        }.onFailure { android.util.Log.w("Alder", "saveAppSettings failed", it) }.getOrNull()
    }

    /** One person who shares a family plan: their email, display name/colour, role (member/manager), and joined state. */
    class Member(val email: String, val name: String?, val color: Int?, val role: String, val joined: Boolean, val expired: Boolean) {
        val isManager: Boolean get() = role == "manager"
    }

    /**
     * The family-sharing group for this account. An owner sees the member list and seat usage; a member sees who shared
     * the plan with them. Null when the account has no active plan (so the UI hides the family row entirely).
     * [seats]/[used] include the owner, so "1 of 2 seats" counts the person who pays; [inviteeSeats] is the extra
     * members the plan allows.
     */
    class Household(
        val role: String, val active: Boolean, val canManage: Boolean, val canManageBilling: Boolean,
        val seats: Int, val used: Int, val inviteeSeats: Int, val owner: Member?,
        val sharedBy: String?, val members: List<Member>,
        val planId: String? = null, val planName: String? = null
    ) {
        val isOwner: Boolean get() = role == "owner"
        val isMember: Boolean get() = role == "member"
        val seatsLeft: Int get() = inviteeSeats - (members.size)
    }

    /** Best-effort read of this account's family group; null when there is nothing to show. */
    fun household(authKey: String): Household? = runCatching {
        val body = result(post("billingHousehold", JSONObject().put("authKey", authKey)))
        val role = body.optString("role")
        if (role.isEmpty()) return null
        val members = body.optJSONArray("members")?.objects()?.map {
            Member(it.optString("email"), it.optText("name"),
                it.optInt("color", 0).takeIf { c -> it.has("color") && c != 0 },
                it.optString("role").ifEmpty { "member" },
                it.optBoolean("joined"), it.optBoolean("expired"))
        }.orEmpty()
        val ownerObj = body.optJSONObject("owner")
        val owner = ownerObj?.let {
            Member(it.optString("email"), it.optText("name"),
                it.optInt("color", 0).takeIf { c -> it.has("color") && c != 0 }, "owner", true, false)
        }
        val planObj = body.optJSONObject("plan")
        Household(
            role = role, active = body.optBoolean("active"),
            canManage = body.optBoolean("canManage"), canManageBilling = body.optBoolean("canManageBilling"),
            seats = body.optInt("seats"), used = body.optInt("used"),
            inviteeSeats = if (body.has("inviteeSeats")) body.optInt("inviteeSeats") else (body.optInt("seats") - 1).coerceAtLeast(0),
            owner = owner, sharedBy = body.optString("sharedBy").ifEmpty { null }, members = members,
            planId = planObj?.optString("id")?.ifEmpty { null }, planName = planObj?.optString("name")?.ifEmpty { null }
        )
    }.onFailure { android.util.Log.w("Alder", "household failed", it) }.getOrNull()

    /** The outcome of an invite change: ok plus the refreshed invite list, or a reason string (error) on failure. */
    class InviteResult(val ok: Boolean, val error: String, val members: List<Member>)

    private fun parseInvite(body: JSONObject): InviteResult {
        val ok = body.optBoolean("ok")
        val error = if (!ok) body.optString("error") else ""
        val members = body.optJSONArray("invites")?.objects()?.map {
            Member(it.optString("email"), it.optText("name"),
                it.optInt("color", 0).takeIf { c -> it.has("color") && c != 0 },
                it.optString("role").ifEmpty { "member" },
                it.optBoolean("joined") || it.optBoolean("acceptedAt"), it.optBoolean("expired"))
        }.orEmpty()
        return InviteResult(ok, error, members)
    }

    /** Invite someone by email to share this account's plan. Role is "manager" only for an owner, else "member". */
    fun inviteCreate(authKey: String, email: String, role: String? = null, name: String? = null, color: Int? = null): InviteResult {
        val body = JSONObject().put("authKey", authKey).put("email", email)
        if (role != null) body.put("role", role)
        if (!name.isNullOrBlank()) body.put("name", name)
        if (color != null) body.put("color", color)
        return runCatching { parseInvite(result(post("billingInviteCreate", body))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))
    }

    /** Edit an invitee's display name/colour (any manager). Pass nulls to leave a field unchanged. */
    fun inviteUpdate(authKey: String, email: String, name: String? = null, color: Int? = null): InviteResult {
        val body = JSONObject().put("authKey", authKey).put("email", email)
        if (name != null) body.put("name", name)
        if (color != null) body.put("color", color)
        return runCatching { parseInvite(result(post("billingInviteUpdate", body))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))
    }

    /** Remove an invited person from this account's plan (owner or manager). */
    fun inviteRemove(authKey: String, email: String): InviteResult =
        runCatching { parseInvite(result(post("billingInviteRemove", JSONObject().put("authKey", authKey).put("email", email)))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))

    /** Change an invited person's role between member and manager (owner only). */
    fun inviteRole(authKey: String, email: String, role: String): InviteResult =
        runCatching { parseInvite(result(post("billingInviteRole", JSONObject().put("authKey", authKey).put("email", email).put("role", role)))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))

    /** Resend a pending invite (renews expiry, new confirmation link). Any manager. */
    fun inviteResend(authKey: String, email: String): InviteResult =
        runCatching { parseInvite(result(post("billingInviteResend", JSONObject().put("authKey", authKey).put("email", email)))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))

    /** Hand the plan to a joined member; the current owner stays on as manager. Owner only. */
    fun inviteTransfer(authKey: String, email: String): InviteResult =
        runCatching { parseInvite(result(post("billingInviteTransfer", JSONObject().put("authKey", authKey).put("email", email)))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))

    /** Leave a plan shared with this account (drops its own seat from every owner). */
    fun leavePlan(authKey: String): InviteResult =
        runCatching { parseInvite(result(post("billingLeave", JSONObject().put("authKey", authKey)))) }
            .getOrDefault(InviteResult(false, "failed", emptyList()))

    /**
     * The device's profile list (including tombstones) and active selection saved on the signed-in account, so they
     * follow the person to a new device or after a reinstall. Returns null when there is nothing saved yet (a fresh
     * account), so the caller keeps its local profiles. Each profile carries its own [Profile.mod] for the merge.
     */
    class SyncedProfiles(val list: List<Profile>, val active: String?, val rev: Long)

    fun profilesGet(authKey: String): SyncedProfiles? = runCatching {
        val body = result(post("profilesGet", JSONObject().put("authKey", authKey)))
        val arr = body.optJSONArray("list")
        if (arr == null) return null
        val list = arr.objects().map { p ->
            Profile(
                p.optString("id"), p.optString("name"), p.optInt("color"), p.optString("authKey"),
                p.optText("pin"), p.optLong("mod", 0L), p.optBoolean("deleted", false)
            )
        }
        val active = body.optString("active").ifEmpty { null }
        SyncedProfiles(list, active, body.optLong("rev", 0L))
    }.onFailure { android.util.Log.w("Alder", "profilesGet failed", it) }.getOrNull()

    /** Save the device's profile list (tombstones included) + active selection to the signed-in account. Returns the new revision, or null. */
    fun profilesSet(authKey: String, list: List<Profile>, active: String?): Long? = runCatching {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val o = JSONObject().put("id", p.id).put("name", p.name).put("color", p.color)
                .put("authKey", p.authKey).put("pin", p.pin ?: "").put("mod", p.mod)
            if (p.deleted) o.put("deleted", true)
            arr.put(o)
        }
        val body = JSONObject().put("authKey", authKey).put("list", arr)
        if (active != null) body.put("active", active)
        result(post("profilesSet", body)).optLong("rev", 0L)
    }.onFailure { android.util.Log.w("Alder", "profilesSet failed", it) }.getOrNull()

    /** One operator-written prompt from remote config (GET /config): a title, optional body, and an optional button that opens a link. */
    class Announcement(val id: String, val rev: Int, val title: String, val body: String, val button: String, val url: String?)

    /**
     * The operator's branding from remote config (GET /config → settings): the product name shown across the app,
     * plus optional lines the operator set (tagline, sign-in headline/subtitle, support email, terms/privacy links).
     * Empty strings mean "not set"; callers fall back to their defaults.
     */
    class Brand(
        val name: String, val tagline: String, val loginHeadline: String, val loginSubtitle: String,
        val supportEmail: String, val termsUrl: String?, val privacyUrl: String?,
        val sourceUrl: String?, val serverDownloadUrl: String?
    ) {
        /** The name to show, or [fallback] when the operator left it blank. */
        fun or(fallback: String): String = name.ifBlank { fallback }
    }

    /** Remote config bundled: the operator's [Brand] and any live [Announcement]. */
    class Config(val brand: Brand, val announcement: Announcement?)

    /**
     * Remote config from our own account service (GET /config on the host root, Stremio's shape): the operator's
     * branding (product name and optional lines) and announcement, if any is live. Returns the newest prompt by
     * revision, or null when none is showing. The same revision is shown to a person only once (callers persist the
     * dismissed rev); editing the operator's text bumps the revision, which shows it again.
     */
    fun config(): Config {
        val json = JSONObject(Http.get(CONFIG))
        val s = json.optJSONObject("settings")
        val brand = Brand(
            name = s?.optString("productName").orEmpty(),
            tagline = s?.optString("tagline").orEmpty(),
            loginHeadline = s?.optString("loginHeadline").orEmpty(),
            loginSubtitle = s?.optString("loginSubtitle").orEmpty(),
            supportEmail = s?.optString("supportEmail").orEmpty(),
            termsUrl = s?.optString("termsUrl")?.ifEmpty { null },
            privacyUrl = s?.optString("privacyUrl")?.ifEmpty { null },
            sourceUrl = s?.optString("sourceUrl")?.ifEmpty { null },
            serverDownloadUrl = s?.optString("serverDownloadUrl")?.ifEmpty { null }
        )
        return Config(brand, announcementFrom(json))
    }

    /** The operator's product name alone (branding), or null when the fetch fails or it is unset. */
    fun productName(): String? = runCatching { config().brand.name.ifBlank { null } }.getOrNull()

    private fun announcementFrom(json: JSONObject): Announcement? {
        val items = json.optJSONObject("notifications")?.optJSONArray("items") ?: return null
        val now = System.currentTimeMillis()
        return (0 until items.length())
            .map { items.getJSONObject(it) }
            .filter { it.optString("template") == "prompt" }
            .filter { item ->
                val ends = item.optString("endsAt")
                ends.isEmpty() || runCatching { java.time.Instant.parse(ends).toEpochMilli() >= now }.getOrDefault(true)
            }
            .maxWithOrNull(compareBy({ it.optInt("priority") }, { it.optInt("rev") }))
            ?.let { item ->
                val content = item.optJSONObject("content")?.optJSONObject("locales")
                // Pick the device's language, falling back to English, then any first locale the operator wrote.
                val names = content?.names()
                val firstLocale = names?.takeIf { it.length() > 0 }?.optString(0)
                val locale = content?.optJSONObject(java.util.Locale.getDefault().language)
                    ?: content?.optJSONObject("en")
                    ?: firstLocale?.let { content.optJSONObject(it) }
                val firstAction = item.optJSONArray("buttons")?.optJSONObject(0)
                    ?.optJSONArray("actions")?.optJSONObject(0)
                val url = if (firstAction?.optString("type") == "openUrl") firstAction.optString("url").ifEmpty { null } else null
                val okLabel = locale?.optJSONObject("buttons")?.optString("ok")?.ifEmpty { null }
                val btn = okLabel
                Announcement(
                    id = item.optString("id").ifEmpty { "announcement" },
                    rev = item.optInt("rev"),
                    title = locale?.optString("title").orEmpty(),
                    body = locale?.optString("body").orEmpty(),
                    button = btn ?: "Got it",
                    url = url
                )
            }
            ?.takeIf { it.title.isNotBlank() }
    }

    /** Kept for callers that only want the announcement. */
    fun announcement(): Announcement? = runCatching { config().announcement }.getOrNull()

    // Stremio wraps every answer as {"result": …} or {"error": {"code", "message"}}.
    private fun result(body: String): JSONObject {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw ApiException(it.optInt("code"), it.optString("message")) }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun post(method: String, body: JSONObject) = Http.post(API + method, body.toString())
}
