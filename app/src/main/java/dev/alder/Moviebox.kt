package dev.alder

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The Moviebox source, as a built-in add-on: a title's IMDb id is resolved straight from the Moviebox-API host
 * (moviebox.mewayz.com) and its HLS packager (moviebox2.mewayz.com), the same way stremio-web-with-streamgrabber's
 * movieboxResolver does it. A port of that resolver so Alder asks no bridge and no server:
 *
 *   imdb id --Cinemeta--> title + year --/api/search + /api/detail--> Moviebox subject
 *   subject.dubs[]  = one Moviebox subject per dubbed language (each its own single-audio MP4)
 *   /api/streams/{id}                -> which MP4/DASH/HLS sources exist
 *   /api/dash/{token}/manifest.mpd   -> DASH manifest (CDN headers injected server-side)
 *   /api/captions/{id}               -> subtitle list (WebVTT)
 *
 * Playable URLs come straight from moviebox2: HEVC MP4 as on-demand HLS from /hls/<assetId>/master.m3u8, h264 MP4
 * through /media (which injects the CDN User-Agent/Referer/Cookie the browser cannot set), and DASH through the
 * same-origin /api/dash proxy. The player reads them as-is; nothing here needs the network at construction.
 */
object Moviebox {
    private const val BASE = "https://moviebox.mewayz.com"
    private const val BASE2 = "https://moviebox2.mewayz.com"
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private const val TIMEOUT = 15_000
    private const val MAX_DUBS = 16
    private const val MAX_CANDIDATES = 6
    private const val PREFERRED_RES = 1080
    private const val CACHE_MS = 10 * 60 * 1000L
    // Every dub the family lists can become a variant, so no dub is dropped before it reaches the language modal.
    private const val MAX_VARIANTS = 16
    // One stream per (variant, height): 16 variants × up to ~5 heights fits, so the grouped tail isn't truncated.
    private const val MAX_STREAMS = 80

