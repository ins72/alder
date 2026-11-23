package dev.alder

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun castChipLabel(member: CastMember): String = member.name

/**
 * Selects the people shown on the title page's compact cast strip: at most one primary director plus lead actors,
 * capped so the strip fits inside the hero column without horizontal scrolling.
 */
internal fun heroCastPreview(allMembers: List<CastMember>, maxCount: Int = 3): List<CastMember> {
    if (allMembers.isEmpty() || maxCount <= 0) return emptyList()
    val directors = allMembers.filter { it.isDirector }
    val actors = allMembers.filterNot { it.isDirector }
    if (actors.isEmpty()) return directors.take(maxCount)
    val ordered = listOfNotNull(directors.firstOrNull()) + actors
    return ordered.take(maxCount)
}

internal fun isEpisodeAired(released: String?, now: Long = System.currentTimeMillis()): Boolean {
    if (released.isNullOrBlank()) return true
    val day = released.take(10).takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return true
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    if (runCatching { source.parse(day) }.getOrNull() == null) return true

    if (released.length >= 19 && released.contains('T')) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val full = runCatching { iso.parse(released.take(19)) }.getOrNull()
        if (full != null && full.time <= now) return true
    }

    val localToday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
    val utcToday = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))
    return day <= localToday || day <= utcToday
}

