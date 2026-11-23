package dev.alder

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Enriches movie and series cast metadata with full cast lists, directors, character names, and headshot photos from TMDB.
 */
object Tmdb {
    private const val KEY = "844dba0bfd8f3a4f3799f6130ef9e335"
    private const val BASE = "https://api.themoviedb.org/3"
    private val memoryCache = ConcurrentHashMap<String, List<CastMember>>()
    private val creditsCache = ConcurrentHashMap<String, Set<String>>()
    private val similarCache = ConcurrentHashMap<String, List<Meta>>()
    private val overviewCache = ConcurrentHashMap<String, String>()
    // A title's IMDb id, asked once: Discover pages repeat titles as filters change, and each was a network round trip.
    private val imdbCache = ConcurrentHashMap<String, String>()

    /** Full synopsis from TMDB, for when a meta add-on (e.g. Cinemeta) ships a truncated description. */
    fun overview(imdbId: String, type: String): String? {
        if (!imdbId.startsWith("tt")) return null
        overviewCache[imdbId]?.let { return it }
        return runCatching {
            val findUrl = "$BASE/find/$imdbId?api_key=$KEY&external_source=imdb_id"
            val findJson = JSONObject(Http.get(findUrl, keep = true))
            val isMovie = type != "series"
            val primaryKey = if (isMovie) "movie_results" else "tv_results"
            val fallbackKey = if (isMovie) "tv_results" else "movie_results"
            val target = findJson.optJSONArray(primaryKey)?.optJSONObject(0)
                ?: findJson.optJSONArray(fallbackKey)?.optJSONObject(0)
                ?: return null
            val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return null
            val mediaType = if (target.has("title")) "movie" else "tv"
            val detailsUrl = "$BASE/$mediaType/$tmdbId?api_key=$KEY"
            JSONObject(Http.get(detailsUrl, keep = true)).text("overview")?.takeIf { it.isNotBlank() }
        }.getOrNull()?.also { overviewCache[imdbId] = it }
    }