    private val SAFE_ID = Regex("""\d{1,24}""")
    private val IMDB_ID = Regex("""tt\d+""")
    private val SAFE_SLUG = Regex("""[A-Za-z0-9._~-]{1,200}""")
    // "Alls Fair [Hindi]" / "Alls Fair (Hindi)" -> "Alls Fair": the dub marker Moviebox appends to a title.
    private val DUB_SUFFIX = Regex("""\s*[\[(][^)\]]*[)\]]\s*$""")
    private val YEAR_RE = Regex("""\b(18|19|20)\d{2}\b""")
    private val CORRUPT_CDN = Regex("""h[zZ]cunaymatata""")
    private val APOSTROPHES = Regex("['‘’]")
    private val TITLE_SPLIT = Regex("""\s*[:–—-]\s+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val NON_CODEC = Regex("""[^A-Z0-9]""")
    private val TRAILING_P = Regex("""p$""", RegexOption.IGNORE_CASE)
    private val DUB_WORD = Regex("""\s+dub\b""", RegexOption.IGNORE_CASE)
    private val BRACKET = Regex("""[\[\]()]""")
    private val WHITESPACE = Regex("""\s+""")
    private val COMBINING = Regex("""[\x{0300}-\x{036F}]""")
    private val M3U8 = Regex("""\.m3u8(\?|$)""", RegexOption.IGNORE_CASE)

    private val VODNIK_CODEC = mapOf(
        "HEVC" to "hevc", "H265" to "hevc", "H264" to "h264", "AVC" to "h264", "AV1" to "av1", "VP9" to "vp9",
    )

    // [alpha-2, alpha-3, english name]. alpha-3 is what Stremio's language list resolves best.
    private val LANGS = listOf(
        Triple("en", "eng", "English"), Triple("es", "spa", "Spanish"), Triple("fr", "fra", "French"),
        Triple("de", "deu", "German"), Triple("it", "ita", "Italian"), Triple("pt", "por", "Portuguese"),
        Triple("ru", "rus", "Russian"), Triple("ja", "jpn", "Japanese"), Triple("ko", "kor", "Korean"),
        Triple("zh", "zho", "Chinese"), Triple("ar", "ara", "Arabic"), Triple("hi", "hin", "Hindi"),
        Triple("bn", "ben", "Bengali"), Triple("ta", "tam", "Tamil"), Triple("te", "tel", "Telugu"),
        Triple("ml", "mal", "Malayalam"), Triple("kn", "kan", "Kannada"), Triple("mr", "mar", "Marathi"),
        Triple("pa", "pan", "Punjabi"), Triple("gu", "guj", "Gujarati"), Triple("ur", "urd", "Urdu"),
        Triple("ne", "nep", "Nepali"), Triple("si", "sin", "Sinhala"), Triple("id", "ind", "Indonesian"),
        Triple("ms", "msa", "Malay"), Triple("th", "tha", "Thai"), Triple("vi", "vie", "Vietnamese"),
        Triple("tl", "tgl", "Filipino"), Triple("tr", "tur", "Turkish"), Triple("fa", "fas", "Persian"),
        Triple("he", "heb", "Hebrew"), Triple("el", "ell", "Greek"), Triple("pl", "pol", "Polish"),
        Triple("cs", "ces", "Czech"), Triple("sk", "slk", "Slovak"), Triple("hu", "hun", "Hungarian"),
        Triple("ro", "ron", "Romanian"), Triple("bg", "bul", "Bulgarian"), Triple("sr", "srp", "Serbian"),
        Triple("hr", "hrv", "Croatian"), Triple("sl", "slv", "Slovenian"), Triple("uk", "ukr", "Ukrainian"),
        Triple("nl", "nld", "Dutch"), Triple("sv", "swe", "Swedish"), Triple("da", "dan", "Danish"),
        Triple("no", "nor", "Norwegian"), Triple("fi", "fin", "Finnish"), Triple("is", "isl", "Icelandic"),
        Triple("et", "est", "Estonian"), Triple("lv", "lav", "Latvian"), Triple("lt", "lit", "Lithuanian"),
        Triple("sq", "sqi", "Albanian"), Triple("mk", "mkd", "Macedonian"), Triple("ka", "kat", "Georgian"),
        Triple("hy", "hye", "Armenian"), Triple("az", "aze", "Azerbaijani"), Triple("kk", "kaz", "Kazakh"),
        Triple("uz", "uzb", "Uzbek"), Triple("km", "khm", "Khmer"), Triple("lo", "lao", "Lao"),
        Triple("my", "mya", "Burmese"), Triple("mn", "mon", "Mongolian"), Triple("sw", "swa", "Swahili"),
        Triple("af", "afr", "Afrikaans"), Triple("am", "amh", "Amharic"), Triple("ha", "hau", "Hausa"),
        Triple("yo", "yor", "Yoruba"), Triple("zu", "zul", "Zulu"), Triple("ca", "cat", "Catalan"),
        Triple("eu", "eus", "Basque"), Triple("gl", "glg", "Galician"), Triple("ps", "pus", "Pashto"),
        Triple("ku", "kur", "Kurdish"),
    )

    private val BY_CODE = HashMap<String, String>().apply {
        for ((a2, a3, name) in LANGS) {
            put(a2, a3); put(a3, a3); put(name.lowercase(), a3)
        }
        // Legacy / site-specific spellings seen in subtitle and dub lists.
        val legacy = listOf(
            "in" to "ind", "iw" to "heb", "jw" to "jav", "fre" to "fra", "ger" to "deu", "chi" to "zho",
            "cze" to "ces", "dut" to "nld", "gre" to "ell", "per" to "fas", "rum" to "ron", "slo" to "slk",
            "alb" to "sqi", "arm" to "hye", "baq" to "eus", "geo" to "kat", "ice" to "isl", "mac" to "mkd",
            "may" to "msa", "bur" to "mya", "fil" to "tgl", "nb" to "nor", "nn" to "nor", "nob" to "nor",
            "nno" to "nor", "esla" to "spa", "ptbr" to "por", "esp" to "spa", "engsub" to "eng",
        )
        for ((k, v) in legacy) put(k, v)
    }

    private val NAME_KEYWORDS = listOf(
        "indonesia" to "ind", "melayu" to "msa", "tagalog" to "fil", "mandarin" to "zho", "cantonese" to "zho",
        "farsi" to "fas", "brazil" to "por", "bahasa melayu" to "msa", "filipino" to "tgl",
    )

    // Convert a 3-letter language code to a full English display name.
    private val LANG_FULL_NAMES = mapOf(
        "spa" to "Spanish", "por" to "Portuguese", "eng" to "English", "hin" to "Hindi", "tam" to "Tamil",
        "tel" to "Telugu", "mal" to "Malayalam", "kan" to "Kannada", "mar" to "Marathi", "guj" to "Gujarati",
        "pan" to "Punjabi", "ben" to "Bengali", "ori" to "Odia", "asm" to "Assamese", "urd" to "Urdu",
        "npi" to "Nepali", "nep" to "Nepali", "mon" to "Mongolian", "sin" to "Sinhala", "mya" to "Burmese", "khm" to "Khmer", "lao" to "Lao",
        "bod" to "Tibetan", "dzo" to "Dzongkydzongkha".let { "Dzongkha" }, "ind" to "Indonesian", "msa" to "Malay",
        "tgl" to "Filipino", "jav" to "Javanese", "fra" to "French", "deu" to "German", "zho" to "Chinese",
        "jpn" to "Japanese", "kor" to "Korean", "ita" to "Italian", "rus" to "Russian", "ara" to "Arabic",
        "tur" to "Turkish", "fas" to "Persian", "heb" to "Hebrew", "ell" to "Greek", "pol" to "Polish",
        "ces" to "Czech", "slk" to "Slovak", "hun" to "Hungarian", "ron" to "Romanian", "bul" to "Bulgarian",
        "srp" to "Serbian", "hrv" to "Croatian", "slv" to "Slovenian", "ukr" to "Ukrainian", "nld" to "Dutch",
        "swe" to "Swedish", "dan" to "Danish", "nor" to "Norwegian", "fin" to "Finnish", "isl" to "Icelandic",
        "est" to "Estonian", "lav" to "Latvian", "lit" to "Lithuanian", "sqi" to "Albanian", "mkd" to "Macedonian",
        "kat" to "Georgian", "hye" to "Armenian", "aze" to "Azerbaijani", "kaz" to "Kazakh", "uzb" to "Uzbek",
        "swa" to "Swahili", "afr" to "Afrikaans", "amh" to "Amharic", "hau" to "Hausa", "yor" to "Yoruba",
        "zul" to "Zulu", "cat" to "Catalan", "eus" to "Basque", "glg" to "Galician", "pus" to "Pashto",
        "kur" to "Kurdish",
    )

    private class Target(val imdb: String, val type: String, val season: Int, val episode: Int)
    private class CacheEntry(val at: Long, val resolved: MBResolved)
    private data class MBMember(val subjectId: String, val detailPath: String, val lanName: String?)
    private class MBSubjectMatch(val family: List<MBMember>, val originalId: String, val title: String)
    private class MBSubjectHit(val score: Int, val hitId: String, val slug: String, val subject: JSONObject)
    private data class MBSource(val height: Int, val codecs: List<String>, val url: String)
    private data class MBSubtitle(val id: String, val lan: String, val lang: String, val label: String, val url: String)
    private class MBVariant(
        val id: String, val subjectId: String, val lang: String, val label: String,
        var original: Boolean, var url: String, var sources: List<MBSource>, val subtitles: MutableList<MBSubtitle>,
    )
    private class MBResolved(val title: String, val streamUrls: List<String>, val variants: MutableList<MBVariant>, val subtitles: List<MBSubtitle>)
    private class MBBuilt(val url: String, val resolution: String, val sources: List<MBSource>, val subtitles: MutableList<MBSubtitle>)

    // Subject id -> captions response, filled by the fire-and-forget captions fetch in buildVariant. A play that
    // resolves before captions return gets them from here on the next play (the resolve cache holds the result).
    private val capsCache = ConcurrentHashMap<String, JSONObject>()
    private val resolveCache = ConcurrentHashMap<String, CacheEntry>()

    // The dub variants are independent, so resolve them together (as stremio-web-with-streamgrabber's Promise.all does)
    // instead of one after another: a film with three dubs then costs about one lookup, not three.
    private val resolvePool = java.util.concurrent.Executors.newFixedThreadPool(4)

    // Codec preference for one height, as in streamGrabber.js: hardware-friendly HEVC first, then H264/AV1.
    private fun codecRank(codec: String): Int = when (codec.uppercase()) {
        "HEVC", "H265" -> 0
        "H264", "AVC" -> 1
        "AV1" -> 2
        "VP9" -> 3
        else -> 4
    }

    // Union of two subjects' sources for one language: de-dupe by (height, codecs) and order highest-first with HEVC
    // before H264/AV1 at the same height, so the player's first row is the best quality that language offers.
    private fun mergeSources(a: List<MBSource>, b: List<MBSource>): List<MBSource> {
        val seen = HashSet<String>()
        val out = ArrayList<MBSource>()
        for (s in a + b) {
            if (s.height <= 0) continue
            if (seen.add("${s.height}|${s.codecs.joinToString(",")}")) out.add(s)
        }
        return out.sortedWith(compareByDescending<MBSource> { it.height }.thenBy { codecRank(it.codecs.firstOrNull().orEmpty()) })
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun json(url: String, timeoutMs: Int = TIMEOUT): Any =
        JSONTokener(Http.get(url, false, timeoutMs, strict = true, headers = mapOf("Accept" to "application/json"))).nextValue()

    private fun getObject(url: String, timeoutMs: Int = TIMEOUT): JSONObject = json(url, timeoutMs) as JSONObject

    private fun yearOf(v: String?): Int? = v?.let { YEAR_RE.find(it)?.value?.toIntOrNull() }

    private fun normTitle(s: String?): String {
        var t = Normalizer.normalize(s ?: "", Normalizer.Form.NFKD).replace(COMBINING, "")
        t = t.lowercase().replace(DUB_SUFFIX, "").replace("&", " and ")
        t = t.replace(APOSTROPHES, "")
        return t.replace(NON_ALNUM, " ").trim()
    }

    private fun compactTitle(s: String?): String = normTitle(s).replace(" ", "")

    private fun hasDubSuffix(s: String?): Boolean = s != null && DUB_SUFFIX.containsMatchIn(s) && normTitle(s).isNotEmpty()

    private fun toLangCode(lan: String?, name: String?): String? {
        val cleanName = (name ?: "").replace(DUB_WORD, "").trim()
        val code = (lan ?: "").trim().lowercase().replace("-", "_")
        if (code.isNotEmpty()) {
            BY_CODE[code]?.let { return it }
            BY_CODE[code.substringBefore("_")]?.let { return it }
        }
        val n = cleanName.lowercase()
        if (n.isNotEmpty()) {
            BY_CODE[n]?.let { return it }
            for ((word, c) in NAME_KEYWORDS) if (n.contains(word)) return c
            for (lang in LANGS) if (n.contains(lang.third.lowercase())) return lang.second
        }
        return code.ifEmpty { null }
    }

    private fun fullLangName(code: String?): String? = if (code.isNullOrEmpty()) null else LANG_FULL_NAMES[code]

    private fun resolutionHeight(s: JSONObject): Int? = s.optString("resolution").replace(TRAILING_P, "").toIntOrNull()

    private fun pickResolution(sources: List<JSONObject>, preferred: Int = PREFERRED_RES): Int? {
        val heights = sources.mapNotNull(::resolutionHeight).filter { it > 0 }.distinct()
        if (heights.isEmpty()) return null
        val below = heights.filter { it <= preferred }.sortedDescending()
        val above = heights.filter { it > preferred }.sorted()
        return (below + above).firstOrNull()
    }

    // moviebox2 backend bug: a DASH token's manifest URL (m) can carry a corrupted CDN domain (hZcunaymatata.com
    // instead of hakunaymatata.com). Correct it in both m and b, and make sure they share a host.
    private fun fixDashToken(dashUrl: String): String {
        try {
            val match = Regex("""/api/dash/([^/]+)/manifest\.mpd""").matchEntire(dashUrl) ?: return dashUrl
            val b64 = match.groupValues[1].replace('-', '+').replace('_', '/')
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            val token = JSONObject(String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8))
            val mUrl = token.optString("m")
            val bUrl = token.optString("b")
            if (mUrl.isNotEmpty() && bUrl.isNotEmpty()) {
                var m = if (CORRUPT_CDN.containsMatchIn(mUrl)) CORRUPT_CDN.replace(mUrl, "hakunaymatata") else mUrl
                var b = if (CORRUPT_CDN.containsMatchIn(bUrl)) CORRUPT_CDN.replace(bUrl, "hakunaymatata") else bUrl
                val mHost = URL(m).host
                val bHost = URL(b).host
                if (mHost != bHost && mHost.isNotEmpty()) m = m.replaceFirst(mHost, bHost)
                token.put("m", m)
                token.put("b", b)
                val encoded = Base64.encodeToString(token.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    .replace('+', '-').replace('/', '_').trimEnd('=')
                return "/api/dash/" + encoded + "/manifest.mpd"
            }
        } catch (_: Throwable) {
        }
        return dashUrl
    }

    private fun headerParam(headers: JSONObject?, name: String): String? {
        if (headers == null) return null
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.equals(name, ignoreCase = true)) return headers.optString(k).ifEmpty { null }
        }
        return null
    }

    // Routes a cross-origin CDN fetch through moviebox2's /media proxy (handles UA + CORS). Moviebox CDNs require the
    // oneroom User-Agent plus, for HLS, the Referer/Cookie the source carries; the browser cannot set those, so they
    // go as query params and moviebox2's media_proxy injects them server-side.
    private fun mediaProxyUrl(cdnUrl: String, id: String, se: Int, ep: Int, headers: JSONObject?): String {
        var url = "$BASE2/media?url=${enc(cdnUrl)}&sid=$id&se=$se&ep=$ep"
        headerParam(headers, "user-agent")?.let { url += "&ua=${enc(it)}" }
        headerParam(headers, "referer")?.let { url += "&ref=${enc(it)}" }
        headerParam(headers, "cookie")?.let { url += "&cookie=${enc(it)}" }
        return url
    }

    // vodnik HLS URL for HEVC MP4: <BASE2>/hls/<id>-<se>-<ep>-<res>-<codec>-MP4/master.m3u8
    private fun vodnikUrl(id: String, se: Int, ep: Int, res: Int, codec: String): String {
        val c = VODNIK_CODEC[codec.uppercase().replace(NON_CODEC, "")] ?: "hevc"
        return "$BASE2/hls/$id-$se-$ep-${res}p-$c-MP4/master.m3u8"
    }

    private fun getTitle(imdb: String, type: String): Pair<String, Int?> {
        val meta = getObject("$CINEMETA/meta/$type/$imdb.json").optJSONObject("meta") ?: JSONObject()
        val name = meta.text("name") ?: throw IllegalStateException("title not found in Cinemeta")
        val year = yearOf(meta.text("year")) ?: yearOf(meta.text("releaseInfo")) ?: yearOf(meta.text("released"))
        return name to year
    }

    private fun epCount(x: JSONObject): Double {
        val v = when {
            x.has("maxEp") && !x.isNull("maxEp") -> x.opt("maxEp")
            x.has("max_ep") && !x.isNull("max_ep") -> x.opt("max_ep")
            x.has("episodes") && !x.isNull("episodes") -> x.opt("episodes")
            x.has("epCount") && !x.isNull("epCount") -> x.opt("epCount")
            x.has("episodeCount") && !x.isNull("episodeCount") -> x.opt("episodeCount")
            x.has("total") && !x.isNull("total") -> x.opt("total")
            else -> null
        }
        return (v as? Number)?.toDouble() ?: Double.NaN
    }

    // Find the Moviebox subject (and its dub family) for a title. Exact (spacing/punctuation-insensitive) title matches
    // win; a prefix match is accepted only when the release year agrees.
    private fun findSubject(name: String, year: Int?, type: String): MBSubjectMatch {
        val wanted = compactTitle(name)
        val querySet = LinkedHashSet<String>()
        querySet.add(name)
        querySet.add(name.replace(APOSTROPHES, ""))
        querySet.add(name.split(TITLE_SPLIT).first())
        val queries = querySet.filter { it.isNotBlank() }.take(3)
        val seenIds = HashSet<String>()
        val exact = ArrayList<JSONObject>()
        val loose = ArrayList<JSONObject>()
        var searchError: Throwable? = null
        val notes = ArrayList<String>()

        data class Step(val q: String, val label: String, val path: String)
        val steps = ArrayList<Step>()
        for (s in queries) steps.add(Step(s, "search \"$s\"", "/api/search?q=${enc(s)}"))
        for (n in listOf(2, 3)) steps.add(Step(queries[0], "search \"${queries[0]}\" p$n", "/api/search?q=${enc(queries[0])}&page=$n"))
        for (s in queries) steps.add(Step(s, "search all \"$s\"", "/api/search/all?q=${enc(s)}"))
        for (n in listOf(2, 3)) steps.add(Step(queries[0], "search all \"${queries[0]}\" p$n", "/api/search/all?q=${enc(queries[0])}&page=$n"))

        var differs = 0
        var searchWorked = false
        for (step in steps) {
            val search = try {
                json("$BASE2${step.path}")
            } catch (e: Throwable) {
                searchError = e
                notes.add("${step.label} failed: ${e.message}")
                continue
            }
            searchWorked = true
            val list: List<JSONObject> = when (search) {
                is JSONArray -> search.objects()
                is JSONObject -> (
                    search.optJSONArray("items")
                        ?: search.optJSONArray("results")
                        ?: search.optJSONObject("data")?.optJSONArray("items")
                    )?.objects() ?: emptyList()
                else -> emptyList()
            }
            notes.add("${step.label}: ${list.size} items${if (list.isEmpty()) " (keys: ${keysOf(search).take(6)})" else ""}")
            for (i in list) {
                val sid = i.text("subject_id")
                val slug = i.text("slug")
                if (sid == null || !SAFE_ID.matches(sid) || slug == null || !SAFE_SLUG.matches(slug) || seenIds.contains(sid)) continue
                val have = compactTitle(i.text("name"))
                if (have.isEmpty()) continue
                if (have != wanted && differs < 4) {
                    differs += 1
                    notes.add("title \"${i.text("name")}\" differs")
                }
                if (have == wanted) {
                    seenIds.add(sid); exact.add(i)
                } else if (year != null && wanted.length >= 5 && have.length >= 5 && (have.startsWith(wanted) || wanted.startsWith(have))) {
                    seenIds.add(sid); loose.add(i)
                }
            }
            // Keep scanning the cheap /api/search pages (all spellings + pages 2-3 of the main query) so every
            // "Title [Language]" dub sibling is collected, not just page 1 — a dub on page 2 would otherwise be
            // missing from the language list. Only stop early once the slow /api/search/all fallback finds a hit.
            if (exact.isNotEmpty() && step.path.contains("/all")) break
        }

        val byOriginalFirst = compareBy<JSONObject> { if (hasDubSuffix(it.text("name"))) 1 else 0 }
        val hits = ArrayList<JSONObject>()
        hits.addAll(exact.sortedWith(byOriginalFirst).take(MAX_CANDIDATES))
        if (exact.size < MAX_CANDIDATES) {
            hits.addAll(loose.sortedWith(byOriginalFirst).take(MAX_CANDIDATES - exact.size))
        }
        val isLoose = HashSet(loose.mapNotNull { it.text("subject_id") })

        var best: MBSubjectHit? = null
        for (hit in hits) {
            val hitId = hit.text("subject_id") ?: continue
            val slug = hit.text("slug") ?: hitId
            val detail = try {
                var d = try { getObject("$BASE2/api/detail/${enc(hitId)}") } catch (_: Throwable) { null }
                if (d == null || d.optJSONObject("subject") == null) d = getObject("$BASE2/api/detail/all/${enc(hitId)}") else d
                d
            } catch (e: Throwable) {
                notes.add("detail \"$hitId\" failed: ${e.message}")
                null
            } ?: continue
            val subject = detail.optJSONObject("subject") ?: JSONObject()
            val seasons = subject.optJSONArray("seasons")?.objects() ?: emptyList()
            val isSeries = subject.optInt("subjectType") == 2
            val singleEpisode = seasons.size <= 1 && seasons.none { epCount(it) > 1 }
            val typeOk = if (type == "series") isSeries else (!isSeries || singleEpisode)
            if (!typeOk) {
                notes.add("\"${hit.text("name")}\" is ${if (isSeries) "a series" else "a movie"}")
                continue
            }
            val y = yearOf(subject.text("year")) ?: yearOf(subject.text("releaseDate"))
            if (year != null && y != null && abs(year - y) > 1) {
                notes.add("\"${hit.text("name")}\" year $y vs $year")
                continue
            }
            if (isLoose.contains(hitId) && !(year != null && y != null)) {
                notes.add("\"${hit.text("name")}\" has no year to confirm a loose match")
                continue
            }
            val seasonBonus = if (seasons.size > 1) 2 else 0
            val score = (if (year != null && y != null) (if (year == y) 3 else 1) else 0) +
                (if (hasDubSuffix(hit.text("name"))) 0 else 1) -
                (if (isLoose.contains(hitId)) 2 else 0) + seasonBonus
            if (best == null || score > best!!.score) best = MBSubjectHit(score, hitId, slug, subject)
            if (score >= 4) break
        }
        if (best == null && searchError != null && !searchWorked) throw searchError!!
        val found = best ?: throw IllegalStateException(
            "no Moviebox match for \"$name\"${year?.let { " ($it)" } ?: ""}${if (notes.isNotEmpty()) " [${notes.take(12).joinToString("; ")}]" else ""}"
        )

        val self = MBMember(found.hitId, found.slug, null)
        val family = ArrayList<MBMember>()
        for (d in found.subject.optJSONArray("dubs")?.objects() ?: emptyList()) {
            val sid = d.text("subjectId")
            if (sid == null || !SAFE_ID.matches(sid)) continue
            val detailPath = d.text("detailPath") ?: d.text("slug") ?: sid
            val lanName = d.text("lanName") ?: d.text("language") ?: d.text("label")
            family.add(MBMember(sid, detailPath, lanName))
        }
        if (family.none { it.subjectId == self.subjectId }) family.add(0, self)
        // Dub siblings are listed in search as "Title [Hindi]"; add any the detail's dubs list left out.
        for (i in exact) {
            val id = i.text("subject_id") ?: continue
            if (!hasDubSuffix(i.text("name")) || family.any { it.subjectId == id }) continue
            val label = (DUB_SUFFIX.find(i.text("name") ?: "")?.value ?: "").replace(BRACKET, "").trim()
            if (label.isNotEmpty() && toLangCode(null, label) != null) {
                family.add(MBMember(id, i.text("slug") ?: id, label))
            }
        }
        // The original audio is the family member that is NOT a named dub: a null/blank lanName or one that reads
        // "Original"/"Original Audio". Identifying it by the detail name's dub suffix was unreliable (Moviebox stores
        // the base name there, so a dub subject looked unsuffixed and stole the original flag — which relabelled that
        // dub's streams as "Original" and hid both it and the true original behind one entry).
        val originalId = family.firstOrNull { m ->
            val n = m.lanName?.replace(DUB_WORD, "")?.trim().orEmpty()
            m.lanName == null || n.isEmpty() || n.equals("original", ignoreCase = true) ||
                n.equals("original audio", ignoreCase = true)
        }?.subjectId ?: family.firstOrNull()?.subjectId ?: self.subjectId
        return MBSubjectMatch(family.take(MAX_DUBS), originalId, found.subject.text("title") ?: name)
    }

    private fun keysOf(v: Any?): List<String> = (v as? JSONObject)?.keys()?.asSequence()?.toList() ?: emptyList()

    // One family member -> { url, sources[], subtitles[] }, or null when it has no playable stream.
    private fun buildVariant(member: MBMember, se: Int, ep: Int, imdb: String): MBBuilt? {
        val id = member.subjectId
        val streamsFast = { getObject("$BASE2/api/streams/$id?se=$se&ep=$ep&imdb=${enc(imdb)}") }
        val streamsAll = { getObject("$BASE2/api/streams/all/$id?se=$se&ep=$ep&imdb=${enc(imdb)}") }
        // Captions are slow (~2.7s) and never on the stream path, so they are only read from the warm cache here; the
        // subtitle lookup (Moviebox.subtitles) fetches them on demand when the player waits for them.
        val stream = try {
            val fast = try { streamsFast() } catch (_: Throwable) { null }
            if (fast != null && (fast.optJSONArray("sources")?.objects()?.isNotEmpty() == true)) fast else streamsAll()
        } catch (_: Throwable) {
            return null
        }
        val caps = capsCache[id] ?: JSONObject().apply { put("captions", JSONArray()) }
        val sources = stream.optJSONArray("sources")?.objects() ?: emptyList()
        if (sources.isEmpty()) return null
        val res = pickResolution(sources) ?: return null

        fun codecOf(s: JSONObject) = s.optString("codec").uppercase().replace(NON_CODEC, "")
        fun heightOf(s: JSONObject) = s.optString("resolution").replace(TRAILING_P, "").toIntOrNull()
        fun isHevc(c: String) = c == "HEVC" || c == "H265"

        // vodnik's mapper only serves an asset when a matching MP4 release exists at (resolution, codec); a DASH-only
        // source has none, so track which (height, codec) pairs have an MP4 sibling before rewriting to /hls/...-MP4.
        val mp4Keys = HashSet<String>()
        val mp4Proxied = HashMap<String, String>()
        for (s in sources) {
            if (s.optString("format").uppercase() == "MP4" && s.optString("url").isNotEmpty()) {
                val key = "${heightOf(s)}-${codecOf(s)}"
                mp4Keys.add(key)
                mp4Proxied[key] = mediaProxyUrl(s.optString("url"), id, se, ep, s.optJSONObject("headers"))
            }
        }
        val byHeight = LinkedHashMap<Int, MutableList<Triple<String, String, String>>>()
        for (s in sources) {
            val h = heightOf(s) ?: continue
            if (h <= 0) continue
            val format = s.optString("format").uppercase()
            val codec = codecOf(s)
            val url = s.optString("url")
            if (url.isEmpty()) continue
            var playUrl: String? = null
            when {
                format == "MP4" || format.isEmpty() ->
                    // Route every MP4 (HEVC and AVC) through the /media proxy as a progressive file. vodnik's on-demand
                    // HLS muxes audio into the video fMP4 (hls_mux_audio), and ExoPlayer's chunkless preparation drops
                    // that muxed audio track (androidx/media #3485), leaving a silent video. The progressive MP4 keeps
                    // both tracks and ExoPlayer demuxes it normally.
                    playUrl = mediaProxyUrl(url, id, se, ep, s.optJSONObject("headers"))
                format == "HLS" || M3U8.containsMatchIn(url) ->
                    playUrl = mediaProxyUrl(url, id, se, ep, s.optJSONObject("headers"))
                format == "DASH" && s.optString("dash_url").startsWith("/") ->
                    playUrl = "$BASE2${fixDashToken(s.optString("dash_url"))}"
            }
            if (playUrl == null) continue
            var finalPlayUrl = playUrl
            if (format == "DASH" && isHevc(codec) && mp4Keys.contains("$h-$codec")) {
                // Prefer the audio-safe progressive MP4 (proxied) over vodnik's on-demand HLS: that HLS muxes audio
                // into the video fMP4 and ExoPlayer's chunkless preparation drops it, leaving a silent picture
                // (androidx/media #3485). The MP4 sibling keeps both tracks.
                finalPlayUrl = mp4Proxied["$h-$codec"] ?: vodnikUrl(id, se, ep, h, codec)
            }
            val entry = byHeight.getOrPut(h) { ArrayList() }
            if (entry.none { it.first == codec && it.second == finalPlayUrl }) {
                entry.add(Triple(codec, finalPlayUrl, format))
            }
        }
        if (byHeight.isEmpty()) return null
        val allSources = byHeight.entries.sortedByDescending { it.key }.flatMap { e ->
            e.value.map { MBSource(e.key, if (it.first.isEmpty()) emptyList() else listOf(it.first), it.second) }
        }

        val seen = HashSet<String>()
        val subtitles = ArrayList<MBSubtitle>()
        for (c in caps.optJSONArray("captions")?.objects() ?: emptyList()) {
            val curl = c.text("url") ?: continue
            val name = c.text("name") ?: c.text("lan") ?: ""
            val key = name.lowercase()
            if (!seen.add(key)) continue
            val label = if (name.isEmpty()) "Subtitle" else name
            val lang = toLangCode(name, name) ?: name.lowercase()
            val sid = "MBSUB_${id}_${name.replace(WHITESPACE, "_")}"
            subtitles.add(MBSubtitle(sid, name, lang, label, curl))
        }
        return MBBuilt(allSources[0].url, "${res}p", allSources, subtitles)
    }

    private fun doResolve(target: Target): MBResolved {
        val (name, year) = getTitle(target.imdb, target.type)
        val match = findSubject(name, year, target.type)
        val se = target.season
        val ep = target.episode

        val variants = ArrayList<MBVariant>()
        val futures = match.family.map { m ->
            resolvePool.submit(java.util.concurrent.Callable<Pair<MBMember, MBBuilt?>> {
                m to runCatching { buildVariant(m, se, ep, target.imdb) }.getOrNull()
            })
        }
        for (f in futures) {
            val (m, built) = f.get()
            if (built == null) {
                android.util.Log.w("AlderStreams", "variant dropped (no playable stream): lan=${m.lanName} id=${m.subjectId}")
                continue
            }
            val code = toLangCode(null, m.lanName)
            val label = if (m.lanName != null) {
                fullLangName(code) ?: m.lanName.replace(DUB_WORD, "").trim().ifEmpty { "Original" }
            } else "Original"
            variants.add(
                MBVariant(
                    "MBDUB_${m.subjectId}", m.subjectId, code ?: "und", label,
                    m.subjectId == match.originalId, built.url, built.sources, built.subtitles,
                )
            )
            if (variants.size >= MAX_VARIANTS) break
        }
        if (variants.isEmpty()) throw IllegalStateException("Moviebox has no playable stream for \"$name\"")
        android.util.Log.i("AlderStreams", "family: " + match.family.joinToString(" | ") { "lan=${it.lanName}" })
        android.util.Log.i("AlderStreams", "variants(pre-merge): " + variants.joinToString(" | ") {
            "orig=${it.original} lang=${it.lang} label='${it.label}' heights=[${it.sources.map { s -> s.height }.distinct().joinToString(",")}]"
        })

        // A title's dubs are spread across several Moviebox subjects, and the same audio language can appear on more
        // than one (two "Hindi" subjects, an English-tagged release separate from the untagged original). Collapse
        // them to ONE variant per language, merging every subject's sources so each language plays at the best quality
        // any of them offers: the original is often only a 480p placeholder while a 1080p release of the same film
        // sits on another subject. The original/untagged audio is treated as English, so an English-tagged release
        // folds into it and English plays at its best resolution too.
        val merged = LinkedHashMap<String, MBVariant>()
        for (v in variants) {
            val key = when {
                v.original -> "eng"
                v.lang == "und" || v.lang.isEmpty() -> "und"
                else -> v.lang
            }
            val existing = merged[key]
            if (existing == null) {
                merged[key] = v
            } else {
                existing.sources = mergeSources(existing.sources, v.sources)
                existing.url = existing.sources.firstOrNull()?.url ?: existing.url
                existing.original = existing.original || v.original
                val have = HashSet(existing.subtitles.map { it.lan.lowercase() })
                for (t in v.subtitles) if (have.add(t.lan.lowercase())) existing.subtitles.add(t)
            }
        }
        val mergedVariants = ArrayList(merged.values)
        variants.clear()
        variants.addAll(mergedVariants)

        // Original audio first; it is what plays unless the viewer's audio preference matches a dub.
        variants.sortByDescending { if (it.original) 1 else 0 }
        if (!variants[0].original) variants[0].original = true
        // A dub is the same film, so a language Moviebox only lists on another family member is usable on it too.
        for (i in variants.indices) {
            val v = variants[i]
            val have = HashSet(v.subtitles.map { it.lan.lowercase() })
            for (j in variants.indices) {
                if (j == i) continue
                for (t in variants[j].subtitles) {
                    if (!have.add(t.lan.lowercase())) continue
                    v.subtitles.add(t.copy(id = "MBSUB_${v.subjectId}_${t.lan}"))
                }
            }
        }
        return MBResolved(name, listOf(variants[0].url), variants, variants[0].subtitles)
    }

    private fun resolve(target: Target): MBResolved {
        val key = "${target.imdb}|${target.type}|${target.season}|${target.episode}"
        resolveCache[key]?.let { if (System.currentTimeMillis() - it.at < CACHE_MS) return it.resolved }
        val resolved = doResolve(target)
        resolveCache[key] = CacheEntry(System.currentTimeMillis(), resolved)
        if (resolveCache.size > 100) resolveCache.keys.firstOrNull()?.let { resolveCache.remove(it) }
        return resolved
    }

    private fun parseTarget(type: String, videoId: String): Target? {
        if (type != "movie" && type != "series") return null
        val parts = videoId.split(":")
        val imdb = parts.getOrNull(0) ?: return null
        if (!IMDB_ID.matches(imdb)) return null
        return if (type == "series") {
            val se = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val ep = parts.getOrNull(2)?.toIntOrNull() ?: return null
            Target(imdb, type, se, ep)
        } else {
            Target(imdb, type, 0, 0)
        }
    }

    /**
     * The streams for one video, as a source list: one row per (dub, resolution) the way stremio-web-with-streamgrabber
     * offers them. Each row's name carries the resolution, codec and dub language so Alder ranks and filters it (height,
     * HEVC/AV1 on a TV that can't decode them, the viewer's audio language). [Stream.bingeGroup] is the dub, so the next
     * episode of the same dub is preferred. A lookup that fails gives an empty list, not an error: the other add-ons play.
     */
    fun streams(type: String, videoId: String): Addon.Streams {
        val target = parseTarget(type, videoId) ?: return Addon.Streams(emptyList(), 0, 0)
        val source = internalSourceName()
        val resolved = try {
            resolve(target)
        } catch (e: Throwable) {
            android.util.Log.w("Alder", "Moviebox resolve failed for $videoId", e)
            return Addon.Streams(emptyList(), 0, 0)
        }
        val out = ArrayList<Stream>()
        for (v in resolved.variants) {
            // One source per height, HEVC before H264/AV1 at that height (streamGrabber.js pickSourceUrl codecRank).
            val byHeight = LinkedHashMap<Int, MBSource>()
            for (s in v.sources.sortedWith(compareByDescending<MBSource> { it.height }.thenBy { codecRank(it.codecs.firstOrNull().orEmpty()) })) {
                if (s.height > 0) byHeight.putIfAbsent(s.height, s)
            }
            // swsg plays at or under 1080p first (highest such first), then anything above 1080p (lowest first): the
            // first row is the auto source and the next rows are the downshift ladder the player falls back through.
            val below = byHeight.keys.filter { it in 1..PREFERRED_RES }.sortedDescending()
            val above = byHeight.keys.filter { it > PREFERRED_RES }.sorted()
            for (h in below + above) {
                val s = byHeight[h] ?: continue
                val codec = s.codecs.firstOrNull()
                val codecPart = codec?.let { " · $it" } ?: ""
                // The dub language and "Dub" marker live in the description so Alder ranks audio and reads the codec and
                // height; the original names no language, so Alder treats it as English (the default audio).
                val description = if (v.original)
                    "$source · Original · ${s.height}p$codecPart"
                else
                    "$source · ${v.label} · Dub · ${s.height}p$codecPart"
                out.add(Stream(source, description, s.url, emptyMap(), 0L, null, v.id))
            }
        }
        return Addon.Streams(out.take(MAX_STREAMS), 0, 0)
    }

    /** The Moviebox subtitle files (WebVTT) for one video, from the first (original) variant. */
    fun subtitles(type: String, videoId: String): List<Subtitle> {
        val target = parseTarget(type, videoId) ?: return emptyList()
        val source = internalSourceName()
        val resolved = try {
            resolve(target)
        } catch (_: Throwable) {
            return emptyList()
        }
        val v = resolved.variants.firstOrNull() ?: return emptyList()
        if (v.subtitles.isNotEmpty()) return v.subtitles.map { Subtitle(it.url, it.lang, source) }
        // The captions lookup is slow and never on the stream path; the player waits briefly for subtitles, so ask now.
        return try {
            val c = getObject("$BASE2/api/captions/${v.subjectId}?se=${target.season}&ep=${target.episode}", 5000)
            capsCache[v.subjectId] = c
            val seen = HashSet<String>()
            val out = ArrayList<Subtitle>()
            for (cap in c.optJSONArray("captions")?.objects() ?: emptyList()) {
                val url = cap.text("url") ?: continue
                val name = cap.text("name") ?: cap.text("lan") ?: ""
                if (!seen.add(name.lowercase())) continue
                out.add(Subtitle(url, toLangCode(name, name) ?: name.lowercase(), source))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }
}

/**
 * The Moviebox source as a built-in add-on: always present, never saved to the account, asked for streams and subtitles
 * alongside the installed add-ons (see StreamsActivity, StreamsResolver and PlayerActivity). It has no settings page, so
 * [Addon.configureUrl] stays null and no QR setup row is shown for it.
 */
class MovieboxAddon : Addon(
    JSONObject().apply {
        put("transportUrl", "https://moviebox.mewayz.com/manifest.json")
        put(
            "manifest",
            JSONObject().apply {
                put("id", "moviebox")
                put("name", internalSourceName())
                put("types", JSONArray().put("movie").put("series"))
                put("resources", JSONArray().put("stream").put("subtitles"))
                put("idPrefixes", JSONArray().put("tt"))
            },
        )
    },
) {
    override fun streams(type: String, videoId: String, allowTorrents: Boolean): Addon.Streams = Moviebox.streams(type, videoId)

    override fun subtitles(type: String, videoId: String, stream: Stream?): List<Subtitle> = Moviebox.subtitles(type, videoId)
}

class HouseAddon : Addon(
    JSONObject().apply {
        put("transportUrl", "https://stremio.thehouseofthemaster.pt/manifest.json")
        put(
            "manifest",
            JSONObject().apply {
                put("id", "org.local.streamimdb")
                put("name", internalSourceName())
                put("types", JSONArray().put("movie").put("series"))
                put("resources", JSONArray().put("stream"))
                put("idPrefixes", JSONArray().put("tt"))
            },
        )
    },
) {
    override fun streams(type: String, videoId: String, allowTorrents: Boolean): Addon.Streams {
        val base = super.streams(type, videoId, allowTorrents)
        return Addon.Streams(
            base.playable.map {
                Stream(internalSourceName(), it.description, it.url, it.headers, it.size, it.filename, it.bingeGroup, it.torrent)
            },
            base.torrents,
            base.links,
        )
    }
}