/** "14 Nov" for an episode that airs later, in the viewer's language; the screen puts it in "Airs …". */
internal fun airDay(released: String?, locale: Locale = Locale.getDefault()): String? {
    val day = released?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

internal fun isRecentlyAired(released: String?, now: Long = System.currentTimeMillis()): Boolean {
    if (!isEpisodeAired(released, now)) return false
    val day = released?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return false
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return false
    val age = now - parsed.time
    return age in 0..(8L * 24 * 60 * 60 * 1000L)
}

internal fun episodeDate(value: String?, locale: Locale = Locale.getDefault()): String? {
    val day = value?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return null
    val source = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
    val parsed = runCatching { source.parse(day) }.getOrNull() ?: return null
    return SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}

// A title stays in place over its art; episodes and related titles open over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonStrip: LinearLayout
    private var seasonButtons = emptyMap<Int, TextView>()
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewFacts: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var similarOverlay: FrameLayout
    private var similarFirst: View? = null
    private lateinit var buttonRow: LinearLayout
    private val castChips = mutableListOf<View>()
    private lateinit var filmographyOverlay: FrameLayout
    private var filmographyOpen = false
    private var filmographyFirst: View? = null
    private var lastFocusedActor: View? = null
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var guideOpen = false
    private var similarOpen = false
    private var infoOpen = false
    private val infoCastCards = mutableListOf<View>()
    private var storyScrollView: ScrollView? = null
    private var richCast: List<CastMember> = emptyList()
    private var castStripSlot: FrameLayout? = null
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private val episodic get() = hasEpisodes(type, meta?.videos)
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List
    private var itemChangedHere = false
    private var savingEdit = false
    private var similarRequested = false
    private var imdbTrailers: List<Stream> = emptyList()
    private var trailerRequested = false
    private var certificate: String? = null
    private var certificateRequested = false
    private var fullDescription: String? = null
    private var overviewRequested = false
    private var descScroll: ScrollView? = null
    private var letterboxdScore: String? = null
    private var letterboxdRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false }
        root = FrameLayout(this).apply {
            clipChildren = false
            // A dark filter over the title art: the logo, facts and description then read over any backdrop, bright or busy.
            addView(View(this@DetailsActivity).apply {
                background = GradientDrawable().apply { setColor(0x66000000.toInt()) }
            }, FrameLayout.LayoutParams(-1, -1))
            addView(hero, FrameLayout.LayoutParams(-1, -1))
            addView(TextClock(this@DetailsActivity).apply {
                format12Hour = "h:mm a"
                format24Hour = "HH:mm"
                textSize = SMALL
                setTextColor(TEXT_2)
                typeface = text("", SMALL).typeface
                if (Handheld.isHandheld(this@DetailsActivity)) visibility = View.GONE // the phone's status bar has the time
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(36); rightMargin = dp(PAD)
            })
            val (loading, loadingText) = loadingOverlay()
            loadingView = loading
            loadingStatus = loadingText
            addView(loadingView, FrameLayout.LayoutParams(-1, -1))
        }
        setWindowed(root, heroArt)

        // A deliberate swipe up anywhere on the page (never on the description scroll or the buttons) opens
        // episodes / "more below" — a second way in besides the label. The listener only runs when no child
        // consumed the touch, so the description ScrollView and the buttons keep their own gestures.
        var downY = 0f
        var fired = false
        hero.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && downY - event.y > dp(120)) { fired = true; openBelow() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fired = false
            }
            true
        }

        // The art arrives: a touch close, settling back as the page opens (a transform only, no redraw of the art).
        (initialArt ?: poster)?.let { Images.load(it, heroArt, artWidth, urgent = true) {
            heroArt.alpha = 1f; heroArt.scaleX = ARRIVE; heroArt.scaleY = ARRIVE
            heroArt.enter(ART_MS).scaleX(1f).scaleY(1f).start()
            refrost()
        } }

        fun show(m: Meta) {
            meta = m
            val art = m.background ?: initialArt ?: poster
            if (heroArt.tag != art) Images.load(art, heroArt, artWidth, urgent = true) {
                heroArt.enter().alpha(1f).start()
                refrost()
            }
            richCast = m.castMembers
            certificate = m.certification ?: certificate
            val wasGuide = guideOpen // fresh details can land while the guide is open: rebuild it open, as onRestart does
            showHero(m)
            if (hasEpisodes(type, m.videos) && m.videos.isNotEmpty()) { buildGuide(m.videos); if (wasGuide) showGuide() }
            if (!hasEpisodes(type, m.videos) && !similarRequested) { similarRequested = true; loadSimilar() }
            loadRichCast()
            if (!overviewRequested && m.id.startsWith("tt")) {
                overviewRequested = true
                background<String?>({ Tmdb.overview(m.id, type) }) { r ->
                    val full = r.getOrNull()
                    val current = (fullDescription ?: m.description).orEmpty()
                    if (!full.isNullOrBlank() && (full.length > current.length || current.trimEnd().endsWith("..."))) {
                        fullDescription = full
                        if (!guideOpen && !similarOpen && !filmographyOpen) showHero(meta ?: m)
                        if (infoOpen) showInfo(focusCast = false)
                    }
                }
            }
            if (!certificateRequested && certificate == null && m.id.startsWith("tt")) {
                certificateRequested = true
                background<String?>({ imdbCertificate(m.id) }) { r ->
                    val resolved = r.getOrNull()
                    if (resolved != null && resolved != certificate) {
                        certificate = resolved
                        if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) showHero(meta)
                    }
                }
            }
            if (!letterboxdRequested && Settings.wantsLetterboxd(this, type, m.id)) {
                letterboxdRequested = true
                letterboxdScore = knownLetterboxd(m.id)
                val key = Settings.mdblistKey(this)
                background({ letterboxd(m.id, key, fresh = true) }) { r ->
                    val score = r.getOrNull()
                    if (score != null && score != letterboxdScore) {
                        letterboxdScore = score
                        if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) showHero(meta)
                    }
                }
            }
            if (!trailerRequested && m.id.startsWith("tt")) {
                trailerRequested = true
                background({ imdbTrailerStreams(m.id) }) { r ->
                    val resolved = r.getOrDefault(emptyList())
                    if (resolved.isNotEmpty()) {
                        val hadTrailer = m.trailerStreams.isNotEmpty() || m.trailerYtId != null || imdbTrailers.isNotEmpty()
                        imdbTrailers = resolved
                        if (!hadTrailer && !guideOpen && !infoOpen && !similarOpen && !filmographyOpen) showHero(meta)
                    }
                }
            }
        }
        background({
            (Stremio.savedAddons(key) ?: Stremio.addons(key)).savedMeta(type, metaId) to Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId }
        }) { result ->
            result.getOrNull()?.let { (saved, libraryItem) ->
                if (!itemChangedHere && item == null) item = libraryItem
                if (meta == null) saved?.let(::show)
            }
        }
        background({ Stremio.addons(key).meta(type, metaId, together = true, reportFailure = true) }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null) show(fresh)
            else if (meta == null) {
                val failure = result.exceptionOrNull()?.let(::failureOf) ?: Failure.MISSING
                FailureLog.record(FailureLog.Operation.DETAILS, failure)
                showHero(null, failure)
            }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            if (!itemChangedHere && !savingEdit) {
                item = result.getOrNull() ?: item
                if (meta != null) showHero(meta)
            }
        }
    }

    // ---------- title and watch window ----------

    private fun showHero(m: Meta?, failure: Failure? = null) {
        hero.removeAllViews()
        descScroll = null
        val title = text(m?.name ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.BOTTOM or Gravity.START
            // The box is a fixed height: at large font sizes a two-line name shrinks to fit instead of clipping.
            // Android 8+ only; older TVs ellipsize the second line instead.
            if (android.os.Build.VERSION.SDK_INT >= 26)
                setAutoSizeTextTypeUniformWithConfiguration(20, SERIF_HERO.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val titleArea = FrameLayout(this).apply {
            addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
            if (m != null) {
                val logo = ImageView(this@DetailsActivity).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    maxWidth = dp(260)
                    maxHeight = dp(75)
                    visibility = View.GONE
                }
                addView(logo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(520), urgent = true, failed = {
                    title.visibility = View.VISIBLE; logo.visibility = View.GONE
                }) {
                    title.visibility = View.GONE; logo.visibility = View.VISIBLE
                }
            }
        }
        val stack = column(Gravity.START).apply { addView(titleArea, LinearLayout.LayoutParams(-1, -2)) }
        if (m == null) {
            val error = failed(failure?.let { failureMessage(R.string.details_load_failed, it) }
                ?: getString(R.string.details_load_failed)) { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(1).requestFocus() }
        } else {
            stack.addView(buildMetadataRow(m, detailed = false), wrap().apply { topMargin = dp(12) })
            val descText = fullDescription ?: m.description
            descText?.takeIf { it.isNotBlank() }?.let { desc ->
                val descW = minOf(dp(470), resources.displayMetrics.widthPixels - dp(2 * PAD))
                // The description scrolls inside a capped box on every screen size (phones and big screens alike): a long
                // synopsis is read by scrolling the text rather than being cut off at a fixed number of lines.
                val story = ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    isVerticalFadingEdgeEnabled = true
                    setFadingEdgeLength(dp(20))
                    isFocusable = true
                    setPadding(0, dp(6), 0, dp(6))
                }
                val descView = text(desc, BODY, color = TEXT).onArt()
                story.addView(descView)
                descView.measure(
                    View.MeasureSpec.makeMeasureSpec(descW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                // Cap the description box at a share of the screen height (like the hero band): a fixed 210 dp is a small
                // slice of a tall phone but a huge slab of a landscape screen, so scale it to keep the same proportion.
                val maxH = (resources.displayMetrics.heightPixels * 0.275f).toInt()
                val h = minOf(descView.measuredHeight + dp(12), maxH)
                descScroll = story
                stack.addView(story, LinearLayout.LayoutParams(descW, h).apply { topMargin = dp(8) })
            }
            val initialCast = richCast.ifEmpty {
                val dirMembers = m.directors.map { CastMember(it, character = getString(R.string.cast_director), role = "director") }
                dirMembers + m.castMembers
            }
            buildCastChips(initialCast)?.let { castStrip ->
                val slot = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(castStrip)
                }
                castStripSlot = slot
                stack.addView(slot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(92)
        })
        val hasEpisodes = hasEpisodes(type, m?.videos) && m?.videos?.isNotEmpty() == true
        if (hasEpisodes || ::similarOverlay.isInitialized) {
            hero.addView(caps(if (hasEpisodes) R.string.details_episodes_below else R.string.details_more_below, TEXT_2).onArt().apply {
                isFocusable = true
                setOnClickListener { openBelow() }
                rowFocus(rest = { null })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(28)
            })
        }
    }

    /** Opens the episodes (or "more below" similar) panel; used by the label, a DPAD-down and a swipe-up. */
    private fun openBelow(): Boolean {
        if (infoOpen || similarOpen || filmographyOpen) return false
        if (episodic) { if (::guide.isInitialized) { showGuide(); return true } }
        else if (::similarOverlay.isInitialized) { showSimilar(); return true }
        return false
    }

    /** A deliberate swipe down on a panel closes it. Scrollable children (episode list, poster rail) consume their own touches first, so they keep scrolling. */
    private fun attachDismiss(view: View, onClose: () -> Unit) {
        var downY = 0f
        var swiped = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; swiped = false }
                MotionEvent.ACTION_MOVE -> if (!swiped && event.y - downY > dp(120)) { swiped = true; onClose() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swiped = false
            }
            true
        }
    }

    /**
     * The episode list. On a phone, dragging down while the list is already at the top closes the panel (an
     * overscroll-to-dismiss); a left/right flick switches season; any other drag scrolls the rows. The touch is
     * tracked in dispatchTouchEvent so the press is seen even though each row is clickable and would otherwise
     * swallow it — the close only arms when the list started the gesture at the top (so scrolling back up never
     * closes it), and a season swipe only fires on a mostly-horizontal flick (so it never fights a vertical scroll).
     */
    private inner class EpisodeScroll(
        context: android.content.Context,
        private val onClose: () -> Unit,
        private val onSeasonStep: (Int) -> Unit,
    ) : ScrollView(context) {
        private var downY = 0f
        private var downX = 0f
        private var armed = false
        private var closed = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y; downX = ev.x; armed = scrollY == 0; closed = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (armed && !closed && ev.y - downY > dp(160)) {
                        closed = true
                        onClose()
                        return true
                    }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!closed && kotlin.math.abs(dx) > dp(90) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5) {
                        onSeasonStep(if (dx < 0) 1 else -1) // swipe left -> next season, right -> previous
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> closed = false
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    /** Moves the episode list to the next (step +1) or previous (step -1) season, wrapping nothing; no-op at an end. */
    private fun changeSeason(step: Int) {
        val seasons = meta?.videos.orEmpty().map { it.season }.distinct()
        val idx = seasons.indexOf(selectedSeason)
        if (idx < 0) return
        val next = (idx + step).coerceIn(0, seasons.size - 1)
        if (next == idx) return
        selectedSeason = seasons[next]
        fillGuide()
        seasonButtons[selectedSeason]?.requestFocus()
    }

    /** Where you are, then the main action and three small glass controls. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        val target = playTarget(m)
        val episode = target?.second
        val resumeAt = item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L
        val videos = m.videos
        val watched = Library.watched(item, videos)
        val airedVideos = videos.filter { it.season > 0 && isEpisodeAired(it.released) }
        val currentVideoId = item?.optJSONObject("state")?.text("video_id")
        val currentVideo = videos.firstOrNull { it.id == currentVideoId }
        val unairedNext = when {
            // Unfinished playback always resumes
            resumeAt > 0 -> null
            // Unpremiered show: first target episode is unaired
            episode != null && !isEpisodeAired(episode.released) -> episode
            // Caught up pointer is unaired
            currentVideo != null && !isEpisodeAired(currentVideo.released) -> currentVideo
            // All aired episodes watched: next in line is unaired
            airedVideos.isNotEmpty() && airedVideos.all { it.id in watched } ->
                videos.firstOrNull { it.season > 0 && !isEpisodeAired(it.released) && it.id !in watched }
            else -> null
        }

        val hasTrailer = m.trailerStreams.isNotEmpty() || m.trailerYtId != null || imdbTrailers.isNotEmpty()

        if (episodic && unairedNext != null) {
            val airDate = airDay(unairedNext.released) ?: getString(R.string.details_airs_soon)
            val caughtUp = getString(if (airedVideos.isEmpty()) R.string.details_premiere else R.string.details_up_to_date,
                unairedNext.season, unairedNext.episode, airDate)
            stack.addView(text(caughtUp, SMALL, bold = true).onArt().apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }.marginTop(16))

            playButton = pill(R.string.action_episodes).apply {
                setOnClickListener { showGuide() }
            }
            val trailerButton = if (hasTrailer) iconButton("", getString(R.string.action_trailer), iconRes = R.drawable.ic_trailer) { playTrailer(m) } else null
            infoButton = iconButton("ⓘ", getString(R.string.action_more_info)) { showInfo() }
            listButton = listButton()
            buttonRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(playButton, wrap().apply { marginEnd = dp(14) })
                trailerButton?.let { addView(it, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)).apply { marginEnd = dp(8) }) }
                addView(listButton, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)).apply { marginEnd = dp(8) })
                addView(infoButton, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)))
            }
            addActions(stack, buttonRow)
            playButton.post { if (!guideOpen && !infoOpen && !similarOpen) playButton.requestFocus() }
            return
        }
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = timeLeft(duration, resumeAt)
        if (resumeAt > 0) {
            val stopped = getString(R.string.stopped_at, android.text.format.DateUtils.formatElapsedTime(resumeAt / 1000))
            val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining, stopped)
            stack.addView(text(where.joinToString(DOT), SMALL, bold = true).onArt().apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }.marginTop(16))
        }
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        playButton = pill(if (resumeAt > 0) R.string.action_resume else R.string.action_play, R.drawable.ic_play).apply {
            setOnClickListener { openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        val trailerButton = if (hasTrailer) iconButton("", getString(R.string.action_trailer), iconRes = R.drawable.ic_trailer) { playTrailer(m) } else null
        val startOver = if (resumeAt > 0) iconButton("↺", getString(R.string.action_start_over)) {
            openSources(target?.first ?: metaId, startOver = true)
        } else null
        infoButton = iconButton("ⓘ", getString(R.string.action_more_info)) { showInfo(focusCast = false) }
        listButton = listButton()
        buttonRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(playButton, wrap().apply { marginEnd = dp(14) })
            trailerButton?.let { addView(it, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)).apply { marginEnd = dp(8) }) }
            startOver?.let { addView(it, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)).apply { marginEnd = dp(8) }) }
            addView(listButton, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)).apply { marginEnd = dp(8) })
            addView(infoButton, LinearLayout.LayoutParams(dp(ICON_BUTTON_H), dp(ICON_BUTTON_H)))
        }
        addActions(stack, buttonRow)
        playButton.post { if (!guideOpen && !infoOpen && !similarOpen && !filmographyOpen) playButton.requestFocus() }
    }

    /**
     * Play and the round buttons beside it. Upright, a phone's width fits only Play and two or three of them, so the
     * round ones take a second line under Play instead of running off the screen's edge.
     */
    private fun addActions(stack: LinearLayout, buttonRow: LinearLayout) {
        if (!Handheld.narrow(this) || buttonRow.childCount <= 3) return stack.addView(buttonRow, wrap().apply { topMargin = dp(16) })
        val icons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        while (buttonRow.childCount > 1) {
            val v = buttonRow.getChildAt(1)
            buttonRow.removeViewAt(1)
            icons.addView(v)
        }
        stack.addView(buttonRow, wrap().apply { topMargin = dp(16) })
        stack.addView(icons, wrap().apply { topMargin = dp(10) })
    }

    private fun buildMetadataRow(m: Meta, detailed: Boolean): LinearLayout {
        val seasonCount = if (hasEpisodes(type, m.videos)) {
            m.videos.map { it.season }.filter { it > 0 }.distinct().size.takeIf { it > 0 }?.let(::seasons)
        } else null
        val genreText = if (detailed) {
            m.genres.take(3).joinToString(" / ").ifEmpty { null }
        } else {
            m.genres.firstOrNull()
        }
        val lengthParts = if (detailed) {
            listOfNotNull(seasonCount, m.runtime)
        } else {
            listOfNotNull(seasonCount ?: m.runtime)
        }
        val facts = (listOfNotNull(m.year, genreText) + lengthParts).joinToString(DOT)
        val ratings = shownRatings(Settings.ratings(this@DetailsActivity), m.rating, letterboxdScore)
        val hasCert = certificate != null
        // A phone stacks the facts above a second line holding the certificate and the ratings together: one wide row
        // pushed the IMDb score off the screen's edge. A TV, with room to spare, keeps the single line.
        if (Handheld.narrow(this)) {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                if (facts.isNotBlank()) addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, wrap())
                if (hasCert || ratings.isNotEmpty()) {
                    val line = LinearLayout(this@DetailsActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                    certificate?.let { cert ->
                        line.addView(text(cert, 9.5f, bold = true, color = TEXT_2).apply {
                            includeFontPadding = false
                            setLineSpacing(0f, 1f)
                            setPadding(dp(5), dp(2), dp(5), dp(2))
                            background = GradientDrawable().apply {
                                cornerRadius = dp(3).toFloat()
                                setStroke(dp(1), GLASS_EDGE)
                            }
                        })
                        if (ratings.isNotEmpty()) line.addView(text(DOT, SMALL, color = TEXT_2).onArt().apply { setPadding(dp(6), 0, dp(6), 0) })
                    }
                    if (ratings.isNotEmpty()) addRatings(line, ratings, tagSp = 9.5f, firstGap = 0)
                    addView(line, wrap().apply { topMargin = dp(6) })
                }
            }
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            certificate?.let { cert ->
                addView(text(cert, 9.5f, bold = true, color = TEXT_2).apply {
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3).toFloat()
                        setStroke(dp(1), GLASS_EDGE)
                    }
                })
                if (facts.isNotBlank()) addView(text(DOT, SMALL, color = TEXT_2).onArt())
            }
            if (facts.isNotBlank()) {
                addView(text(facts, SMALL, color = TEXT_2).onArt().apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }
            if (ratings.isNotEmpty()) {
                if (facts.isNotBlank() || certificate != null) addView(text(DOT, SMALL, color = TEXT_2).onArt())
                addRatings(this, ratings, tagSp = 9.5f, firstGap = 0)
            }
        }
    }

    private fun seasonName(season: Int) = if (season == 0) getString(R.string.season_specials) else getString(R.string.season_number, season)

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        item?.let { entry ->
            val saved = Library.savedVideoId(entry)
            if (Library.resumeAt(entry, saved) > 0 || Library.nextEpisodePending(entry)) {
                return saved to videos.firstOrNull { it.id == saved }
            }
        }
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    private fun listButton(): TextView {
        val inList = Library.inList(item)
        val desc = bt(if (inList) R.string.action_remove_from_list else R.string.action_add_to_list)
        return iconButton(if (inList) "✓" else "+", desc, action = ::toggleList)
    }

    private fun toggleList() {
        val add = !Library.inList(item)
        editLibrary({ Library.setInList(it, add) }, R.string.list_update_failed) {
            listButton.text = if (add) "✓" else "+"
            val desc = bt(if (add) R.string.action_remove_from_list else R.string.action_add_to_list)
            listButton.contentDescription = desc
        }
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14, shade = FROST_DENSE)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
        }
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val x = dp(PAD)
        val top = dp(128)
        val width = resources.displayMetrics.widthPixels - x * 2
        // Use the usable height (the root is inset by the phone's status and navigation bars), so the episode list ends
        // above the navigation bar instead of running under it, where its last row could not be scrolled into view.
        val usableH = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val height = usableH - top - dp(27)
        val largeText = resources.configuration.fontScale > 1.15f
        val narrowGuide = Handheld.narrow(this) // upright: the episode list takes the pane, the preview beside it has no room
        guide = FrameLayout(this).apply { visibility = View.GONE }
        guide.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD90E0C0A.toInt(), 0x800E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(136), Gravity.TOP))
        if (Handheld.isHandheld(this)) guide.addView(closeButton { hideGuide() }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); rightMargin = dp(PAD / 2)
        })
        val smallLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        val logoX = if (narrowGuide) dp(54) else x // upright: right of the back arrow
        guide.addView(smallLogo, FrameLayout.LayoutParams(dp(150), dp(42)).apply {
            leftMargin = logoX; topMargin = dp(27)
        })
        val fallback = text(meta?.name ?: name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE
        }
        guide.addView(fallback, FrameLayout.LayoutParams(dp(250), -2).apply {
            leftMargin = logoX; topMargin = dp(28)
        })
        Images.load(meta?.logo ?: Images.logoFor(metaId), smallLogo, dp(300), urgent = true, failed = {
            smallLogo.visibility = View.GONE; fallback.visibility = View.VISIBLE
        })

        seasonStrip = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        // Room around the chips for the focused one to grow into: flush with the strip, its left edge was cut flat.
        // clipChildren=false lets a focused chip grow past the strip's edge without its top being cut off (phones).
        val narrowStrip = Handheld.narrow(this)
        val room = dp(if (narrowStrip) 10 else 8)
        guide.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            setPadding(room, room, room, room)
            clipToPadding = false
            addView(seasonStrip)
        }, FrameLayout.LayoutParams(width + 2 * room, (if (narrowStrip) dp(CHIP_H) else dp(32)) + 2 * room).apply {
            // A phone's season chips are taller (finger-sized); lift the strip so they still end above the pane.
            leftMargin = x - room; topMargin = dp(88) - room - dp(minOf(8, maxOf(0, CHIP_H - 36)))
        })

        val pane = glassPane()
        // A little space under the last episode, so the list scrolls the final row clear of the pane's fading edge.
        guideRows = column(Gravity.START).apply { setPadding(0, 0, 0, dp(28)) }
        val episodeScroll: ScrollView =
            if (Handheld.isHandheld(this)) EpisodeScroll(this, { hideGuide() }, { changeSeason(it) }) else ScrollView(this)
        episodeScroll.apply {
            isVerticalScrollBarEnabled = false
            // On a TV the row past the panel's foot fades out instead of running into the edge. A phone keeps every row
            // crisp, so the last episode is fully seen when the list is scrolled to its end.
            isVerticalFadingEdgeEnabled = !narrowGuide
            if (!narrowGuide) setFadingEdgeLength(dp(40))
            addView(guideRows)
        }
        pane.addView(episodeScroll, FrameLayout.LayoutParams(if (narrowGuide) width - dp(28) else dp(380), height - dp(28)).apply {
            leftMargin = dp(14); topMargin = dp(14)
        })
        val previewX = dp(414)
        val previewWidth = if (narrowGuide) 1 else width - previewX - dp(14)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(BASE, 8)
            clipToOutline = true
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(200)).apply {
            leftMargin = previewX; topMargin = dp(14)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(20), dp(3)).apply {
            leftMargin = previewX + dp(10); topMargin = dp(203)
        })
        previewTitle = text("", 22f, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(225)
        })
        previewFacts = text("", SMALL, color = TEXT_2).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewFacts, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 259 else 254)
        })
        previewAbout = text("", SMALL, color = TEXT).onArt().apply {
            maxLines = if (largeText) 3 else 5
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 287 else 278)
        })
        if (narrowGuide) listOf<View>(previewImage, previewProgress, previewTitle, previewFacts, previewAbout).forEach { it.visibility = View.GONE }
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x; topMargin = top
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        // The episode panel closes via the episode list's own overscroll-to-dismiss (EpisodeScroll), not by
        // dragging the surrounding backdrop — touching outside the list must not dismiss it.
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasons = videos.map { it.season }.distinct()
        seasonStrip.removeAllViews()
        seasonButtons = seasons.associateWith { season ->
            chip(if (season == selectedSeason) seasonName(season)
                else if (season == 0) getString(R.string.season_specials) else season.toString(), season == selectedSeason).apply {
                setOnClickListener {
                    selectedSeason = season
                    fillGuide()
                    seasonButtons[season]?.requestFocus()
                }
                setOnLongClickListener {
                    seasonMenu(season)
                    true
                }
                seasonStrip.addView(this, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply {
                    if (seasonStrip.childCount > 0) marginStart = dp(8)
                })
            }
        }
        guideRows.removeAllViews()
        // Phone: room at each row's end for a "⋯" button, the touch form of holding the row (kept on every row so the columns line up).
        val moreW = if (Handheld.isHandheld(this)) dp(minOf(Handheld.designDp(this, 40f), 44)) else 0
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = videos.filter { it.season == selectedSeason }.map { video ->
            val row = FrameLayout(this).apply { tag = video.id }
            // Upright phone: a still beside each episode (the mobile pattern), with the title over its status in a column.
            val narrow = Handheld.narrow(this)
            val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
                ?.optLong("duration") ?: 0L
            val aired = isEpisodeAired(video.released)
            val status = when {
                !aired -> airDay(video.released)?.let { getString(R.string.airs_on, it) }.orEmpty()
                video.id in watched -> "✓"
                else -> timeLeft(duration, resumeAt) ?: video.runtime.orEmpty()
            }
            val titleColor = when {
                !aired -> TEXT_3
                video.id in watched -> TEXT_2
                else -> TEXT
            }
            val titleView = text(video.title.ifBlank { getString(R.string.episode_number, video.episode) }, BODY, color = titleColor).apply {
                maxLines = if (narrow) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            }
            val statusView = text(status, SMALL,
                color = if (!aired) TEXT_3 else if (video.id in watched) TUNGSTEN else TEXT_2)
            if (narrow) {
                val thumbW = dp(112)
                val rowH = dp(74)
                val dotsW = moreW
                row.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(BASE, 6)
                    clipToOutline = true
                    val blur = Settings.blurThumbnails(this@DetailsActivity) && video.id !in watched
                    Images.load(video.thumbnail ?: meta?.background,
                        this, thumbW, orElse = meta?.background, look = if (blur) Images.blurred else null)
                }, FrameLayout.LayoutParams(thumbW, thumbW * 9 / 16, Gravity.CENTER_VERTICAL).apply {
                    leftMargin = dp(12)
                })
                // The episode number as a badge on the still's corner, so the list keeps its numbers on a phone.
                row.addView(text(video.episode.toString(), SMALL, bold = true, color = TEXT).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    background = rounded(0xE6000000.toInt(), 5)
                }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    leftMargin = dp(16); topMargin = dp(4)
                })
                val textLeft = dp(12) + thumbW + dp(12)
                val textCol = column(Gravity.START).apply {
                    addView(titleView, LinearLayout.LayoutParams(-1, -2))
                    if (status.isNotEmpty()) addView(statusView.marginTop(2), LinearLayout.LayoutParams(-1, -2))
                }
                val textW = resources.displayMetrics.widthPixels - dp(2 * PAD + 28) - textLeft - dp(10) - dotsW
                row.addView(textCol, FrameLayout.LayoutParams(textW, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = textLeft })
                row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
                if (aired) {
                    row.setOnClickListener {
                        val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                        openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
                    }
                    row.setOnLongClickListener { episodeMenu(video); true }
                    if (dotsW > 0) row.addView(MoreDots(this).apply {
                        contentDescription = getString(R.string.card_more)
                        setOnClickListener { episodeMenu(video) }
                    }, FrameLayout.LayoutParams(dotsW, dotsW, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(2) })
                }
                guideRows.addView(row, LinearLayout.LayoutParams(-1, rowH))
                return@map row
            }
            val thumbW = dp(76)
            val rowH = dp(54)
            row.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(BASE, 6)
                clipToOutline = true
                val blur = Settings.blurThumbnails(this@DetailsActivity) && video.id !in watched
                Images.load(video.thumbnail ?: meta?.background,
                    this, thumbW, orElse = meta?.background, look = if (blur) Images.blurred else null)
            }, FrameLayout.LayoutParams(thumbW, thumbW * 9 / 16, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(10)
            })
            // The episode number as a badge on the still's corner, so the list keeps its numbers on a big screen too.
            row.addView(text(video.episode.toString(), SMALL, bold = true, color = TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = rounded(0xE6000000.toInt(), 5)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(14); topMargin = dp(4)
            })
            val textLeft = dp(10) + thumbW + dp(10)
            row.addView(titleView, FrameLayout.LayoutParams(dp(380) - textLeft - dp(110) - moreW, -2, Gravity.CENTER_VERTICAL).apply { leftMargin = textLeft })
            if (status.isNotEmpty()) row.addView(statusView.apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(98), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
                    rightMargin = dp(12) + moreW
                })
            row.rowFocus(9) { focused -> if (focused) updatePreview(video) }
            if (aired) {
                row.setOnClickListener {
                    val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                    openSources(video.id, startOver = false, next = next, label = "S${video.season} E${video.episode}")
                }
                row.setOnLongClickListener {
                    episodeMenu(video)
                    true
                }
                if (moreW > 0) row.addView(MoreDots(this).apply {
                    contentDescription = getString(R.string.card_more)
                    setOnClickListener { episodeMenu(video) }
                }, FrameLayout.LayoutParams(moreW, moreW, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(2) })
            }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, rowH))
            row
        }
        videos.firstOrNull { it.id == currentId && it.season == selectedSeason }?.let(::updatePreview)
            ?: videos.firstOrNull { it.season == selectedSeason }?.let(::updatePreview)
    }

    private fun updatePreview(video: Video) {
        val blur = Settings.blurThumbnails(this) && video.id !in Library.watched(item, meta?.videos.orEmpty())
        Images.load(video.thumbnail, previewImage, dp(870), orElse = meta?.background, look = if (blur) Images.blurred else null)
        previewTitle.text = video.title.ifBlank { getString(R.string.episode_number, video.episode) }
        previewFacts.text = listOfNotNull("S${video.season} E${video.episode}", video.runtime ?: meta?.runtime,
            episodeDate(video.released)).joinToString(DOT)
        previewAbout.text = video.overview.orEmpty()
        previewAbout.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == video.id }
        val resumeAt = item?.let { Library.resumeAt(it, video.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration),
                FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.enter().alpha(1f).translationY(0f).start()
        // The film steps back behind the guide, so the chips and panel read over a bright backdrop.
        heroArt.animate().alpha(GUIDE_ART).setDuration(ENTER_MS).withEndAction { refrost() }.start()
        leftShade(false) // the episode list sits on glass: the art can run to the left edge as it does on the right
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        heroArt.enter().alpha(1f).withEndAction { refrost() }.start()
        leftShade(true)
        guide.exit().alpha(0f).translationY(dp(24).toFloat()).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            if (::playButton.isInitialized) playButton.requestFocus()
        }.start()
    }

    private fun editLibrary(change: (JSONObject) -> Unit, title: Int = R.string.library_edit_failed,
                            previous: Failure? = null, done: () -> Unit) {
        if (savingEdit) return
        savingEdit = true
        val entry = Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        background({
            Stremio.editLibrary(key, metaId, entry, change)
        }) { result ->
            savingEdit = false
            if (isFinishing) return@background
            result.onSuccess {
                previous?.let { FailureLog.record(FailureLog.Operation.LIBRARY_EDIT, it, recovered = true) }
                itemChangedHere = true
                item = it
                done()
            }.onFailure {
                val failure = failureOf(it)
                FailureLog.record(FailureLog.Operation.LIBRARY_EDIT, failure)
                choose(bt(title), listOf(getString(R.string.action_try_again), getString(R.string.action_cancel)),
                    getString(failure.message)) { choice -> if (choice == 0) editLibrary(change, title, failure, done) }
            }
        }
    }

    private fun refreshAfterEdit(focusEpisodeId: String? = null, focusSeason: Int? = null) {
        fillGuide()
        meta?.let(::showHero)
        if (focusEpisodeId != null) {
            val target = (0 until guideRows.childCount).map { guideRows.getChildAt(it) }
                .firstOrNull { it.tag == focusEpisodeId }
            target?.requestFocus()
            meta?.videos?.firstOrNull { it.id == focusEpisodeId }?.let(::updatePreview)
        } else if (focusSeason != null) {
            seasonButtons[focusSeason]?.requestFocus()
        }
    }

    private fun episodeMenu(video: Video) {
        val videos = meta?.videos.orEmpty()
        val watched = Library.watched(item, videos)
        val isWatched = video.id in watched
        val prior = Library.priorEpisodes(videos, video)

        val options = mutableListOf(R.string.action_choose_source, if (isWatched) R.string.action_mark_unwatched else R.string.action_mark_watched)
        if (prior.isNotEmpty()) {
            options.add(R.string.action_mark_previous_watched)
        }

        val epNumber = if (video.season > 0) "S${video.season} E${video.episode}" else "E${video.episode}"
        val title = video.title.ifBlank { epNumber }
        val message = if (video.title.isNotBlank()) epNumber else null

        choose(title, options.map(::getString), message = message) { selected ->
            when (options[selected]) {
                R.string.action_choose_source -> {
                    val next = videos.getOrNull(videos.indexOf(video) + 1)?.id
                    openSources(video.id, startOver = false, next = next,
                        label = "S${video.season} E${video.episode}", pick = true)
                }
                R.string.action_mark_watched -> markEpisodeWatched(video, true)
                R.string.action_mark_unwatched -> markEpisodeWatched(video, false)
                R.string.action_mark_previous_watched -> markPreviousEpisodesWatched(video, prior)
            }
        }
    }

    private fun markEpisodeWatched(video: Video, watched: Boolean) {
        val videos = meta?.videos.orEmpty()
        editLibrary({ entry ->
            val s = entry.state
            if (watched) {
                Library.markWatched(entry, videos, video.id)
                s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
                val nextEp = Library.nextEpisode(videos, video)
                if (nextEp != null) {
                    s.put("video_id", nextEp.id)
                    s.put("timeOffset", 0L)
                    s.put("flaggedWatched", 0)
                } else {
                    s.put("video_id", video.id)
                    s.put("timeOffset", 0L)
                    s.put("flaggedWatched", 1)
                }
            } else {
                Library.markUnwatched(entry, videos, video.id)
                s.put("video_id", video.id)
                s.put("timeOffset", 0L)
                s.put("flaggedWatched", 0)
            }
            entry.put("_mtime", Library.now())
        }) { refreshAfterEdit(focusEpisodeId = video.id) }
    }

    private fun markPreviousEpisodesWatched(video: Video, prior: List<Video>) {
        val videos = meta?.videos.orEmpty()
        editLibrary({ entry ->
            val s = entry.state
            Library.markEpisodesWatched(entry, videos, prior.map { it.id })
            s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
            s.put("video_id", video.id)
            s.put("timeOffset", 0L)
            s.put("flaggedWatched", 0)
            entry.put("_mtime", Library.now())
        }) { refreshAfterEdit(focusEpisodeId = video.id) }
    }

    private fun seasonMenu(season: Int) {
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == season }
        if (seasonVideos.isEmpty()) return
        val watched = Library.watched(item, videos)
        val allWatched = seasonVideos.all { it.id in watched }
        val title = seasonName(season)
        val option = getString(if (allWatched) R.string.mark_season_unwatched else R.string.mark_season_watched, title)
        choose(title, listOf(option)) { selected ->
            if (selected == 0) {
                markSeasonWatched(season, !allWatched)
            }
        }
    }

    private fun markSeasonWatched(season: Int, watched: Boolean) {
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == season }
        val seasonIds = seasonVideos.map { it.id }.toSet()
        editLibrary({ entry ->
            val s = entry.state

            if (watched) {
                Library.markEpisodesWatched(entry, videos, seasonIds)
                s.put("timesWatched", maxOf(1, s.optInt("timesWatched")))
                val nextEp = Library.nextEpisodeAfterSeason(videos, season)
                if (nextEp != null) {
                    s.put("video_id", nextEp.id)
                    s.put("timeOffset", 0L)
                    s.put("flaggedWatched", 0)
                } else {
                    seasonVideos.lastOrNull()?.let { s.put("video_id", it.id) }
                    s.put("timeOffset", 0L)
                    s.put("flaggedWatched", 1)
                }
            } else {
                Library.unmarkEpisodesWatched(entry, videos, seasonIds)
                seasonVideos.firstOrNull()?.let { firstEp ->
                    s.put("video_id", firstEp.id)
                    s.put("timeOffset", 0L)
                    s.put("flaggedWatched", 0)
                }
            }
            entry.put("_mtime", Library.now())
        }) { refreshAfterEdit(focusSeason = season) }
    }

    /**
     * The whole story and complete cast, with circular portraits above actor and character names.
     * Remote navigates between the story text and cast cards, with OK on any actor revealing their filmography.
     */
    private fun showInfo(focusCast: Boolean = false) {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoCastCards.clear()
        storyScrollView = null

        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xEB0E0C0A.toInt(), 0xD90E0C0A.toInt(), 0x990E0C0A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val content = column(Gravity.START).apply {
            clipChildren = false
            clipToPadding = false
        }

        content.addView(text(m.name, SERIF_TITLE, bold = true, serif = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        content.addView(buildMetadataRow(m, detailed = true), wrap().apply { topMargin = dp(8) })

        (fullDescription ?: m.description)?.takeIf { it.isNotBlank() }?.let { desc ->
            content.addView(caps(R.string.details_the_story, TEXT_2).onArt().marginTop(14))
            val story = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(20))
                isFocusable = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) rounded(ROW_FOCUSED, 8) else null
                }
                addView(text(desc, BODY, color = TEXT).onArt())
            }
            storyScrollView = story
            content.addView(story, LinearLayout.LayoutParams(dp(HERO_W), if (Handheld.narrow(this)) dp(210) else dp(110)).apply {
                topMargin = dp(6)
                leftMargin = -dp(8)
            })
        }

        val fullCast = richCast.ifEmpty {
            val directors = m.directors.map { CastMember(it, getString(R.string.cast_director), null, role = "director") }
            val directorNames = directors.map { it.name.lowercase() }.toSet()
            directors + m.castMembers.filter { it.name.lowercase() !in directorNames }
        }
        if (fullCast.isNotEmpty()) {
            content.addView(caps(R.string.details_cast, TEXT_2).onArt().marginTop(14))
            val castRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }

            val avatarSize = dp(56)

            fullCast.forEachIndexed { index, actor ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }

                val avatarContainer = FrameLayout(this).apply {
                    clipChildren = false
                    alpha = 0.65f
                }

                val avatarFrame = FrameLayout(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x33F3D9B1.toInt())
                    }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setOval(0, 0, view.width, view.height)
                    }
                }
                val initials = text(actor.initials, 15f, bold = true, color = 0xFFF3D9B1.toInt(), serif = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                avatarFrame.addView(initials, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initials.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initials.visibility = View.GONE
                    }
                }
                avatarContainer.addView(avatarFrame, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER))

                card.addView(avatarContainer, wrap())

                val nameView = text(actor.name, SMALL, bold = true, color = TEXT_2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                card.addView(nameView, LinearLayout.LayoutParams(dp(96), -2).apply { topMargin = dp(8) })

                val role = if (actor.isDirector) getString(R.string.cast_director) else actor.character
                val charView = if (!role.isNullOrBlank()) {
                    text(role, 11f, color = TEXT_3).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                } else null
                charView?.let { card.addView(it, LinearLayout.LayoutParams(dp(96), -2).apply { topMargin = dp(2) }) }

                card.setOnFocusChangeListener { v, focused ->
                    v.background = if (focused) rounded(ROW_FOCUSED, 10) else null
                    avatarContainer.grow(focused)
                        .translationZ(if (focused) dp(CARD_LIFT).toFloat() else 0f)
                        .alpha(if (focused) 1f else 0.65f)
                        .start()
                    nameView.setTextColor(if (focused) TEXT else TEXT_2)
                    charView?.setTextColor(if (focused) TEXT_2 else TEXT_3)
                }

                card.setOnClickListener {
                    showFilmography(actor, card)
                }

                infoCastCards.add(card)
                castRow.addView(card, LinearLayout.LayoutParams(-2, -2).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }

            val room = dp(8)
            val castScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(32))
                clipToPadding = false
                setPadding(room, room, room, room)
                addView(castRow)
            }
            content.addView(castScroll, LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = -room
                rightMargin = -room
                topMargin = dp(6) - room
                bottomMargin = -room
            })
        }

        infoOverlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(34)
        })
        if (Handheld.isHandheld(this)) infoOverlay.addView(closeButton { hideInfo() }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); rightMargin = dp(PAD / 2) // the back arrow keeps the other corner
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        infoOverlay.alpha = 0f
        infoOverlay.translationY = dp(20).toFloat()
        infoOverlay.enter().alpha(1f).translationY(0f).start()

        val story = storyScrollView
        if (focusCast && infoCastCards.isNotEmpty()) {
            infoCastCards.first().requestFocus()
        } else if (story != null) {
            story.post {
                val canScroll = story.canScrollVertically(1) || story.canScrollVertically(-1)
                if (canScroll || infoCastCards.isEmpty()) {
                    story.isFocusable = true
                } else {
                    story.isFocusable = false
                    infoCastCards.first().requestFocus()
                }
            }
            story.requestFocus()
        } else {
            infoCastCards.firstOrNull()?.requestFocus()
        }
    }

    private fun hideInfo() {
        infoOpen = false
        if (::infoOverlay.isInitialized) {
            val closing = infoOverlay
            closing.exit().alpha(0f).translationY(dp(20).toFloat()).withEndAction {
                root.removeView(closing)
                if (!infoOpen) {
                    hero.visibility = View.VISIBLE
                    infoButton.requestFocus()
                }
            }.start()
        } else {
            hero.visibility = View.VISIBLE
            infoButton.requestFocus()
        }
    }

    private fun loadSimilar() {
        background({
            val fromAddons = Stremio.addons(key).firstNotNullOfOrNull { addon ->
                runCatching { addon.similar(type, metaId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            fromAddons.ifEmpty { Tmdb.similar(metaId, type) }
        }) { result ->
            result.getOrNull()?.let { titles ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar(getString(R.string.details_more_like_this), it) }
            }
        }
    }

    /** Shared poster-row overlay for "More like this" and cast filmography. */
    private fun buildPosterRowOverlay(heading: String, titles: List<Meta>, onClose: () -> Unit, onFirstCard: (View?) -> Unit): FrameLayout {
        val overlay = FrameLayout(this).apply { visibility = View.GONE }
        overlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xA60E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(280), Gravity.BOTTOM))
        val content = column(Gravity.START)
        val captionPill = caption("").apply {
            visibility = View.INVISIBLE
            maxWidth = dp(360)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caps(heading).onArt())
            addView(captionPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            if (Handheld.isHandheld(this@DetailsActivity)) { // no Back key: a close button at the row's end
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(closeButton(onClose), LinearLayout.LayoutParams(dp(44), dp(44)))
            }
        }
        content.addView(headerRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        onFirstCard(addPosterRail(content, titles, captionPill))
        overlay.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(38)
        })
        if (Handheld.isHandheld(this)) attachDismiss(overlay) { onClose() } // tap the backdrop or swipe down closes it
        return overlay
    }

    /** Movies reveal one Home-sized poster row without moving the title off the screen. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        if (::similarOverlay.isInitialized) root.removeView(similarOverlay)
        similarOverlay = buildPosterRowOverlay(heading, titles, { hideSimilar() }) { similarFirst = it }
        root.addView(similarOverlay, FrameLayout.LayoutParams(-1, -1))
        showHero(meta)
    }

    private fun addPosterRail(content: LinearLayout, titles: List<Meta>, captionPill: TextView): View? {
        val room = dp(8)
        val rail = HorizontalCardRail(
            screen = this,
            cardGapPx = dp(CARD_GAP),
            paddingStartPx = room,
            paddingTopPx = room,
            paddingEndPx = room,
            paddingBottomPx = room,
            cullLeft = false,
        )
        var firstCard: View? = null
        titles.forEachIndexed { index, title ->
            val card = MediaCardView(this, title.name, title.poster, title.background, fadeIn = false).apply {
                dim(true, animate = false)
                setOnFocusChanged { has ->
                    dim(!has)
                    if (has) {
                        rail.scrollToCard(index)
                        val yearStr = title.year?.take(4)?.let { "  ·  $it" } ?: ""
                        captionPill.text = "${title.name}$yearStr"
                        captionPill.visibility = View.VISIBLE
                    }
                }
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                load()
            }
            if (firstCard == null) firstCard = card
            rail.appendCard(card)
        }
        content.addView(rail.scroller, LinearLayout.LayoutParams(-1, dp(POSTER_H) + 2 * room).apply {
            leftMargin = -room
            rightMargin = -room
            topMargin = -room
            bottomMargin = -room
        })
        return firstCard
    }

    private fun showSimilar() {
        if (!::similarOverlay.isInitialized || similarOpen) return
        similarOpen = true
        hero.visibility = View.INVISIBLE
        similarOverlay.visibility = View.VISIBLE
        similarOverlay.alpha = 0f
        similarOverlay.translationY = dp(20).toFloat()
        similarOverlay.enter().alpha(1f).translationY(0f).start()
        similarFirst?.requestFocus()
    }

    private fun hideSimilar() {
        similarOpen = false
        similarOverlay.exit().alpha(0f).translationY(dp(20).toFloat()).withEndAction {
            similarOverlay.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    private fun loadRichCast() {
        background({
            Tmdb.cast(metaId, type)
        }) { result ->
            val enhanced = result.getOrNull().orEmpty()
            if (enhanced.isNotEmpty()) {
                richCast = enhanced
                castStripSlot?.let { slot ->
                    slot.removeAllViews()
                    buildCastChips(richCast)?.let { slot.addView(it) }
                }
                if (infoOpen) {
                    showInfo(focusCast = currentFocus in infoCastCards)
                }
            }
        }
    }

    private fun buildCastChips(allMembers: List<CastMember>): View? {
        val members = heroCastPreview(
            allMembers,
            maxCount = if (allMembers.size <= 4 && allMembers.sumOf { it.name.length } <= 52) 4 else 3
        )
        if (members.isEmpty()) return null
        castChips.clear()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        members.forEachIndexed { index, actor ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                minimumHeight = dp(28)
                setPadding(dp(4), dp(4), dp(11), dp(4))
                val rest = glassOver(this, 14, shade = FROST_DENSE)
                background = rest

                val avatarSize = dp(20)
                val avatarFrame = FrameLayout(context).apply {
                    background = rounded(0x33F3D9B1.toInt(), 10)
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                val initialsText = text(actor.initials, 9f, bold = true, color = TUNGSTEN, serif = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                }
                avatarFrame.addView(initialsText, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

                if (!actor.photo.isNullOrBlank()) {
                    val photoView = ImageView(this@DetailsActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    avatarFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
                    Images.load(actor.photo, photoView, avatarSize, failed = {
                        photoView.visibility = View.GONE
                        initialsText.visibility = View.VISIBLE
                    }) {
                        photoView.visibility = View.VISIBLE
                        initialsText.visibility = View.GONE
                    }
                }
                addView(avatarFrame, LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(7) })

                val nameView = text(castChipLabel(actor), SMALL, color = TEXT_2).apply {
                    includeFontPadding = false
                    maxLines = 1
                }
                addView(nameView, wrap())

                setOnFocusChangeListener { v, has ->
                    v.background = if (has) glass(14, true) else rest
                    v.grow(has, CHIP_GROW).start()
                    nameView.setTextColor(if (has) TEXT else TEXT_2)
                }
                setOnClickListener { showFilmography(actor, this) }
            }
            castChips.add(chip)
            container.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (index > 0) marginStart = dp(8)
            })
        }

        val totalCastCount = (richCast.ifEmpty { allMembers }).size
        val remainingCount = totalCastCount - members.size
        if (remainingCount > 0) {
            val moreChip = text(getString(R.string.cast_more, remainingCount), SMALL, color = TEXT_2).apply {
                gravity = Gravity.CENTER
                setSingleLine()
                includeFontPadding = false
                minHeight = dp(28)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                val rest = glassOver(this, 14, shade = FROST_DENSE)
                background = rest
                isFocusable = true
                setOnFocusChangeListener { v, has ->
                    v.background = if (has) glass(14, true) else rest
                    setTextColor(if (has) TEXT else TEXT_2)
                    v.grow(has, CHIP_GROW).start()
                }
                setOnClickListener { showInfo(focusCast = true) }
            }
            castChips.add(moreChip)
            container.addView(moreChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }

        val room = dp(4)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            setPadding(room, room, room, room)
            addView(container)
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                leftMargin = -room
                rightMargin = -room
                topMargin = -room
                bottomMargin = -room
            }
        }
    }

    private fun showFilmography(actor: CastMember, originView: View) {
        if (resolvingStreams != null) return
        lastFocusedActor = originView
        var canceled = false
        resolvingStreams = object : Cancellable { override fun cancel() { canceled = true } }
        loadingStatus.text = getString(R.string.filmography_loading)
        loadingView.visibility = View.VISIBLE
        loadingView.bringToFront()
        background({
            val creditedTitles = runCatching {
                Tmdb.personCredits(actor.tmdbId, actor.name, actor.isDirector)
            }.getOrDefault(emptySet())

            val searches = Stremio.addons(key).map { addon -> runCatching { addon.search(actor.name) } }
            // Every add-on failed: the viewer hears why, not an empty list.
            searches.takeIf { it.isNotEmpty() && it.all { r -> r.isFailure } }?.let { throw it.first().exceptionOrNull()!! }
            val allMetas = searches.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.id }.filter { it.id != metaId }

            val actorLower = actor.name.lowercase().trim()

            allMetas.sortedWith(compareByDescending<Meta> { meta ->
                val metaTitleLower = meta.name.lowercase().trim()
                var score = 0
                if (metaTitleLower in creditedTitles || creditedTitles.any { it.contains(metaTitleLower) || metaTitleLower.contains(it) }) {
                    score += 100
                }
                if (actor.isDirector) {
                    if (meta.directors.any { it.lowercase().contains(actorLower) || actorLower.contains(it.lowercase()) }) {
                        score += 80
                    }
                } else {
                    if (meta.cast.any { it.lowercase().contains(actorLower) || actorLower.contains(it.lowercase()) }) {
                        score += 80
                    }
                }
                if (score == 0 && metaTitleLower.contains(actorLower)) {
                    score -= 50
                }
                score
            }.thenByDescending { it.year ?: "" }).filter { meta ->
                val metaTitleLower = meta.name.lowercase().trim()
                val isCredited = metaTitleLower in creditedTitles || creditedTitles.any { it.contains(metaTitleLower) || metaTitleLower.contains(it) }
                    || (if (actor.isDirector) meta.directors.any { it.lowercase().contains(actorLower) } else meta.cast.any { it.lowercase().contains(actorLower) })
                isCredited || allMetas.size <= 4 || !metaTitleLower.contains(actorLower)
            }.take(16)
        }) { result ->
            if (canceled) return@background
            cancelStreamResolution()
            result.exceptionOrNull()?.let { error ->
                val reason = failureOf(error)
                FailureLog.record(FailureLog.Operation.SEARCH, reason)
                choose(actor.name, listOf(getString(R.string.action_ok)),
                    failureMessage(R.string.filmography_failed, reason, advice = false)) { originView.requestFocus() }
                return@background
            }
            val titles = result.getOrNull().orEmpty()
            if (titles.isEmpty()) {
                choose(actor.name, listOf(getString(R.string.action_ok)),
                    getString(R.string.filmography_empty)) { originView.requestFocus() }
                return@background
            }
            val heading = if (actor.isDirector) getString(R.string.directed_by, actor.name) else actor.name
            buildFilmographyOverlay(heading, titles)
            openFilmography()
        }
    }

    private fun buildFilmographyOverlay(heading: String, titles: List<Meta>) {
        if (::filmographyOverlay.isInitialized) root.removeView(filmographyOverlay)
        filmographyOverlay = buildPosterRowOverlay(heading, titles, { hideFilmography() }) { filmographyFirst = it }
        root.addView(filmographyOverlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openFilmography() {
        if (!::filmographyOverlay.isInitialized || filmographyOpen) return
        filmographyOpen = true
        if (infoOpen) {
            infoOverlay.visibility = View.INVISIBLE
        } else {
            hero.visibility = View.INVISIBLE
        }
        filmographyOverlay.visibility = View.VISIBLE
        filmographyOverlay.alpha = 0f
        filmographyOverlay.translationY = dp(20).toFloat()
        filmographyOverlay.enter().alpha(1f).translationY(0f).start()
        filmographyFirst?.requestFocus()
    }

    private fun hideFilmography() {
        if (!filmographyOpen) return
        filmographyOpen = false
        filmographyOverlay.exit().alpha(0f).translationY(dp(20).toFloat()).withEndAction {
            filmographyOverlay.visibility = View.GONE
            if (infoOpen) {
                infoOverlay.visibility = View.VISIBLE
            } else {
                hero.visibility = View.VISIBLE
            }
            (lastFocusedActor ?: if (infoOpen) infoOverlay else playButton).requestFocus()
        }.start()
    }

    // ---------- shared ----------

    private var resolvingStreams: Cancellable? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingStatus: TextView

    private fun cancelStreamResolution() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        if (::loadingView.isInitialized) loadingView.visibility = View.GONE
    }

    private fun playTrailer(m: Meta) {
        if (resolvingStreams != null) return
        val ready = m.trailerStreams + imdbTrailers
        if (ready.isNotEmpty()) {
            startTrailerPlayer(m, ready)
            return
        }
        if (!m.id.startsWith("tt")) {
            openExternalTrailer(m.trailerYtId)
            return
        }
        var canceled = false
        resolvingStreams = object : Cancellable { override fun cancel() { canceled = true } }
        loadingStatus.text = getString(R.string.loading_trailer)
        loadingView.visibility = View.VISIBLE
        loadingView.bringToFront()
        background({ imdbTrailerStreams(m.id) }) { result ->
            if (canceled) return@background
            cancelStreamResolution()
            val streams = result.getOrDefault(emptyList())
            if (streams.isNotEmpty()) {
                imdbTrailers = streams
                startTrailerPlayer(m, streams)
            } else {
                openExternalTrailer(m.trailerYtId)
            }
        }
    }

    private fun startTrailerPlayer(m: Meta, streams: List<Stream>) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("type", type)
            .putExtra("id", metaId)
            .putExtra("videoId", "$metaId:trailer")
            .putExtra("name", m.name.ifBlank { name })
            .putExtra("poster", m.poster ?: poster)
            .putExtra("background", m.background)
            .putExtra("logo", m.logo)
            .putExtra("isTrailer", true)
            .putExtra("trailerYtId", m.trailerYtId)
            .putExtra("sources", org.json.JSONArray(streams.map { it.toJson() }).toString()))
    }

    private fun openExternalTrailer(ytId: String?) {
        val id = ytId?.takeIf { it.isNotBlank() } ?: return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id")))
        }.isSuccess
        if (!opened && runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")))
        }.isFailure) choose(getString(R.string.action_trailer), listOf(getString(R.string.action_ok)), getString(R.string.trailer_no_app)) {}
    }

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        if (resolvingStreams != null) return
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        val resumeAt = if (startOver) 0L else item?.let { Library.resumeAt(it, videoId) } ?: 0L
        val intent = Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail).putExtra("runtime", m?.runtime)
            .putExtra("blurStill", Settings.blurThumbnails(this) && video != null && video.id !in Library.watched(item, m?.videos.orEmpty()))
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") })
            .putExtra("resumeAt", resumeAt)

        if (pick || Settings.alwaysList(this)) {
            startActivity(intent)
            return
        }

        startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent))
    }

    override fun onPause() {
        cancelStreamResolution()
        super.onPause()
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull()
            ?: Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { result ->
            item = result.getOrNull() ?: item
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false; storyScrollView = null }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { episodic && it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resolvingStreams != null) {
                cancelStreamResolution()
                return true
            }
            if (guideOpen) {
                val focused = currentFocus
                if (focused in seasonButtons.values) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    // Up from the first row (the season buttons) closes the guide, mirroring the Down that opened it.
                    KeyEvent.KEYCODE_DPAD_UP -> { hideGuide(); return true }
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) {
                        // First episode: step up to the season buttons if there are any, otherwise (single season)
                        // close the guide.
                        val season = seasonButtons[selectedSeason]
                        if (season != null) { season.requestFocus(); return true }
                        hideGuide(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideFilmography()
                return true
            } else if (similarOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideSimilar()
                return true
            } else if (infoOpen && !filmographyOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (currentFocus === storyScrollView && infoCastCards.isNotEmpty()) {
                        infoCastCards.first().requestFocus()
                        return true
                    }
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (currentFocus?.let { v -> infoCastCards.any { it === v || isInside(v, it) } } == true &&
                        storyScrollView?.isFocusable == true) {
                        storyScrollView?.requestFocus()
                        return true
                    }
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen && currentFocus?.let { v -> castChips.any { it === v || isInside(v, it) } } == true) {
                val chipIndex = castChips.indexOfFirst { it === currentFocus || isInside(currentFocus!!, it) }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { playButton.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (chipIndex == 0) return true
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (chipIndex == castChips.lastIndex) return true
                }
            } else if (!infoOpen && !similarOpen && !filmographyOpen &&
                currentFocus?.let { (::playButton.isInitialized && it === playButton) || (::buttonRow.isInitialized && isInside(it, buttonRow)) } == true) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    castChips.firstOrNull()?.requestFocus()
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && ::playButton.isInitialized && currentFocus === playButton) {
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && ::buttonRow.isInitialized &&
                    currentFocus === (0 until buttonRow.childCount).map { buttonRow.getChildAt(it) }.lastOrNull { it.visibility == View.VISIBLE && it.isFocusable }) {
                    return true
                }
            }
            if (!infoOpen && !similarOpen && !filmographyOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                currentFocus?.let { isInside(it, hero) } == true &&
                (if (episodic) ::guide.isInitialized else ::similarOverlay.isInitialized)) {
                if (episodic) showGuide() else showSimilar()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            filmographyOpen -> hideFilmography()
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            similarOpen -> hideSimilar()
            else -> super.onBackPressed()
        }
    }

    private companion object {
        const val GUIDE_ART = 0.45f // the film's brightness behind the episode guide
    }
}