    fun cast(imdbId: String, type: String): List<CastMember> {
        if (!imdbId.startsWith("tt")) return emptyList()
        memoryCache[imdbId]?.let { return it }
        return runCatching {
            val findUrl = "$BASE/find/$imdbId?api_key=$KEY&external_source=imdb_id"
            val findJson = JSONObject(Http.get(findUrl, keep = true))
            val isMovie = type != "series"
            val primaryKey = if (isMovie) "movie_results" else "tv_results"
            val fallbackKey = if (isMovie) "tv_results" else "movie_results"
            val target = findJson.optJSONArray(primaryKey)?.optJSONObject(0)
                ?: findJson.optJSONArray(fallbackKey)?.optJSONObject(0)
                ?: return emptyList()
            val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return emptyList()
            val mediaType = if (target.has("title")) "movie" else "tv"
            val creditsUrl = "$BASE/$mediaType/$tmdbId/credits?api_key=$KEY"
            val creditsJson = JSONObject(Http.get(creditsUrl, keep = true))

            val directors = mutableListOf<CastMember>()
            val crewArray = creditsJson.optJSONArray("crew")
            if (crewArray != null) {
                for (i in 0 until crewArray.length()) {
                    val item = crewArray.optJSONObject(i) ?: continue
                    val job = item.optString("job").trim()
                    val isDir = if (isMovie) {
                        job.equals("Director", ignoreCase = true)
                    } else {
                        job.equals("Director", ignoreCase = true) ||
                            job.equals("Series Director", ignoreCase = true) ||
                            job.equals("Creator", ignoreCase = true)
                    }
                    if (isDir) {
                        val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                        val profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() && it != "null" }
                        val photoUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                        val id = item.optInt("id").takeIf { it > 0 }
                        directors.add(CastMember(name, "Director", photoUrl, role = "director", tmdbId = id))
                    }
                }
            }

            val castArray = creditsJson.optJSONArray("cast") ?: org.json.JSONArray()
            val actors = mutableListOf<CastMember>()
            for (i in 0 until castArray.length()) {
                val item = castArray.optJSONObject(i) ?: continue
                val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
                val character = item.optString("character").trim().takeIf { it.isNotBlank() && it != "null" }
                val profilePath = item.optString("profile_path").trim().takeIf { it.isNotBlank() && it != "null" }
                val photoUrl = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                val id = item.optInt("id").takeIf { it > 0 }
                actors.add(CastMember(name, character, photoUrl, role = "actor", tmdbId = id))
            }

            val list = (directors.distinctBy { it.name } + actors.distinctBy { it.name })
            list.also { memoryCache[imdbId] = it }
        }.getOrDefault(emptyList())
    }

    /**
     * Resolves credited movie and TV titles for a person from TMDB to filter out search false-positives.
     */
    fun personCredits(personId: Int?, personName: String, isDirector: Boolean): Set<String> {
        val cacheKey = "${personId ?: personName}_$isDirector".lowercase()
        creditsCache[cacheKey]?.let { return it }

        return runCatching {
            var targetId = personId
            if (targetId == null || targetId <= 0) {
                val searchUrl = "$BASE/search/person?api_key=$KEY&query=${android.net.Uri.encode(personName)}"
                val searchJson = JSONObject(Http.get(searchUrl, keep = true))
                val first = searchJson.optJSONArray("results")?.optJSONObject(0)
                targetId = first?.optInt("id")?.takeIf { it > 0 }
            }
            if (targetId == null || targetId <= 0) return emptySet()

            val combinedUrl = "$BASE/person/$targetId/combined_credits?api_key=$KEY"
            val combinedJson = JSONObject(Http.get(combinedUrl, keep = true))
            val set = mutableSetOf<String>()

            if (isDirector) {
                val crew = combinedJson.optJSONArray("crew")
                if (crew != null) {
                    for (i in 0 until crew.length()) {
                        val item = crew.optJSONObject(i) ?: continue
                        val job = item.optString("job")
                        val dept = item.optString("department")
                        if (job.equals("Director", ignoreCase = true) || dept.equals("Directing", ignoreCase = true)) {
                            val title = item.optString("title").ifEmpty { item.optString("name") }.trim().lowercase()
                            if (title.isNotBlank()) set.add(title)
                        }
                    }
                }
            } else {
                val cast = combinedJson.optJSONArray("cast")
                if (cast != null) {
                    for (i in 0 until cast.length()) {
                        val item = cast.optJSONObject(i) ?: continue
                        val title = item.optString("title").ifEmpty { item.optString("name") }.trim().lowercase()
                        if (title.isNotBlank()) set.add(title)
                    }
                }
            }
            set.also { creditsCache[cacheKey] = it }
        }.getOrDefault(emptySet())
    }

    // ---------- Discover: every film and show, by TMDB's discover search ----------

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    private val language get() = java.util.Locale.getDefault().toLanguageTag()
    /** The TV's country, for "On Netflix" (streaming services differ by country); TMDB is told nothing else. */
    val region: String get() = java.util.Locale.getDefault().country.ifEmpty { "US" }

    /** TMDB's genres for films or shows ("movie" or "tv"), in the TV's language: id to name. */
    fun genres(media: String): List<Pair<Int, String>> =
        JSONObject(Http.get("$BASE/genre/$media/list?api_key=$KEY&language=$language", keep = true))
            .optJSONArray("genres").objects().map { it.optInt("id") to it.optString("name") }

    /** The streaming services TMDB knows in this country, most watched first: id to name. */
    fun services(media: String): List<Pair<Int, String>> =
        JSONObject(Http.get("$BASE/watch/providers/$media?api_key=$KEY&language=$language&watch_region=$region", keep = true))
            .optJSONArray("results").objects()
            .sortedBy { it.optJSONObject("display_priorities")?.optInt(region, 999) ?: it.optInt("display_priority", 999) }
            // Subscriptions only: rental stores and add-on channels ("Apple TV Store", "… Amazon Channel") aren't a place to browse.
            .filterNot { s -> listOf("Store", "Channel", "Amazon Video", "JustWatch").any { it in s.optString("provider_name") } }
            .take(SERVICES).map { it.optInt("provider_id") to it.optString("provider_name") }

    class Page(val titles: List<Meta>, val total: Int, val last: Boolean)

    /**
     * One page (20 titles) of [query]. Each title gets its IMDb id first (TMDB's external ids, saved on disk), so it
     * opens, plays and goes to My List like any other; titles without one are left out.
     */
    fun discover(query: DiscoverQuery, page: Int, genreNames: Map<Int, String>): Page {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val answer = JSONObject(Http.get(discoverUrl(query, page, region, language, today, KEY)))
        val found = answer.optJSONArray("results").objects()
        val media = query.media
        // ponytail: one external-ids request per title, 8 at a time, kept in memory; ~20 small requests a page.
        val ids = pool.invokeAll(found.map { t ->
            java.util.concurrent.Callable {
                val cacheKey = "$media:${t.optInt("id")}"
                imdbCache[cacheKey]?.let { return@Callable it }
                runCatching {
                    JSONObject(Http.get("$BASE/$media/${t.optInt("id")}/external_ids?api_key=$KEY", keep = true)).text("imdb_id")
                }.getOrNull()?.takeIf { it.startsWith("tt") }?.also { imdbCache[cacheKey] = it }
            }
        }).map { runCatching { it.get() }.getOrNull() }
        val titles = found.zip(ids).mapNotNull { (t, imdb) -> imdb?.let { discoverMeta(t, it, query.type == "movie", genreNames) } }
        return Page(titles, answer.optInt("total_results"), page >= answer.optInt("total_pages"))
    }

    private const val SERVICES = 14

    /**
     * Fallback recommendations from TMDB when installed Stremio add-ons do not advertise a `similar` catalog.
     */
    fun similar(imdbId: String, type: String): List<Meta> {
        if (!imdbId.startsWith("tt")) return emptyList()
        val cacheKey = "${type}_$imdbId"
        similarCache[cacheKey]?.let { return it }
        return runCatching {
            val findUrl = "$BASE/find/$imdbId?api_key=$KEY&external_source=imdb_id"
            val findJson = JSONObject(Http.get(findUrl, keep = true))
            val isMovie = type != "series"
            val primaryKey = if (isMovie) "movie_results" else "tv_results"
            val fallbackKey = if (isMovie) "tv_results" else "movie_results"
            val target = findJson.optJSONArray(primaryKey)?.optJSONObject(0)
                ?: findJson.optJSONArray(fallbackKey)?.optJSONObject(0)
                ?: return emptyList()
            val tmdbId = target.optInt("id").takeIf { it > 0 } ?: return emptyList()
            val media = if (target.has("title")) "movie" else "tv"
            val recUrl = "$BASE/$media/$tmdbId/recommendations?api_key=$KEY&language=$language&page=1"
            val found = JSONObject(Http.get(recUrl, keep = true)).optJSONArray("results").objects().take(12)
            if (found.isEmpty()) return emptyList()
            val genreNames = runCatching { genres(media).toMap() }.getOrDefault(emptyMap())
            val ids = pool.invokeAll(found.map { t ->
                java.util.concurrent.Callable {
                    runCatching {
                        JSONObject(Http.get("$BASE/$media/${t.optInt("id")}/external_ids?api_key=$KEY", keep = true)).text("imdb_id")
                    }.getOrNull()?.takeIf { it.startsWith("tt") }
                }
            }).map { runCatching { it.get() }.getOrNull() }
            found.zip(ids).mapNotNull { (t, imdb) ->
                imdb?.takeIf { it != imdbId }?.let { discoverMeta(t, it, media == "movie", genreNames) }
            }.also { if (it.isNotEmpty()) similarCache[cacheKey] = it }
        }.getOrDefault(emptyList())
    }
}

