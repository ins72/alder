package dev.alder

import android.content.Context

/**
 * The operator's branding, pulled from remote config (GET /config → settings). [name] is the product name shown
 * across the app instead of a hardcoded one; the other fields are optional lines the operator set. Values are
 * cached in memory and persisted, so every screen reads the same name without waiting on the network. Until the
 * first successful fetch, [name] falls back to the app's own name.
 */
object Branding {
    private const val PREFS = "app"
    private const val KEY_NAME = "productName"
    private const val KEY_TAGLINE = "tagline"
    private const val KEY_HEADLINE = "loginHeadline"
    private const val KEY_SUBTITLE = "loginSubtitle"
    private const val KEY_SUPPORT = "supportEmail"
    private const val KEY_TERMS = "termsUrl"
    private const val KEY_PRIVACY = "privacyUrl"
    private const val KEY_SOURCE = "sourceUrl"
    private const val KEY_SERVER = "serverDownloadUrl"

    @Volatile private var name: String? = null
    @Volatile private var tagline: String? = null
    @Volatile private var headline: String? = null
    @Volatile private var subtitle: String? = null
    @Volatile private var support: String? = null
    @Volatile private var terms: String? = null
    @Volatile private var privacy: String? = null
    @Volatile private var source: String? = null
    @Volatile private var server: String? = null
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call once from an Activity: remembers the app context so code without a Context (Moviebox streams) can brand. */
    fun init(c: Context) { if (app == null) app = c.applicationContext }

    private fun ensure(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(c)
            name = p.getString(KEY_NAME, null)
            tagline = p.getString(KEY_TAGLINE, null)
            headline = p.getString(KEY_HEADLINE, null)
            subtitle = p.getString(KEY_SUBTITLE, null)
            support = p.getString(KEY_SUPPORT, null)
            terms = p.getString(KEY_TERMS, null)
            privacy = p.getString(KEY_PRIVACY, null)
            source = p.getString(KEY_SOURCE, null)
            server = p.getString(KEY_SERVER, null)
            loaded = true
        }
    }

    /** The operator's product name, or [fallback] (the app's own name) when they have not set one. */
    fun name(c: Context, fallback: String = c.getString(R.string.app_name)): String {
        ensure(c)
        return name?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun tagline(c: Context): String { ensure(c); return tagline.orEmpty() }
    fun loginHeadline(c: Context): String { ensure(c); return headline.orEmpty() }
    fun loginSubtitle(c: Context): String { ensure(c); return subtitle.orEmpty() }
    fun supportEmail(c: Context): String { ensure(c); return support.orEmpty() }
    fun termsUrl(c: Context): String? { ensure(c); return terms?.takeIf { it.isNotBlank() } }
    fun privacyUrl(c: Context): String? { ensure(c); return privacy?.takeIf { it.isNotBlank() } }
    fun sourceUrl(c: Context): String? { ensure(c); return source?.takeIf { it.isNotBlank() } }
    fun serverDownloadUrl(c: Context): String? { ensure(c); return server?.takeIf { it.isNotBlank() } }

    /** Records the operator's settings from a config fetch. Blank fields clear back to unset. */
    fun update(c: Context, b: Stremio.Brand) {
        name = b.name.ifBlank { null }
        tagline = b.tagline.ifBlank { null }
        headline = b.loginHeadline.ifBlank { null }
        subtitle = b.loginSubtitle.ifBlank { null }
        support = b.supportEmail.ifBlank { null }
        terms = b.termsUrl?.takeIf { it.isNotBlank() }
        privacy = b.privacyUrl?.takeIf { it.isNotBlank() }
        source = b.sourceUrl?.takeIf { it.isNotBlank() }
        server = b.serverDownloadUrl?.takeIf { it.isNotBlank() }
        loaded = true
        prefs(c).edit()
            .putString(KEY_NAME, b.name)
            .putString(KEY_TAGLINE, b.tagline)
            .putString(KEY_HEADLINE, b.loginHeadline)
            .putString(KEY_SUBTITLE, b.loginSubtitle)
            .putString(KEY_SUPPORT, b.supportEmail)
            .putString(KEY_TERMS, b.termsUrl.orEmpty())
            .putString(KEY_PRIVACY, b.privacyUrl.orEmpty())
            .putString(KEY_SOURCE, b.sourceUrl.orEmpty())
            .putString(KEY_SERVER, b.serverDownloadUrl.orEmpty())
            .apply()
    }

    /** Replaces the hardcoded product words with the operator's name, so strings read "…reach <name>" etc. */
    fun brand(text: String, c: Context): String {
        val name = name(c)
        if (name == "Stremio" || name == "Alder") {
            // Still normalise the other word to the shown name, but avoid a pointless double pass when they match.
            if (name == "Alder") return text.replace("Stremio", name)
            if (name == "Stremio") return text.replace("Alder", name)
        }
        return text.replace("Stremio", name).replace("Alder", name)
    }

    /**
     * The name the built-in source (Moviebox/House) shows and matches under: the operator's brand, or "Alder" until a
     * name is set. Stream titles, add-on manifest names and the `== ` checks that group Moviebox/House streams all use
     * this, so the source is branded without breaking playback (it stays stable between sessions).
     */
    fun sourceName(c: Context? = null): String {
        val ctx = c ?: app ?: return "Alder"
        return name(ctx)
    }
}

/**
 * The built-in source's name (operator brand, or "Alder"): what Moviebox/House title their streams and what the player
 * matches them by. One source of truth so the name and the checks can't drift. Pass a Context when handy; otherwise the
 * app context remembered by [Branding.init] is used. See [Branding.sourceName].
 */
fun internalSourceName(c: Context? = null): String = Branding.sourceName(c)

/** Formats [resId] and swaps the hardcoded product words for the operator's name (see [Branding.brand]). */
fun Context.bt(resId: Int, vararg args: Any): String =
    Branding.brand(if (args.isEmpty()) getString(resId) else getString(resId, *args), this)