/** Discover's filters: [type] is movie, series or anime; [decade] a year like 2010 (0: before 1980); [year] an exact year; [sort] popular, top or new. */
data class DiscoverQuery(val type: String = "movie", val genre: Int? = null, val decade: Int? = null,
                         val service: Int? = null, val sort: String = "popular", val year: Int? = null) {
    val media get() = if (type == "movie") "movie" else "tv"
}

/** TMDB's discover search for [q]: films by release date, shows by first air date; anime is Japanese animation. */
internal fun discoverUrl(q: DiscoverQuery, page: Int, region: String, language: String, today: String, key: String): String {
    val movie = q.type == "movie"
    val date = if (movie) "primary_release_date" else "first_air_date"
    val params = linkedMapOf<String, String?>("api_key" to key, "language" to language, "include_adult" to "false", "page" to "$page")
    params["with_genres"] = listOfNotNull(16.takeIf { q.type == "anime" }, q.genre).distinct().joinToString(",").ifEmpty { null }
    if (q.type == "anime") params["with_original_language"] = "ja"
    when {
        q.year != null -> { params["$date.gte"] = "${q.year}-01-01"; params["$date.lte"] = "${q.year}-12-31" }
        q.decade == 0 -> params["$date.lte"] = "1979-12-31"
        q.decade != null -> { params["$date.gte"] = "${q.decade}-01-01"; params["$date.lte"] = "${q.decade + 9}-12-31" }
    }
    if (q.service != null) { params["with_watch_providers"] = "${q.service}"; params["watch_region"] = region }
    when (q.sort) {
        "top" -> { params["sort_by"] = "vote_average.desc"; params["vote_count.gte"] = "300" }
        "new" -> {
            params["sort_by"] = "$date.desc"; params["vote_count.gte"] = "20"
            val lte = params["$date.lte"]
            if (lte == null || lte > today) params["$date.lte"] = today // not yet out: nothing to play
        }
        else -> params["sort_by"] = "popularity.desc"
    }
    return "https://api.themoviedb.org/3/discover/${q.media}?" +
        params.entries.filter { it.value != null }.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
}

/** A TMDB result as a Stremio meta, under its IMDb id; the meta add-on fills in the logo, runtime and rating. */
internal fun discoverMeta(t: JSONObject, imdb: String, movie: Boolean, genreNames: Map<Int, String>): Meta {
    val image = { path: String?, size: String -> path?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" } }
    val date = t.text(if (movie) "release_date" else "first_air_date")
    return Meta(JSONObject()
        .put("id", imdb).put("type", if (movie) "movie" else "series")
        .put("name", t.text(if (movie) "title" else "name") ?: t.text("original_title") ?: t.text("original_name") ?: imdb)
        .putOpt("poster", image(t.text("poster_path"), "w342"))
        .putOpt("background", image(t.text("backdrop_path"), "w1280"))
        .putOpt("description", t.text("overview"))
        .putOpt("releaseInfo", date?.take(4))
        .put("genres", org.json.JSONArray(t.optJSONArray("genre_ids")?.let { ids -> (0 until ids.length()).mapNotNull { genreNames[ids.optInt(it)] } }.orEmpty())))
}
