package dev.alder

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import android.os.Looper
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun lowerSourceHeight(height: Int): Int? = listOf(1080, 720, 480).firstOrNull { it < height }
internal fun upNextWindow(durationMs: Long) = minOf(25_000L, maxOf(15_000L, durationMs / 60))
internal fun plausibleEpisode(durationMs: Long) = durationMs >= 2 * 60_000L

// Fall-asleep protection: episodes that end in a row with no remote key pressed are counted, and the next one waits
// behind "Still watching?" instead of streaming all night (debrid traffic, and Stremio's watched ticks).
internal const val QUIET_EPISODES = 3
internal const val SLEEP_TIMER = 60 * 60_000L
/** [endedQuietly] episodes in a row, this one included, ended with no key pressed: ask before the next one. */
internal fun askStillWatching(endedQuietly: Int) = endedQuietly >= QUIET_EPISODES
/** The Sleep row's value in Audio & subtitles: "Off", "After this episode" or "In 42 min". The screen passes its words. */
internal fun sleepLabel(stopAfter: Boolean, sleepAt: Long, now: Long,
                        afterEpisode: String = "After this episode", inMinutes: String = "In %1\$d min", off: String = "Off") = when {
    stopAfter -> afterEpisode
    sleepAt > now -> inMinutes.format(((sleepAt - now + 59_999) / 60_000).toInt())
    else -> off
}

internal fun formatTiming(delay: Long, onTime: String = "On time",
                          earlier: String = "%1\$+.2f s  earlier", later: String = "%1\$+.2f s  later") = when {
    delay == 0L -> onTime
    delay < 0L -> earlier.format(delay / 1000f)
    else -> later.format(delay / 1000f)
}

/** Holds track data for display and smart pinning in the Audio & Subtitles overlay. */
internal data class TrackChoice(
    val id: String,
    val name: String,
    val tag: String = "",
    val langCode: String? = null,
    val isSelected: Boolean = false,
    val isOff: Boolean = false
)

/**
 * Pinned vs more tracks for fast TV D-pad access:
 * 1. Off (if present)
 * 2. Active / selected track
 * 3. Device / preferred language track (if found and not already pinned)
 * 4. Recent language tracks (in order of recency, if found and not already pinned)
 * 5. All remaining tracks stay in `more` in their original order.
 */
internal fun partitionPinnedTracks(
    tracks: List<TrackChoice>,
    preferredLang: String?,
    recentLangs: List<String>
): Pair<List<TrackChoice>, List<TrackChoice>> {
    val pinned = mutableListOf<TrackChoice>()
    val seen = mutableSetOf<String>()

    fun pin(track: TrackChoice) {
        if (seen.add(track.id)) {
            pinned.add(track)
        }
    }

    // 1. Off track first
    tracks.firstOrNull { it.isOff }?.let { pin(it) }

    // 2. Currently selected track (if not Off)
    tracks.firstOrNull { it.isSelected && !it.isOff }?.let { pin(it) }

    // 3. Preferred language track
    if (!preferredLang.isNullOrBlank()) {
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, preferredLang) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 4. Recently used language tracks
    for (recent in recentLangs) {
        if (recent.isBlank()) continue
        tracks.firstOrNull { !it.isOff && Languages.match(it.langCode, recent) > 0 && it.id !in seen }?.let { pin(it) }
    }

    // 5. Remaining tracks
    val more = tracks.filter { it.id !in seen }
    return pinned to more
}

/** Updates recent languages with [newLang] at the front, deduplicated and capped at [maxCount]. */
internal fun updateRecentLanguages(recents: List<String>, newLang: String, maxCount: Int = 3): List<String> {
    val code = Languages.code(newLang) ?: newLang.trim().lowercase()
    if (code.isBlank() || code == "und") return recents
    val list = recents.filterNot { Languages.same(it, code) || it.equals(code, ignoreCase = true) }.toMutableList()
    list.add(0, code)
    return list.take(maxCount)
}

/** Chunks a list of tracks into rows for a multi-column grid. */
internal fun <T> chunkGrid(tracks: List<T>, columns: Int = 3): List<List<T>> {
    require(columns > 0)
    return tracks.chunked(columns)
}

internal fun playerPauseFacts(subtitle: String, endClock: String?, isTrailer: Boolean, isLive: Boolean,
                              endsAt: String = "Ends %1\$s"): String =
    listOfNotNull(
        subtitle.takeIf { it.isNotBlank() },
        endClock?.takeIf { it.isNotBlank() && !isTrailer && !isLive }?.let { endsAt.format(it) },
    ).joinToString(Screen.DOT)

internal enum class BadgeBrand { NONE, DOLBY, DTS, SUBTITLES, DEBRID }

internal data class PlayerBadge(val brand: BadgeBrand, val label: String, val code: String? = null, val accent: Boolean = false)

internal fun playerBadges(
    stream: Stream?,
    videoHeight: Int = 0,
    videoWidth: Int = 0,
    audioLanguage: String? = null,
    audioMime: String? = null,
    audioLabel: String? = null,
    audioChannels: Int = 0,
    subtitleLanguage: String? = null,
): List<PlayerBadge> {
    if (stream == null && videoHeight <= 0 && audioMime == null && audioLanguage == null && subtitleLanguage == null) return emptyList()
    val out = mutableListOf<PlayerBadge>()
    if (stream?.dolbyVision == true) out += PlayerBadge(BadgeBrand.DOLBY, "VISION", accent = true)
    if (stream?.hdr == true && !stream.dolbyVision) out += PlayerBadge(BadgeBrand.NONE, "HDR", accent = true)

    val words = "${stream?.filename.orEmpty()} ${stream?.title.orEmpty()} ${stream?.description.orEmpty()} ${audioLabel.orEmpty()}".lowercase()
    // The file name describes the main audio track. Once the playing track is known, the name only refines its own
    // codec family (Matroska reports Atmos as plain E-AC-3, DTS-HD MA as DTS), so an AAC commentary is not "ATMOS".
    val unknown = audioMime == null
    val dolbyTrack = unknown || audioMime == MimeTypes.AUDIO_E_AC3 || audioMime == MimeTypes.AUDIO_E_AC3_JOC ||
        audioMime == MimeTypes.AUDIO_TRUEHD
    val dtsTrack = audioMime?.startsWith("audio/vnd.dts") == true
    val atmos = audioMime == MimeTypes.AUDIO_E_AC3_JOC || (dolbyTrack && "atmos" in words)
    val trueHd = audioMime == MimeTypes.AUDIO_TRUEHD || (unknown && "truehd" in words)
    val dolbyDigital = audioMime == MimeTypes.AUDIO_E_AC3 || audioMime == MimeTypes.AUDIO_AC3 ||
        (unknown && Regex("\\b(ddp?|e-?ac-?3|ac-?3|dolby.?digital)(\\b|\\d)").containsMatchIn(words))
    val dtsX = (unknown || dtsTrack) && Regex("dts[-_.: ]?x\\b").containsMatchIn(words)
    val dtsHd = audioMime == MimeTypes.AUDIO_DTS_HD || ((unknown || dtsTrack) && Regex("dts[-_.: ]?hd").containsMatchIn(words))
    val dts = dtsTrack || (unknown && Regex("\\bdts\\b").containsMatchIn(words))

    when {
        atmos -> out += PlayerBadge(BadgeBrand.DOLBY, "ATMOS", accent = true)
        trueHd -> out += PlayerBadge(BadgeBrand.DOLBY, "TRUEHD")
        dtsX -> out += PlayerBadge(BadgeBrand.DTS, "X", accent = true)
        dtsHd -> out += PlayerBadge(BadgeBrand.DTS, "HD")
        dts -> out += PlayerBadge(BadgeBrand.DTS, "")
        dolbyDigital -> out += PlayerBadge(BadgeBrand.DOLBY, "AUDIO")
    }

    val langName = audioLanguage?.let { Languages.code(it)?.let(Languages::name) ?: it }
        ?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
    val channels = when {
        audioChannels == 8 -> "7.1"
        audioChannels == 6 -> "5.1"
        audioChannels == 2 -> "2.0"
        audioChannels == 1 -> "1.0"
        audioChannels > 0 -> null
        Regex("(?<!\\d)7[ ._-]?1\\b").containsMatchIn(words) -> "7.1"
        Regex("(?<!\\d)5[ ._-]?1\\b").containsMatchIn(words) -> "5.1"
        else -> null
    }
    val audioTag = listOfNotNull(langName, channels.takeIf { langName != null || (!atmos && !dtsX) })
        .joinToString(" ").ifEmpty { null }
    if (audioTag != null) out += PlayerBadge(BadgeBrand.NONE, audioTag)

    val subName = subtitleLanguage?.let { Languages.code(it)?.let(Languages::name) ?: it }
        ?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
    if (subName != null) out += PlayerBadge(BadgeBrand.SUBTITLES, subName)

    if (stream?.remux == true) out += PlayerBadge(BadgeBrand.NONE, "REMUX")
    stream?.debrid?.let { d ->
        out += PlayerBadge(BadgeBrand.DEBRID, d.short, code = d.tag, accent = true)
    }
    return out
}

internal class OffsettableTextRenderer(
    private val delegate: TextRenderer
) : Renderer by delegate, RendererCapabilities by delegate {
    @Volatile
    var offsetUs: Long = 0L

    override fun getName(): String = delegate.name
    override fun getTrackType(): Int = delegate.trackType
    override fun getCapabilities(): RendererCapabilities = this

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(maxOf(0L, positionUs - offsetUs), elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, join: Boolean) {
        delegate.resetPosition(maxOf(0L, positionUs - offsetUs), join)
    }
}

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
/**
 * Classifies the first move of a drag on the picture into a player gesture: 0 none, 1 brightness (left half, up),
 * 2 volume (right half, up), 3 seek (horizontal). A horizontal drag that starts within [edge] px of a screen edge is
 * 4: swallowed, because that's where the system's back and home gestures and buttons live and a swipe there must not
 * scrub the film. [startX]/[startY] are the touch-down point; [dx]/[dy] the first move; [width]/[height] the screen.
 * Pure so the touch behaviour can be tested on the JVM (see SwipeGestureTest).
 */
internal fun swipeGesture(startX: Float, startY: Float, dx: Float, dy: Float, width: Int, height: Int, edge: Int): Int {
    val nearEdge = startX <= edge || startX >= width - edge || startY >= height - edge
    return when {
        Math.abs(dx) > Math.abs(dy) * 1.5f -> if (nearEdge) 4 else 3
        Math.abs(dy) > Math.abs(dx) * 1.5f -> if (startX < width / 2f) 1 else 2
        else -> 0
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Screen() {
    override val staysUprightOnPhone: Boolean get() = false
    override val handheldZoom: Float get() = 1.25f // the controls and panels were too small under a finger

    // The picture runs under the system bars and the controls carry their own way back.
    override val showsBackArrow get() = true // a phone's arrow: shown with the controls (see showControls)
    override val arrowStartsHidden get() = true
    override val padsSystemBars get() = false
    // A sideways drag here seeks or changes the volume, so the edge swipes that navigate elsewhere are off.
    override val swipeNav get() = false

    /** The arrow leaves the player at once, as Back does with nothing open; with a panel up it only closes the panel. */
    override fun arrowBack() {
        if (options.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE ||
            stallPanel.visibility == View.VISIBLE || stillPanel.visibility == View.VISIBLE) pressBack() else finish()
    }

    // Picture in picture: Home while a film plays shrinks it to a floating window instead of stopping it.
    private var inPip = false
    private var ownNav = false // this screen opened another one (sources, next episode): Android also calls onUserLeaveHint then

    override fun startActivity(intent: Intent?, options: android.os.Bundle?) { ownNav = true; super.startActivity(intent, options) }

    override fun onResume() { super.onResume(); ownNav = false }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ownNav || isFinishing || !Handheld.isHandheld(this) || android.os.Build.VERSION.SDK_INT < 26 || !::player.isInitialized) return
        // Enter PiP whenever a video is loaded and the viewer leaves — even mid-load. The old check required a
        // frame to have rendered and the player to be actively playing; a swipe that nudged the picture into
        // buffering then stopped PiP from opening at all. Only STATE_IDLE (nothing loaded) has nothing to show.
        if (player.playbackState == Player.STATE_IDLE) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    /** The floating window's shape (the film's own, held to the 1:2.39–2.39:1 range Android accepts; 16:9 before
     *  a frame) and a play/pause button, so playback is controlled from PiP's own controls instead of swiping the
     *  picture — a swipe on the small window used to scrub the film and knock it straight back out of PiP. */
    private fun pipParams(): android.app.PictureInPictureParams {
        val size = player.videoSize
        val shape = if (size.width > 0 && size.height > 0)
            (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.43f, 2.39f) else 16f / 9f
        val playing = player.playWhenReady
        val label = getString(if (playing) R.string.player_pause else R.string.action_play)
        val toggle = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            label, label, pipToggleIntent())
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((shape * 1000).toInt(), 1000))
            .setActions(listOf(toggle))
            .build()
    }

    private fun pipToggleIntent() = android.app.PendingIntent.getBroadcast(this, 0,
        Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

    // PiP's play/pause button lands here: the activity is only paused while it floats, never destroyed.
    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE && ::player.isInitialized) {
                if (player.playWhenReady) player.pause() else player.play()
            }
        }
    }

    @Suppress("NewApi")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            endFastHold()
            if (shown) hideControls(force = true)
            backArrowView?.visibility = View.GONE
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }
    private val isTrailer by lazy { intent.getBooleanExtra("isTrailer", false) }

    private var item: JSONObject? = null
    private var provisionalProgress = false
    private var lastSourceFailure: Failure? = null
    private var lastPosition = 0L
    private var ticks = 0

    @Volatile private var sources: List<Stream> = emptyList()
    private var resolvingStreams: Cancellable? = null
    private lateinit var backdropArt: ImageView
    private lateinit var backdropShade: FrameLayout
    @Volatile private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    // Mid-stream (already started) network retries, budgeted separately from [retries] so a long, stable playback can
    // keep recovering from occasional drops without looping forever on a source that fails at the same spot.
    private var resumeRetries = 0
    private var resumeProgressAt = 0L
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    // Start-wait progress tracking: a source that is buffering real data gets a little longer before it is skipped.
    private var startWaitCount = 0
    private var lastStartBuffered = 0L
    private var startMessage: String? = null
    private var watched = false // any source showed a picture this time: only then is progress saved
    private var handedOff = false // this source went to another video app (Settings › Video player)
    private var startAt = 0L
    @Volatile private var mbAutoPicked = false // Alder: the dub matching the audio preference was chosen once
    private var prepared = false
    private var stopped = false // Alder is behind another app: a late load must not start the sound
    // Usage stats: watched time is reported to our server in slices (so a crash loses at most one slice), plus one
    // final report with the completion flag when playback stops. Only real forward viewing counts (record() excludes seeks).
    private var watchPendingMs = 0L
    private var watchStarted = false
    private var watchCompleted = false
    private val statsKey by lazy { Profiles.active(this)?.authKey.orEmpty() }
    // Paywall: the subscription check for this account (null = not asked yet). A locked account sees the upgrade
    // dialog instead of the film; trailers play for everyone, and a server we cannot reach never blocks playback.
    private var entitled: Boolean? = null
    private var paywallChecking = false
    // The current source's link (Stream.open), the source it belongs to, and the one still opening (a torrent's engine).
    private var opened: Opened? = null
    private var openedFor: Stream? = null
    private var opening: Stream? = null
    // Audio borrowed from another same-dub source while THIS source's video keeps playing (its own audio failed):
    // borrowedFrom is the donor's index, borrowVideoFor the [at] it was borrowed for, borrowedOpened its link. Null
    // when playing normally. A merged (MergingMediaSource) source plays this video with the donor's audio.
    private var borrowedFrom: Int? = null
    private var borrowVideoFor: Int? = null
    private var borrowedOpened: Opened? = null
    private var borrowedFor: Stream? = null
    private var borrowApplied = false
    private val openLock = Any()
    @Volatile private var destroyed = false
    private val inFlightOpened = mutableListOf<Opened>()
    private var startWait = START_WAIT
    // Stremio's resume point when it differs from the TV's copy; used once the picture shows and the file can seek.
    private var lateResume: Long? = null
    private lateinit var status: android.widget.TextView
    private lateinit var loading: FrameLayout
    private lateinit var stallPanel: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var pausedShade: View
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var refreshOptionsSubtitles: (() -> Unit)? = null
    private var chosenSub: Subtitle? = null
    private val readySubs = mutableMapOf<String, SubtitleFiles.Ready?>() // add-on subtitle link -> local UTF-8 file
    private var selectAddonSub = false
    // The languages to start with: what was picked in Audio & subtitles for this title before (the next episode, a
    // resume), else the profile's Settings. Picks in the menu change them, are kept for the title, and hold when the
    // player moves to another source.
    private var wantAudio = ""
    private var wantSub = ""
    private var subsOn = true
    private var viewerAddonSub = false // the viewer chose the addon file, over the file's own in the same language
    private val dialogueProcessor = DialogueAudioProcessor()
    @Volatile private var decodeAudio = false
    @Volatile private var retryWithPcm = false
    @Volatile private var speed = 1f // playback speed: the audio sink decodes to PCM for any but 1x
    @Volatile private var passthroughOut = false // the audio track takes the stream as it is, not decoded PCM
    private var pickedSub: String? = null // the exact subtitle picked for this video before: "addon:<url>" or "file:<track id>"
    // Timing for subtitles (in the file or from add-ons): offset is applied directly to the TextRenderer live.
    private var subDelay = 0L
    private var textRenderer: OffsettableTextRenderer? = null
    private var subPref = "on"
    private var rememberedSubtitles: String? = null

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private val episodic get() = hasEpisodes(type, meta?.videos)
    private var tickedWatched = false
    // "Up next": offered during credits; the current episode keeps playing until the viewer chooses the next one.
    private lateinit var upNextPanel: FrameLayout
    private var upNextDismissed = false
    // "Still watching?": episodes before this one that ended with no key pressed (carried in the intent from one
    // episode to the next, and to another source), and the sleep choices: stop when this one ends, or pause at sleepAt.
    private lateinit var stillPanel: FrameLayout
    private var quiet = 0
    private var stopAfter = false
    private var sleepAt = 0L
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    // Auto quality (YouTube/Netflix style): a stall drops one quality step in the same dub; stable playback climbs back.
    private var downscaled = false
    private var downscaleAt = 0L
    private var switchGraceUntil = 0L
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    private val loadingTask = object : Runnable {
        override fun run() {
            if (loading.visibility != View.VISIBLE || player.playbackState != Player.STATE_BUFFERING) return
            showLoading(if (started) getString(R.string.player_loading) else startMessage
                ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
        }
    }
    private val startTimeout: Runnable = Runnable {
        if (started) return@Runnable
        // Progress-aware: a source that is actually buffering (data arriving, buffer not draining) gets a few more
        // waits before it is skipped, so a slow-but-alive 4K start is not killed; a source with no data at all still
        // moves on at the first deadline.
        val buffered = player.totalBufferedDuration
        if (buffered > START_PROGRESS_MS && buffered >= lastStartBuffered && startWaitCount < START_MAX_RETRIES) {
            lastStartBuffered = buffered
            startWaitCount++
            android.util.Log.i("Alder", "source ${at + 1} slow but buffering (${buffered}ms), extending start wait $startWaitCount/${START_MAX_RETRIES}")
            main.postDelayed(startTimeout, startWait)
            return@Runnable
        }
        android.util.Log.w("Alder", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState} buffered=${buffered}ms")
        next(Failure.TIMEOUT)
    }
    // A torrent/debrid open that never completes (no peers, a stalled engine) must not spin on the loading screen
    // forever: this fires next() after OPEN_WAIT while the source is still opening. Unlike startTimeout it ignores
    // `started` (a source being opened after a playing one still has the old started flag).
    private val openTimeout = Runnable {
        if (opening == null) return@Runnable
        android.util.Log.w("Alder", "source ${at + 1}/${sources.size} open timed out")
        next(Failure.TIMEOUT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val sourcesJson = intent.getStringExtra("sources")
        if (!sourcesJson.isNullOrBlank()) {
            sources = JSONArray(sourcesJson).objects().map(Stream::read)
        }
        if (sources.isEmpty() && isTrailer) return finish()
        decodeAudio = Settings.decodeAudio(this)
        speed = if (isTrailer) 1f else Settings.titleChoice(this, metaId)?.speed?.takeIf { it in SPEEDS } ?: 1f // kept per title, like audio and subtitles
        dialogueProcessor.enabled = Settings.clearDialogue(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                playbackAudioSink(context, dialogueProcessor) { decodeAudio || retryWithPcm || speed != 1f }

            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<Renderer>
            ) {
                val list = java.util.ArrayList<Renderer>()
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, list)
                val wrapped = list.map { r ->
                    if (r is TextRenderer) {
                        OffsettableTextRenderer(r).also { textRenderer = it }
                    } else r
                }
                out.addAll(wrapped)
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
        // Start after 2 s of video and 4 s after a stall, not 1 s and 2 s: big files stall again at once on 2 s.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 2_000, 4_000)
            .apply { bufferBytesFor(Runtime.getRuntime().maxMemory())?.let(::setTargetBufferBytes) }.build()
        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
        }
        player = ExoPlayer.Builder(this, renderers).setLoadControl(load).setTrackSelector(selector)
            // Take audio focus (a call or another app pauses us) and pause when headphones unplug, as a phone expects.
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // Hold a Wi-Fi / CPU wake lock while a network stream plays, so sound continues with the screen off
            // (background playback in PlaybackService).
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setPlaybackSpeed(speed)
        val pipFilter = android.content.IntentFilter(ACTION_PIP_TOGGLE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pipReceiver, pipFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pipReceiver, pipFilter)
        }
        // Android 13+ needs this granted to show the background-playback notification and its lock-screen controls.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                passthroughOut = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
                android.util.Log.i("Alder", "Audio output: encoding=${audioTrackConfig.encoding}, sample rate=${audioTrackConfig.sampleRate}, channel mask=${audioTrackConfig.channelConfig}")
            }
        })
        val remembered = Settings.titleChoice(this, metaId)
        // The Settings audio language wins (an explicit choice is always honoured on every title); else a per-title
        // pick; else the TV's language. A stale per-title pick must not override the language chosen in Settings.
        wantAudio = Settings.audioLanguageExplicit(this).ifBlank {
            remembered?.audio?.takeIf { it.isNotBlank() } ?: Settings.audioLanguage(this)
        }
        subPref = Settings.subPref(this)
        rememberedSubtitles = remembered?.subtitles
        subsOn = remembered?.subtitles?.isNotEmpty() ?: (subPref != "off")
        wantSub = remembered?.subtitles?.ifEmpty { null } ?: Settings.subtitleLanguage(this)
        pickedSub = remembered?.takeIf { it.subtitleVideo == videoId }?.subtitle
        if (pickedSub != null) { subsOn = true; viewerAddonSub = pickedSub!!.startsWith("addon:") }
        // Release files carry many dubs; start in the chosen language (the TV's unless Settings says) instead of the first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(wantAudio).setPreferredTextLanguage(wantSub).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Settings' size, color and background, with a shadow so text holds up on bright scenes either way.
            subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(context) / 100f)
            subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(context),
                if (Settings.subtitleBackground(context)) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            setBackgroundColor(Color.BLACK)
        }
        applyPictureFit()
        status = text("", BODY).onArt().apply {
            gravity = Gravity.CENTER
            maxWidth = dp(440)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        pausedShade = View(this).apply { setBackgroundColor(0x4D000000); visibility = View.GONE }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        stillPanel = FrameLayout(this).apply { visibility = View.GONE }
        quiet = intent.getIntExtra("quiet", 0)
        sleepAt = intent.getLongExtra("sleepAt", 0L)
        stopAfter = intent.getBooleanExtra("stopAfter", false) // another source of this video; the next episode gets a fresh intent
        controls = buildControls()
        loading = buildLoading()
        stallPanel = FrameLayout(this).apply { visibility = View.GONE }
        skip = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = glass(10)
            visibility = View.GONE
            setOnClickListener {
                (tag as? IntroSegment)?.takeIf { canSeek() }?.let { player.seekTo(it.endMs) }
                visibility = View.GONE
            }
        }
        if (!isTrailer) fetchSegments()
        options = FrameLayout(this).apply { visibility = View.GONE }
        val (bArt, bShade) = buildBackdrop()
        backdropArt = bArt
        backdropShade = bShade
        screen = FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
            addView(backdropShade, FrameLayout.LayoutParams(-1, -1))
            addView(pausedShade, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
            addView(loading, FrameLayout.LayoutParams(-1, -1))
            addView(stallPanel, FrameLayout.LayoutParams(-1, -1))
            addView(upNextPanel, FrameLayout.LayoutParams(-1, -1))
            addView(stillPanel, FrameLayout.LayoutParams(-1, -1))
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
        }
        if (Handheld.isHandheld(this)) { installTouch() }
        setContentView(screen)
        if (Handheld.isHandheld(this)) Handheld.immersive(this)
        background({
            Stremio.addons(key).let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            if (!isTrailer) fetchSegments() // anime episode ids get their IntroDB season and number from the meta
            val episode = if (isTrailer) null else m.videos.firstOrNull { it.id == videoId }
            pauseTitle.text = episode?.title?.takeIf { it.isNotBlank() } ?: m.name
            subtitle.text = if (isTrailer) getString(R.string.action_trailer) else episode?.let { "S${it.season} E${it.episode}" } ?: m.year.orEmpty()
            Images.load(m.logo ?: Images.logoFor(m.id), playerLogo, dp(300), urgent = true, failed = {
                playerLogo.visibility = View.GONE; topName.visibility = View.VISIBLE
            }) {
                playerLogo.visibility = View.VISIBLE; topName.visibility = View.GONE
            }
            nextCell.visibility = if (isTrailer || nextVideo() == null) View.GONE else View.VISIBLE
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                val format = (e as? ExoPlaybackException)?.rendererFormat
                val sinkFormat = when (val cause = e.cause) {
                    is AudioSink.ConfigurationException -> cause.format
                    is AudioSink.InitializationException -> cause.format
                    is AudioSink.WriteException -> cause.format
                    else -> null
                }
                val outputFailed = audioOutputFailure(e.errorCode)
                val selected = audioGroups.firstOrNull { it.isSelected }
                val failed = audioGroups.firstOrNull { it.getTrackFormat(0) == format || it.getTrackFormat(0) == sinkFormat } ?: selected
                if (shouldRetryWithPcm(decodeAudio || retryWithPcm, outputFailed, sinkFormat?.sampleMimeType)) {
                    retryWithPcm = true
                    retryAudio(failed, "Retrying the audio track as PCM after ${e.errorCodeName}")
                    return
                }
                var audioExhausted = false
                if ((outputFailed || format?.sampleMimeType?.let(MimeTypes::isAudio) == true) &&
                    failed != null && failedAudio.add(failed.getTrackFormat(0))) {
                    bestAudio(audioGroups)?.let {
                        retryAudio(it, "Audio track changed after ${e.errorCodeName}")
                        return
                    }
                    // No audio track in this source plays. The video can still be fine: keep it and borrow audio from
                    // another source of the SAME dub before moving on to a different one.
                    audioExhausted = true
                    val reason = playbackFailure(e.errorCode, e.cause)
                    val donorIdx = borrowedFrom
                    if (donorIdx != null) {
                        // The donor's audio failed too: give up the borrow and fall back to switching source.
                        audioDead += donorIdx
                        clearBorrow()
                        if (switchDubForAudio(reason)) return
                    } else if (borrowAudioFromSameDub(reason)) return
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                val failure = playbackFailure(e.errorCode, e.cause)
                // Retry the same source on a transient network/server error (not a gone link or a bad file). Before a
                // picture shows this is the START retry (bounded by [retries]); after a picture has shown, a mid-stream
                // network drop is retried from the current position instead of skipping the source (bounded by
                // [resumeRetries], refreshed by forward progress), so a single-source title or a brief hiccup does not
                // kick the viewer out to the source list.
                val startRetry = !started && retries < RETRIES
                val resumeRetry = started && resumeRetries < MID_RETRIES
                if (retryable(e) && (startRetry || resumeRetry) && !audioExhausted && !stopped) {
                    lastSourceFailure = failure
                    FailureLog.record(FailureLog.Operation.PLAYBACK, failure)
                    reportError(failure, critical = false) // a retry will follow; a blip, not a dead source
                    val resume = position()
                    if (started) resumeRetries++ else retries++
                    // A start retry gets a fresh START_WAIT so the start timeout doesn't move on mid-retry; a mid-stream
                    // retry keeps playing from where it dropped (no start timeout — the buffer stall/error paths cover it).
                    main.removeCallbacks(startTimeout)
                    if (!started && !stopped) main.postDelayed(startTimeout, RETRY_WAIT + startWait)
                    main.postDelayed({
                        if (isFinishing || isDestroyed) return@postDelayed
                        if (started && !player.isCurrentMediaItemLive) player.seekTo(resume)
                        player.prepare()
                    }, RETRY_WAIT)
                    android.util.Log.i("Alder", "retrying source ${at + 1}/${sources.size} ${if (started) "at ${resume / 1000}s" else "from start"} after ${e.errorCodeName} (attempt ${if (started) resumeRetries else retries})")
                    return
                }
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                reportError(failure, critical = true) // this source is dead; skipping to the next one
                next(failure)
            }

            override fun onRenderedFirstFrame() {
                lastSourceFailure?.let { FailureLog.record(FailureLog.Operation.PLAYBACK, it, recovered = true) }
                lastSourceFailure = null
                if (started) return
                started = true
                applyLateResume()
                watched = true
                retries = 0
                hideLoading()
                dismissBackdrop()
                main.removeCallbacks(startTimeout)
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                refreshBadges()
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                if (!isTrailer && sources.isNotEmpty()) {
                    val s = sources[at]
                    getSharedPreferences("sources", MODE_PRIVATE).edit()
                        .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
                }
                main.postDelayed({ if (player.playWhenReady) hideControls() }, 2000)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // At the real end the next episode starts, unless the viewer chose to watch the credits or to stop after
                // this one, or this is the third episode in a row with no key pressed: then "Still watching?" waits.
                if (state == Player.STATE_ENDED) {
                    watchCompleted = true // this play reached the end: the final stats report carries the completion flag
                    if (isTrailer) {
                        finish()
                        return
                    }
                    val upcoming = nextVideo()
                    // shortcut: sub-two-minute series streams are treated as addon placeholders; revisit for short-form shows.
                    if (episodic && !plausibleEpisode(player.duration)) {
                        started = false // retry from the original resume point, not the placeholder's short ending
                        next(Failure.MISSING)
                    } else if (upcoming == null || upNextDismissed || stopAfter) finish()
                    else if (askStillWatching(quiet + 1)) showStillWatching(upcoming)
                    else playNext(quiet + 1)
                }
                if (state == Player.STATE_BUFFERING) showLoading(if (started) getString(R.string.player_loading) else startMessage
                    ?: getString(R.string.player_starting, sources.getOrNull(at)?.label.orEmpty()))
                if (state == Player.STATE_READY) {
                    if (started) {
                        retries = 0 // the source recovered: the next network drop gets its own two retries
                        // Give the mid-stream retry budget back after real forward progress, so a long stable playback
                        // keeps recovering from occasional drops (but a loop stuck at one spot never does).
                        if (player.currentPosition > resumeProgressAt + RESUME_REFRESH_MS) {
                            resumeRetries = 0
                            resumeProgressAt = player.currentPosition
                        }
                        hideLoading()
                        updateMarkers()
                    }
                }
                // A seek buffers too; only stalls in plain playback count.
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) stalled()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playPause.foreground = centred(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play, 25, BASE)
                buttonLabels[playPause]?.text = getString(if (playWhenReady) R.string.player_pause else R.string.action_play)
                pausedShade.visibility = if (playWhenReady) View.GONE else View.VISIBLE
                if (playWhenReady) { if (shown) showControls(null) }
                else if (options.visibility != View.VISIBLE && upNextPanel.visibility != View.VISIBLE &&
                    stallPanel.visibility != View.VISIBLE && stillPanel.visibility != View.VISIBLE) showControls(playPause)
                // Keep PiP's play/pause button in step with the player while it floats.
                if (inPip && android.os.Build.VERSION.SDK_INT >= 26)
                    runCatching { setPictureInPictureParams(pipParams()) }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    lastPosition = new.positionMs // a jump is not watched time
                    skipped.clear() // back into an intro: offer it again
                    skip.tag = null // with a new 10 s window, not the old one
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackStatus.text = trackStatus(tracks)
                refreshBadges(tracks)
                // Borrowing: keep this source's video with the donor's audio; skip the normal per-source audio/dub picks.
                if (borrowedFrom != null) {
                    if (!borrowApplied) { borrowApplied = true; applyBorrowedAudio(tracks) }
                    return
                }
                // Alder: start in the dub that matches the audio preference, like Stremio (once per video).
                if (!mbAutoPicked && languageDubChoices().isNotEmpty()) {
                    mbAutoPicked = true
                    if (wantAudio.isNotBlank()) {
                        val target = languageDubChoices().firstOrNull { !it.isSelected && Languages.match(it.langCode, wantAudio) > 0 }
                        val idx = target?.id?.removePrefix("dub:")?.toIntOrNull()
                        if (idx != null && idx in sources.indices) {
                            at = idx
                            resetSourceState()
                            load(position())
                            return
                        }
                    }
                }
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                    refreshOptionsSubtitles?.invoke()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                val lostAudioSupport = audio.any { it.isSelected && !it.isTrackSupported(0) }
                val best = bestAudio(audio)
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the profile's subtitle language, whatever the sound, else
                // none. The file's own come first, then the addon file attached at the start, then the file's own from
                // another country (Portugal's for Brazil's). Once per video, like audio — but only once text tracks
                // have actually been reported, so a subtitle that loads a moment late still gets picked.
                val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
                if (!subsPicked && best != null && (texts.isNotEmpty() || started)) {
                    subsPicked = true
                    fun inFile(score: Int) = texts.filterNot(::isAddonSub).filter { Languages.match(it.getTrackFormat(0).language, wantSub) == score }
                        .maxByOrNull { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED == 0 } // full, not forced-only
                    val addon = texts.firstOrNull { isAddonSub(it) && Languages.same(chosenSub?.lang, wantSub) }
                    // The same track of the file as last time, while its language still matches (another source's file
                    // numbers its tracks its own way).
                    val exact = pickedSub?.takeIf { it.startsWith("file:") }?.removePrefix("file:")?.let { id ->
                        texts.firstOrNull { g -> !isAddonSub(g) && g.getTrackFormat(0).let { it.id == id &&
                            (it.language == null || Languages.same(it.language, wantSub)) } }
                    }
                    val audioLang = best.getTrackFormat(0).language
                    val foreignAudio = !Languages.same(audioLang, wantSub)
                    val forcedTrack = texts.filterNot(::isAddonSub).firstOrNull {
                        Languages.match(it.getTrackFormat(0).language, wantSub) >= 1 &&
                            (it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                    }
                    val pick = when {
                        !subsOn -> null
                        exact != null -> exact
                        subPref == "foreign" && !foreignAudio && rememberedSubtitles == null -> forcedTrack
                        else -> (if (viewerAddonSub) addon else null) ?: inFile(2) ?: addon ?: inFile(1)
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
                        if (pick == null) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        else setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(pick.mediaTrackGroup, 0))
                    }.build()
                }
                if (lostAudioSupport && best == null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                    return
                }
                // A probed-audio pick from another source: select its track now that the source is loaded.
                pendingProbeAudio?.let { want ->
                    val g = audio.firstOrNull { Languages.match(it.getTrackFormat(0).language, want) > 0 }
                    if (g != null) {
                        pendingProbeAudio = null
                        audioPicked = true
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, (0 until g.length).toList())).build()
                        return
                    }
                }
                if ((audioPicked && !lostAudioSupport) || best == null) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // An adaptive (HLS/DASH) source can switch resolution mid-play; keep the quality badge on the real size.
                refreshBadges()
            }
        })

        // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
        val known = if (isTrailer || intent.getBooleanExtra("startOver", false)) 0L else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 }
        // The addon's subtitles are waited for (SUB_WAIT at most) so a file in the profile's language is attached from
        // the start: attaching one later means loading the source again. The latest resume point asked for wins.
        var subsAnswered = isTrailer || !subsOn
        var waiting: Long? = null
        var pendingResume: Long? = null
        fun begin(resume: Long) {
            if (!isTrailer && entitled == false) return
            if (!isTrailer && entitled == null) {
                if (paywallChecking) return
                paywallChecking = true
                background({ runCatching { Stremio.billingStatus(key) }.getOrNull() }) { r ->
                    paywallChecking = false
                    if (isFinishing || isDestroyed) return@background
                    val status = r.getOrNull()
                    // Fail open: a server we cannot reach must not lock a paying viewer out.
                    if (status == null || status.active) { entitled = true; begin(resume) }
                    else { entitled = false; upgradeDialog(status) }
                }
                return
            }
            if (prepared) return
            if (sources.isEmpty()) {
                pendingResume = resume
                return
            }
            // Start playback at once; do not block the picture on subtitle add-ons. Subtitles attach
            // when they arrive (see the late-subs path in askSubs), usually well before the wait expires.
            load(resume)
            lastPosition = resume
            prepared = true
            if (!isTrailer) {
                main.postDelayed(tickTask, TICK)
                main.post(skipTask)
            }
        }
        if (isTrailer) {
            begin(0L)
            return
        }
        // Still ask for subtitles so they can be attached once they arrive, but never hold the picture for them.
        val waitMs = if (pickedSub?.startsWith("addon:") == true) 3_500L else SUB_WAIT
        val stopWaiting = Runnable { if (!isDestroyed) { subsAnswered = true } } // playback already started
        main.postDelayed(stopWaiting, waitMs)
        // Asked with the stream's file name and size when they're known, so the addon puts files for that release
        // first. A stream found here (Continue Watching) asks again once it's known; an older answer never replaces a newer one.
        var subsAsked = 0
        var subsShown = 0
        var subsTries = 0 // how many extra fetch attempts were made after an empty or failed answer
        fun askSubs(stream: Stream?): Unit = (++subsAsked).let { round -> background({
            // Every language: the profile's subtitle language and English first, three files from each add-on in its order,
            // then one file for each other language by name, so the list stays short enough for a remote.
            val all = (Stremio.addons(key) + MovieboxAddon()).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId, stream) }
                    .onFailure { android.util.Log.w("Alder", "subtitles from ${a.name} failed", it) }.getOrDefault(emptyList()) }
            all.groupBy { Languages.code(it.lang) ?: it.lang }.entries
                .sortedWith(compareBy({ it.key != wantSub }, { it.key != "en" }, { Languages.name(it.key) }))
                // The limit is per add-on: with one limit for all, OpenSubtitles' files filled it and SubDL's never showed.
                .flatMap { (code, files) -> files.groupBy { it.addon }.values.flatMap { it.take(if (code == wantSub || code == "en") 3 else 1) } }
        }) { r ->
            if (round < subsShown || isFinishing || isDestroyed) return@background
            subsShown = round
            val failed = r.exceptionOrNull()
            addonSubs = r.getOrDefault(emptyList())
            android.util.Log.i("Alder", "addon subtitles: ${addonSubs.size}", failed)
            if (options.visibility == View.VISIBLE) {
                refreshOptionsSubtitles?.invoke()
            }
            // A transient failure or an empty first answer is retried a couple of times, so a slow or briefly
            // unreachable subtitle add-on doesn't leave the subtitle list looking empty for the rest of playback.
            if ((failed != null || addonSubs.isEmpty()) && subsTries < SUB_RETRIES && !isFinishing && !isDestroyed) {
                subsTries++
                main.postDelayed({ if (!isFinishing && !isDestroyed) askSubs(stream) }, SUB_RETRY_WAIT)
            }
            val exact = addonSubs.firstOrNull { "addon:" + it.url == pickedSub }
            if (subsAnswered) { // playback already started: attach the picked file
                val late = if (chosenSub == null && subsOn) addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } else null
                val attach = exact ?: late
                if (attach != null && chosenSub == null && prepared) {
                    chosenSub = attach
                    selectAddonSub = true
                    load(position(), play = player.playWhenReady)
                }
                return@background
            }
            main.removeCallbacks(stopWaiting)
            if (chosenSub == null) chosenSub = exact ?: addonSubs.firstOrNull { Languages.same(it.lang, wantSub) } // attached, picked above
            stopWaiting.run()
        } }
        askSubs(sources.firstOrNull())
        if (sources.isEmpty()) {
            pauseFacts.text = getString(R.string.finding_sources)
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
            resolvingStreams = StreamsResolver.resolve(
                context = this,
                type = type,
                videoId = videoId,
                metaId = metaId,
                authKey = key,
                onReady = { best ->
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    sources = best
                    // Try every source in the wanted language first (best quality first), then English/original, then the
                    // rest, so a slow or failed source gives way to the next one in the SAME language, not another dub
                    // (see PlaybackOrder). Start on the best of the first group.
                    val originalFirst = wantAudio.isBlank() && Settings.dub(this) == "original"
                    sources = PlaybackOrder.order(best, wantAudio, originalFirst)
                    audioDead.clear()
                    clearBorrow()
                    at = 0
                    android.util.Log.i("AlderStreams", "start at #$at ${sources.getOrNull(at)?.height}p langs=${sources.getOrNull(at)?.audioLanguages} of ${sources.size}")
                    startAudioProbes()
                    best.firstOrNull()?.takeIf { subtitleExtras(it.filename, it.size).isNotEmpty() }?.let(::askSubs)
                    refreshBadges()
                    pauseFacts.text = getString(R.string.player_starting_stream)
                    val resumeToStart = pendingResume ?: known ?: 0L
                    begin(resumeToStart)
                },
                onFallback = {
                    resolvingStreams = null
                    if (isFinishing || isDestroyed) return@resolve
                    startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent))
                    finish()
                }
            )
        }
        // The stream starts at once from what's known here (the intent, or the library saved on the TV) instead of
        // waiting a round trip to Stremio for the resume point; Stremio's answer corrects it if it moved elsewhere.
        if (known != null) begin(known)
        background({ Stremio.savedLibrary(key)?.firstOrNull { it.optString("_id") == metaId } }) { r ->
            val cached = r.getOrNull()
            if (cached != null && (item == null || provisionalProgress)) {
                item = cached
                provisionalProgress = false
                if (known == null) begin(Library.resumeAt(cached, videoId))
            }
        }
        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            val fresh = result.getOrNull()
            if (fresh != null && newerProgress(fresh, item)) item = fresh
            if (item == null) {
                provisionalProgress = result.isFailure
                item = Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster"))
            }
            val resume = known ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            if (!prepared) begin(resume)
            else if (known == null && fresh != null) { lateResume = resume; applyLateResume() }
        }
    }

    /** Moves to [lateResume] unless the viewer has already moved on. Waits for the first picture: before it, a subtitle
     *  may still be getting ready (no file loaded) and the file doesn't yet say if it can seek (if not, a seek lands on 0:00). */
    private fun applyLateResume() {
        val resume = lateResume ?: return
        if (!started) return
        lateResume = null
        if (abs(resume - startAt) > RESUME_SLACK && player.currentPosition < startAt + RESUME_SLACK && player.isCurrentMediaItemSeekable) {
            startAt = resume
            player.seekTo(resume)
            lastPosition = resume
        }
    }

    private fun buildBackdrop(): Pair<ImageView, FrameLayout> {
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val bg = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        if (!bg.isNullOrBlank()) {
            Images.load(bg, art, resources.displayMetrics.widthPixels)
        }
        val shade = FrameLayout(this).apply {
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xEB0E0C0A.toInt(), 0x990E0C0A.toInt(), 0x330E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xF70E0C0A.toInt(), 0xB30E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A)
                )
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
        }
        return art to shade
    }

    private fun dismissBackdrop() {
        if (::backdropArt.isInitialized && backdropArt.visibility == View.VISIBLE) {
            backdropArt.exit(PAGE_MS).alpha(0f).withEndAction { backdropArt.visibility = View.GONE }
            backdropShade.exit(PAGE_MS).alpha(0f).withEndAction { backdropShade.visibility = View.GONE }
        }
    }

    private fun buildLoading() = FrameLayout(this).apply {
        visibility = View.GONE
        addView(View(context).apply { setBackgroundColor(0x800E0C0A.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        val center = column(Gravity.CENTER_HORIZONTAL).apply {
            addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun showLoading(message: String) {
        if (started) {
            status.text = if (message == getString(R.string.player_loading) && player.bufferedPercentage in 1..99)
                getString(R.string.player_loading_percent, player.bufferedPercentage) else message
            loading.visibility = View.VISIBLE
            main.removeCallbacks(loadingTask)
            if (player.playbackState == Player.STATE_BUFFERING) main.postDelayed(loadingTask, 500)
        } else {
            pauseFacts.text = message
            pauseFacts.visibility = View.VISIBLE
            bar.isIndeterminate = true
            showControls(null)
        }
    }

    private fun hideLoading() {
        main.removeCallbacks(loadingTask)
        loading.visibility = View.GONE
        status.text = ""
        bar.isIndeterminate = false
    }

    /** A subtitle pick in the menu: the language ("" for Off) for this title's next episode and resume, and for the
     *  next source if this one fails, and [pick] itself for this video. A track with no language turns subtitles on
     *  without naming one. */
    private fun remember(language: String?, addonFile: Boolean, pick: String) {
        subsOn = language != ""
        viewerAddonSub = addonFile
        pickedSub = pick.ifEmpty { null }
        val code = if (language == "") "" else Languages.code(language)
        rememberedSubtitles = code
        if (!code.isNullOrEmpty()) wantSub = code
        Settings.pickedInPlayer(this, metaId, subtitles = code, video = videoId, subtitle = pick)
    }

    /** A media source for [src]'s link with [item], read through [src]'s request headers (debrid proxies need them). */
    private fun mediaSource(src: Opened, item: MediaItem, extractors: DefaultExtractorsFactory): MediaSource {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(src.headers)
            .setReadTimeoutMs(src.readTimeoutMs)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors).createMediaSource(item)
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long, message: String? = null, play: Boolean = true) {
        if (sources.isEmpty()) return
        // A borrow belongs to one video source; once the viewer moves to another source, let the donor go.
        if (borrowedFrom != null && at != borrowVideoFor) clearBorrow()
        val s = sources[at]
        // The source's link first (Stream.open). A link is ready at once; a torrent's engine starts on another thread,
        // then load runs again. A new source lets the last one go.
        if (openedFor !== s) {
            retryWithPcm = false
            audioPicked = false
            audioGroups = emptyList()
            failedAudio.clear()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
            closeInFlightOpened()
            if (opening !== s) opening = null
            opened?.close()
            opened = null
            openedFor = null
            if (!s.opensAtOnce) return openSource(s, position, message, play)
            opened = try {
                s.open(this)
            } catch (e: Exception) {
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} failed to open", e)
                return next(failureOf(e))
            }
            openedFor = s
        }
        val src = opened!!
        startWait = src.startWait ?: START_WAIT
        val app = Settings.externalPlayer(this)
        if (!isTrailer && !handedOff && app != null) {
            if (handOff(src, position, app)) return
            // No other video app on this TV: Alder plays it, and says so.
            return load(position, bt(R.string.player_no_other_app), play)
        }
        // An add-on subtitle is fetched and made ready first (unpacked, UTF-8): see SubtitleFiles. Once per file.
        // A deadline keeps a stalled fetch from spinning on the loading screen forever: if it doesn't finish in
        // SUB_PREP_WAIT, the source is loaded without that add-on file (still offered in the subtitle list).
        chosenSub?.let { sub ->
            if (sub.url !in readySubs) {
                val prepUrl = sub.url
                val prepAt = at
                val prepTimeout = Runnable {
                    if (!isFinishing && !isDestroyed && !readySubs.containsKey(prepUrl) && chosenSub === sub && at == prepAt) {
                        android.util.Log.w("Alder", "subtitle from ${sub.addon} prep timed out; playing without it")
                        readySubs[prepUrl] = null
                        load(position, message, play)
                    }
                }
                main.postDelayed(prepTimeout, SUB_PREP_WAIT)
                background({ SubtitleFiles.prepare(java.io.File(cacheDir, "subtitles"), sub) }) { r ->
                    main.removeCallbacks(prepTimeout)
                    r.exceptionOrNull()?.let { android.util.Log.w("Alder", "subtitle from ${sub.addon} not prepared", it) }
                    readySubs[sub.url] = r.getOrNull()
                    if (!isFinishing && chosenSub === sub) load(position, message, play)
                }
                return
            }
        }
        startAt = position
        started = false
        toldUnseekable = false
        startMessage = message ?: getString(R.string.player_starting, s.label)
        bar.isIndeterminate = true
        showLoading(startMessage!!)
        subDelay = 0L
        textRenderer?.offsetUs = 0L
        mkvChapters = emptyList()
        setChapterSnapMode(false)
        updateMarkers()
        refreshBadges()
        showControls(null)
        background({
            MkvChapters.fetch(src.url, src.headers)
        }) { r ->
            val chaps = r.getOrNull()
            if (!chaps.isNullOrEmpty() && sources.getOrNull(at) === s) {
                mkvChapters = chaps
                reconcileSegments()
            }
        }
        try {
            val item = MediaItem.Builder().setUri(src.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                    val ready = readySubs[it.url] // null: not prepared, so the add-on's own link as before
                    MediaItem.SubtitleConfiguration.Builder(ready?.file?.let(android.net.Uri::fromFile) ?: android.net.Uri.parse(it.url))
                        .setMimeType(ready?.mimeType ?: it.mimeType)
                        .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
                })).build()
            // Files with no index (.ts, raw audio, some .avi) seek by their bitrate instead of not at all: ExoPlayer turns
            // every seek in a file it can't seek into a jump to 0:00, so scrubbing restarted the movie.
            val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).setConstantBitrateSeekingAlwaysEnabled(true)
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            val current = mediaSource(src, item, extractors)
            val donor = borrowedOpened?.takeIf { borrowedFrom != null }
            if (donor != null) {
                // Keep this source's video, take the donor's audio: a merged source of both. The donor carries no
                // subtitles of its own (this source's subtitles are the ones that play).
                val donorItem = MediaItem.Builder().setUri(donor.url).build()
                val donorSource = mediaSource(donor, donorItem, extractors)
                player.setMediaSource(MergingMediaSource(current, donorSource), position)
                borrowApplied = false
                android.util.Log.i("AlderStreams", "merged video #$at + audio #${borrowedFrom} for playback at ${position / 1000}s")
            } else {
                player.setMediaSource(current, position) // files too: shifted subtitles
            }
        } catch (e: Exception) {
            // A source whose media source can't be built (an unsupported container, a missing factory) is skipped, not fatal.
            android.util.Log.w("Alder", "source ${at + 1}/${sources.size} couldn't be prepared", e)
            return next(failureOf(e))
        }
        player.prepare()
        player.playWhenReady = play && !stopped
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(openTimeout)
        startWaitCount = 0
        lastStartBuffered = 0L
        if (!stopped) main.postDelayed(startTimeout, startWait) // else onStart starts it
    }

    /** Opens [s] off the main thread (a torrent finds peers and its file), then plays it; a failure moves on. */
    private fun openSource(s: Stream, position: Long, message: String?, play: Boolean) {
        main.removeCallbacks(startTimeout)
        showLoading(message ?: getString(R.string.player_starting, s.label))
        if (opening === s) return
        opening = s
        // A torrent that never opens must give way to the next source instead of spinning on the loading screen.
        main.removeCallbacks(openTimeout)
        main.postDelayed(openTimeout, OPEN_WAIT)
        val episode = if (isTrailer || type == "movie" || isLiveType(type)) null else {
            meta?.videos?.firstOrNull { it.id == videoId && it.season >= 0 && it.episode > 0 }
                ?.let { it.season to it.episode }
                ?: episodeNumbers(videoId)
                ?: intent.getStringExtra("episode")?.substringBefore("  ·  ")?.let(::parseEpisodeLabel)
        }
        val stale = { destroyed || isFinishing || isDestroyed || sources.getOrNull(at) !== s }
        background({
            val o = s.open(applicationContext, episode, stale)
            synchronized(openLock) {
                if (closeStaleOpen(o, stale())) null
                else o.also { inFlightOpened += it }
            }
        }) { r ->
            if (opening === s) opening = null
            val o = r.getOrNull()
            if (o != null) synchronized(openLock) { inFlightOpened.remove(o) }
            if (closeStaleOpen(o, stale())) return@background
            if (o == null) {
                android.util.Log.w("Alder", "source ${at + 1}/${sources.size} didn't open", r.exceptionOrNull())
                startAt = position
                started = false
                return@background next(r.exceptionOrNull()?.let(::failureOf) ?: Failure.OTHER)
            }
            opened = o
            openedFor = s
            load(position, message, play)
        }
    }

    /**
     * Settings › Video player › a video app: Alder still picks the source, the resume point and the subtitle, then the
     * other app plays it. False when no app on the TV opens video. Its answer comes back in [onActivityResult].
     */
    private fun handOff(s: Opened, position: Long, app: String): Boolean {
        handedOff = true
        startAt = position
        // The chosen app, or Android's Open with list if it was uninstalled since.
        val pick = app.takeIf { a -> ExternalPlayer.installed(this).any { it.first == a } }
        val out = ExternalPlayer.intent(s.url, intent.getStringExtra("name").orEmpty(), s.headers, position, chosenSub?.url, pick)
        return try {
            startActivityForResult(out, ExternalPlayer.REQUEST)
            showLoading(getString(R.string.player_other_app))
            hideControls(force = true) // Alder's play and seek hints mean nothing while the other app plays
            true
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.i("Alder", "no other video player", e)
            false
        }
    }

    // ponytail: VLC gives no duration for network streams; the title's last known duration stands in, else nothing is saved.
    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SOURCES_REQUEST) return sourcesClosed(resultCode, data)
        if (requestCode != ExternalPlayer.REQUEST) return
        val (position, told) = ExternalPlayer.result(data)
        val item = item
        val duration = told.takeIf { it > 0 } ?: item?.optJSONObject("state")?.optLong("duration") ?: 0L
        android.util.Log.i("Alder", "other player stopped at $position of $duration")
        if (item != null && position > 0 && duration > 0 && !isLiveType(type) && !(episodic && !plausibleEpisode(duration))) {
            Library.record(item, videoId, position, duration, (position - startAt).coerceAtLeast(0))
            val videos = meta?.videos
            if (videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) Library.markWatched(item, videos, videoId)
            Library.stopped(item, nextVideo(onlyAired = false)?.id ?: intent.getStringExtra("nextVideoId"))
            watched = true
            push()
        }
        finish()
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** What one source learned about itself; a new source starts clean. */
    private fun resetSourceState() {
        stalls.clear()
        retries = 0
        asHls = false
        audioPicked = false
        subsPicked = false
        failedAudio.clear()
        resumeRetries = 0
        resumeProgressAt = 0L
        startWaitCount = 0
        lastStartBuffered = 0L
        downscaled = false
        downscaleAt = 0L
    }

    /** How many leading sources are the wanted language or the English/original fallback (the rest are other dubs).
     *  A timeout never crosses this line, so a slow source is not swapped for a different language. With no such
     *  source at all, every source is allowed, so something always plays. */
    private fun playbackLanguageLimit(): Int {
        val want = wantAudio.takeIf { it.isNotBlank() }
        val preferred = sources.count { s ->
            val l = s.audioLanguages
            (want != null && l.any { Languages.match(it, want) > 0 }) || l.isEmpty() || l.any { Languages.same(it, "en") }
        }
        return if (preferred == 0) sources.size else preferred
    }

    /**
     * The dub languages for the playing title, as Audio-tab choices. Every source's language is collected and shown
     * once per language (10 French sources collapse to a single "French" entry), plus "Original" for untagged sources.
     * Picking a language plays the best source for it (see [PlaybackOrder.score]); empty when there is only the one
     * default track (no language to choose), so the file's own audio tracks are shown instead.
     */
    private fun languageDubChoices(): List<TrackChoice> {
        if (sources.size < 2) return emptyList()
        val groups = LinkedHashMap<String, MutableList<Int>>()
        sources.forEachIndexed { i, s ->
            val langs = s.audioLanguages
            if (langs.isEmpty()) groups.getOrPut("") { ArrayList() }.add(i)
            else langs.distinct().forEach { groups.getOrPut(it) { ArrayList() }.add(i) }
        }
        // Fall back to the file's own audio tracks only when no source names a language; otherwise show the deduped
        // language list (10 French sources still collapse to one "French" entry, alongside "Original" if any untagged).
        if (groups.keys.none { it.isNotEmpty() }) return emptyList()
        val choices = groups.entries.map { (code, indices) ->
            val idx = indices.minByOrNull { i -> PlaybackOrder.score(sources[i]) } ?: indices.first()
            val label = if (code.isEmpty()) "Original" else Languages.name(code)
            TrackChoice(id = "dub:$idx", name = label, langCode = code.ifEmpty { null }, isSelected = at in indices)
        }
        android.util.Log.i("AlderStreams", "audio modal from ${sources.size} sources -> " +
            choices.joinToString(" | ") { "${it.name}(${it.langCode ?: "orig"})@" + sources.getOrNull(it.id.removePrefix("dub:").toInt())?.height })
        return choices
    }

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: Failure) {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(openTimeout)
        lastSourceFailure = reason
        FailureLog.record(FailureLog.Operation.PLAYBACK, reason)
        val position = position()
        // A slow, stalling or undecodable source must not make the player jump to another dub. On a timeout, network
        // stall or decode/format failure, only advance within the wanted language and the English/original fallback;
        // another language is reached only after a real failure, or by picking it in the source list.
        val performance = reason == Failure.TIMEOUT || reason == Failure.NETWORK
        val limit = if (performance || reason == Failure.FORMAT) playbackLanguageLimit() else sources.size
        if (at + 1 < sources.size && (at >= limit || at + 1 < limit)) {
            at++
            resetSourceState()
            // A performance-driven step down can climb back up once the link is stable again (see autoQuality).
            if (performance) { downscaled = true; downscaleAt = System.currentTimeMillis() }
            return load(position, failureMessage(R.string.player_source_failed_next, reason, advice = false))
        }
        if (isTrailer) {
            intent.getStringExtra("trailerYtId")?.takeIf { it.isNotBlank() }?.let { ytId ->
                val opened = runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$ytId")))
                }.isSuccess
                if (!opened && runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$ytId")))
                }.isFailure) android.widget.Toast.makeText(this, R.string.trailer_no_app, android.widget.Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        if (intent.getBooleanExtra("picked", false)) {
            if (started) record()
            startAt = position
            started = false
            showSourceError(reason)
            player.stop()
            showLoading(failureMessage(R.string.player_source_failed_choose, reason, advice = false))
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", failureMessage(R.string.player_source_failed_choose, reason, advice = false))
            .putExtra("startOver", false)
            .putExtra("resumeAt", position))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<Format>()
    /** Source indices whose audio failed to play this title; the same-dub fallback skips them (see switchDubForAudio). */
    private val audioDead = mutableSetOf<Int>()

    // Embedded audio discovered on OTHER sources by the background [AudioProbe] (the playing source's own tracks are
    // read live from [audioGroups]); the audio menu merges these in so every source's audio is offered. A pick here
    // switches to that source and selects the track once it loads.
    private class ProbedAudio(val sourceIndex: Int, val lang: String?, val label: String?)
    private val probedAudioLock = Any()
    private var probedAudio: List<ProbedAudio> = emptyList()
    private var probesStarted = false
    private var pendingProbeAudio: String? = null

    /** Reads each built-in source's embedded audio tracks off the main thread so the audio menu can offer every
     *  source's languages, not only the one playing. Runs once per video; results refresh the next menu open. */
    private fun startAudioProbes() {
        synchronized(probedAudioLock) { if (probesStarted) return; probesStarted = true }
        val brand = internalSourceName(this)
        val targets = sources.withIndex().filter { it.value.title == brand && it.value.isVideo }
        if (targets.size < 2) return
        Thread({
            val found = ArrayList<ProbedAudio>()
            for ((idx, s) in targets) {
                if (isFinishing || isDestroyed) break
                // A probe that times out or fails is retried, so a slow source still contributes its dubs to the menu.
                var tracks: List<AudioProbe.Track> = emptyList()
                for (attempt in 1..PROBE_RETRIES) {
                    tracks = runCatching { AudioProbe.extract(this, s.url, s.headers, s.url.contains(".m3u8", ignoreCase = true)) }
                        .onFailure { android.util.Log.w("Alder", "audio probe #$idx attempt $attempt failed", it) }
                        .getOrDefault(emptyList())
                    if (tracks.isNotEmpty() || isFinishing || isDestroyed) break
                    if (attempt < PROBE_RETRIES) runCatching { Thread.sleep(700) }
                }
                for (t in tracks) if (t.lang != null || !t.label.isNullOrBlank()) found.add(ProbedAudio(idx, t.lang, t.label))
            }
            synchronized(probedAudioLock) { probedAudio = found }
            android.util.Log.i("AlderStreams", "audio probes done across ${targets.size} sources: " +
                found.groupBy { it.sourceIndex }.map { (idx, ts) -> "#$idx[" + ts.joinToString(",") { "${it.label ?: "?"}(${it.lang ?: "?"})" } + "]" }.joinToString(" "))
        }, "audio-probe-driver").start()
    }

    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val supported = groups.filter { it.isTrackSupported(0) }
        if (supported.isEmpty()) return null
        val lossless = if (decodeAudio || retryWithPcm) ({ _: Format -> false }) else losslessOutput(this, player.audioAttributes)
        val best = bestAudioFormat(supported.map { it.getTrackFormat(0) }, wantAudio, failedAudio, lossless)
        return supported.firstOrNull { it.getTrackFormat(0) === best }
    }

    private fun retryAudio(track: Tracks.Group?, reason: String) {
        val resume = position()
        if (track != null) {
            audioPicked = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(track.mediaTrackGroup, (0 until track.length).toList())).build()
        }
        if (!player.isCurrentMediaItemLive) player.seekTo(resume)
        player.prepare()
        if (!started && !stopped) {
            main.removeCallbacks(startTimeout)
            main.postDelayed(startTimeout, startWait)
        }
        android.util.Log.i("Alder", reason)
    }

    private fun selectedTrackPassesAudio(): Boolean =
        !decodeAudio && !retryWithPcm && player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { passthroughOutput(this, player.audioAttributes)(it.getTrackFormat(0)) } == true

    /** The speeds in the shared glass list, the one in use ticked and focused; picking one plays at it. */
    private fun openSpeedMenu() {
        val note = if (passthroughOut || (speed != 1f && selectedTrackPassesAudio()))
            getString(R.string.player_speed_passthrough_note) else null
        choose(getString(R.string.player_speed), SPEEDS.map(::speedLabel), message = note,
            checked = SPEEDS.indexOf(speed), widthDp = 260) { i ->
            applySpeed(SPEEDS[i])
            showControls(speedButton) // focus back on the button, and the controls' hide timer starts again
        }
    }

    /**
     * Plays at [to]. Away from 1x a passthrough stream is reopened as PCM, since the sink can only time-stretch PCM;
     * back at 1x a stream the route can pass is reopened as it is again.
     */
    private fun applySpeed(to: Float) {
        speedButton.text = speedLabel(to)
        speedButton.setTextColor(if (to != 1f) TUNGSTEN else TEXT)
        if (to == speed) return
        val reopen = if (to != 1f) passthroughOut else selectedTrackPassesAudio()
        speed = to
        Settings.pickedInPlayer(this, metaId, speed = to)
        if (reopen) {
            player.stop()
            retryAudio(null, "Audio reopened for ${speedLabel(speed)} playback")
        }
        player.setPlaybackSpeed(speed)
    }

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        if (sleepAt in 1..System.currentTimeMillis() && stillPanel.visibility != View.VISIBLE) {
            setSleepAt(0)
            showStillWatching(null)
        }
        record()
        autoQuality()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    /** Auto quality, the other half of [stalled]: once a downscale has played stably for [STABLE_MS], try the next
     *  quality up (same dub). A fresh stall will drop it again, so it tracks the link like YouTube/Netflix. */
    private fun autoQuality() {
        if (!downscaled) return
        val now = System.currentTimeMillis()
        val lastStall = stalls.lastOrNull() ?: 0L
        val stable = now - lastStall > STABLE_MS && now - downscaleAt > STABLE_MS &&
            player.playbackState == Player.STATE_READY && player.playWhenReady && player.totalBufferedDuration > 5_000
        if (!stable) return
        switchQuality(-1, "stable") // no-op if already at the top quality of this dub
        downscaled = false
        downscaleAt = 0L
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        if (prepared) {
            lastPosition = player.currentPosition
            // Left before the picture showed: nothing was seen, so it goes on, with a fresh START_WAIT.
            if (!started && !handedOff && !isTrailer) {
                player.play()
                main.removeCallbacks(startTimeout)
                main.postDelayed(startTimeout, startWait)
            }
            if (!isTrailer) {
                main.postDelayed(tickTask, TICK)
                main.post(skipTask)
            }
            if (loading.visibility == View.VISIBLE && player.playbackState == Player.STATE_BUFFERING)
                main.post(loadingTask)
        }
    }

    /** The name shown in the background-playback notification (lock screen, Recents media). */
    private fun titleForNotification(): String =
        item?.optString("name")?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra("name")?.takeIf { it.isNotBlank() }
            ?: Branding.name(this)

    private fun record() {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        // Live TV has no progress to keep, nor does a channel or event whose stream doesn't say it's live.
        if (isTrailer || !started || player.isCurrentMediaItemLive || isLiveType(type)) return
        if (episodic && !plausibleEpisode(player.duration)) return
        val item = item ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        watchPendingMs += played
        if (watchPendingMs >= WATCH_SLICE_MS) flushWatch(false)
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Keep the current episode full screen; offer the next one only near its actual end.
        // If credits timing (outroAt) is known, wait until outroAt before showing Up next.
        val shouldShowUpNext = if (outroAt > 0) position >= outroAt else duration - position < upNextWindow(duration)
        if (!upNextDismissed && upNextPanel.visibility != View.VISIBLE && shouldShowUpNext) showUpNext()
    }

    /** Sends the watched slice accumulated so far to our stats. `start` counts one play; `completed` marks the end. */
    private fun flushWatch(completed: Boolean) {
        val seconds = (watchPendingMs / 1000L).toInt()
        val done = completed || watchCompleted
        watchPendingMs = 0
        if (seconds <= 0) { if (done) watchCompleted = false; return }
        val s = sources.getOrNull(at)
        val url = s?.url.orEmpty()
        val src = when { s?.torrent != null -> "torrent"; url.contains(".m3u8") -> "hls"; url.endsWith(".mpd") -> "dash"; else -> "http" }
        val addon = s?.title?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9.-]{1,100}")) }
        val name = meta?.name ?: intent.getStringExtra("name").orEmpty()
        val start = !watchStarted
        watchStarted = true
        watchCompleted = false
        val key = statsKey; val t = type; val id = videoId
        background({ Stremio.reportActivity(key, seconds, t, id, name, start, done, src, addon) }) {}
    }

    /**
     * Reports a playback failure to our stats (event="error"), so the operator's Errors and Errors-per-100-plays
     * reflect what the player actually hit. [critical] marks a source that could not be played at all (we skipped
     * to the next one); a retry that later recovers is a non-critical blip.
     */
    private fun reportError(failure: Failure, critical: Boolean) {
        val key = statsKey
        if (key.isBlank()) return
        val s = sources.getOrNull(at)
        val url = s?.url.orEmpty()
        val src = when { s?.torrent != null -> "torrent"; url.contains(".m3u8") -> "hls"; url.endsWith(".mpd") -> "dash"; else -> "http" }
        val addon = s?.title?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9.-]{1,100}")) }
        val name = meta?.name ?: intent.getStringExtra("name").orEmpty()
        val t = type; val id = videoId
        background({ Stremio.reportActivity(key, 0, t, id, name, false, false, src, addon, event = "error", code = failure.name, critical = critical) }) {}
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(onlyAired: Boolean = true): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        val next = videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
        return if (onlyAired && next != null && !isEpisodeAired(next.released)) null else next
    }

    /** The current dub's sources, best quality first (same audio language; all untagged for the Original audio).
     *  Sources whose audio failed this title are left out so the quality ladder never bounces back into them. */
    private fun dubPeers(): List<Int> {
        val langs = sources.getOrNull(at)?.audioLanguages ?: return emptyList()
        return sources.indices.filter { it !in audioDead && sources[it].audioLanguages == langs }
            .sortedBy { PlaybackOrder.score(sources[it]) }
    }

    private fun atLowestQuality(): Boolean {
        val peers = dubPeers(); val pos = peers.indexOf(at)
        return pos < 0 || pos >= peers.size - 1
    }

    /** Switch to another quality of the SAME dub, [step] lower (+1) or higher (-1). False at the end of the ladder. */
    private fun switchQuality(step: Int, reason: String): Boolean {
        val peers = dubPeers(); val pos = peers.indexOf(at)
        if (pos < 0) return false
        val target = peers.getOrNull(pos + step) ?: return false
        if (target == at) return false
        val resume = position()
        if (offeringLower) dismissStall()
        at = target
        switchGraceUntil = System.currentTimeMillis() + 6_000L
        resetSourceState()
        android.util.Log.i("AlderStreams", "adaptive quality -> #${target} ${sources[target].height}p ($reason) at ${resume / 1000}s")
        load(resume)
        return true
    }

    /**
     * Every audio track of the current source failed to play. The picture can still be fine, so before next() moves
     * the viewer to another language, play the same dub from another source: the other sources with the same audio
     * language, best quality first, skipping any already tried this title. Returns true if one was loaded.
     */
    private fun switchDubForAudio(reason: Failure): Boolean {
        val langs = sources.getOrNull(at)?.audioLanguages ?: return false
        val target = sources.indices
            .filter { it != at && it !in audioDead && sources[it].audioLanguages == langs }
            .minByOrNull { PlaybackOrder.score(sources[it]) } ?: return false
        val resume = position()
        audioDead += at
        at = target
        switchGraceUntil = System.currentTimeMillis() + 6_000L
        resetSourceState()
        android.util.Log.i("AlderStreams", "audio dead in #$at; same-dub fallback -> #$target ${sources[target].height}p ($reason) at ${resume / 1000}s")
        load(resume, failureMessage(R.string.player_source_failed_next, reason, advice = false))
        return true
    }

    /**
     * The current source's own audio is fully dead but its video is fine. Keep playing THIS video and borrow the
     * audio from another source of the SAME dub (same audio language), best quality first, skipping any already
     * tried. Returns true if a borrow was set up (load() then rebuilds a merged video+audio source); false when
     * there is no donor, after falling back to a plain source switch.
     */
    private fun borrowAudioFromSameDub(reason: Failure): Boolean {
        if (borrowedFrom != null) return true
        val current = sources.getOrNull(at) ?: return false
        val langs = current.audioLanguages
        val donor = sources.indices
            .filter { it != at && it !in audioDead && sources[it].audioLanguages == langs }
            .minByOrNull { PlaybackOrder.score(sources[it]) }
            ?: return switchDubForAudio(reason) // no same-dub donor left: switch source as before
        val donorStream = sources[donor]
        borrowedFor = donorStream
        val resume = position()
        val stale = { destroyed || isFinishing || isDestroyed || sources.getOrNull(at) !== current || borrowedFor !== donorStream }
        fun useDonor(o: Opened) {
            if (closeStaleOpen(o, stale())) return
            borrowedFrom = donor
            borrowedOpened = o
            borrowedFor = null
            borrowVideoFor = at
            borrowApplied = false
            switchGraceUntil = System.currentTimeMillis() + 6_000L
            android.util.Log.i("AlderStreams", "borrow audio from #$donor ${donorStream.height}p for video #$at ($reason) at ${resume / 1000}s")
            load(resume, failureMessage(R.string.player_source_failed_next, reason, advice = false))
        }
        if (donorStream.opensAtOnce) {
            val o = try {
                donorStream.open(this)
            } catch (e: Exception) {
                android.util.Log.w("Alder", "audio donor didn't open", e)
                borrowedFor = null
                audioDead += donor
                return switchDubForAudio(reason)
            }
            useDonor(o)
            return true
        }
        val episode = if (isTrailer || type == "movie" || isLiveType(type)) null else {
            meta?.videos?.firstOrNull { it.id == videoId && it.season >= 0 && it.episode > 0 }
                ?.let { it.season to it.episode }
                ?: episodeNumbers(videoId)
                ?: intent.getStringExtra("episode")?.substringBefore("  ·  ")?.let(::parseEpisodeLabel)
        }
        background({
            val o = donorStream.open(applicationContext, episode, stale)
            synchronized(openLock) { if (closeStaleOpen(o, stale())) null else o.also { inFlightOpened += it } }
        }) { r ->
            val o = r.getOrNull()
            if (o != null) synchronized(openLock) { inFlightOpened.remove(o) }
            if (closeStaleOpen(o, stale())) return@background
            if (o == null) {
                android.util.Log.w("Alder", "audio donor didn't open", r.exceptionOrNull())
                borrowedFor = null
                audioDead += donor
                if (!switchDubForAudio(reason)) { startAt = resume; started = false; next(reason) }
                return@background
            }
            useDonor(o)
        }
        return true
    }

    /** Stop borrowing audio and release the donor; the current source plays on with its own audio again. */
    private fun clearBorrow() {
        borrowedOpened?.close()
        borrowedOpened = null
        borrowedFrom = null
        borrowVideoFor = null
        borrowedFor = null
        borrowApplied = false
    }

    /**
     * While borrowing, the merged source offers this video plus the donor's audio. Force this source's video and the
     * donor's audio: the current source is added first, so its video group precedes the donor's, and the donor's
     * audio is the last audio group. Idempotent (guarded by [borrowApplied]).
     */
    private fun applyBorrowedAudio(tracks: Tracks) {
        val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val donor = audio.lastOrNull() ?: return
        audioGroups = audio
        val donorTracks = (0 until donor.length).filter { donor.isTrackSupported(it) }.ifEmpty { listOf(0) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(video.mediaTrackGroup, (0 until video.length).toList()))
            .setOverrideForType(TrackSelectionOverride(donor.mediaTrackGroup, donorTracks))
            .build()
        audioPicked = true
        android.util.Log.i("AlderStreams", "borrow applied: video #$at + audio #${borrowedFrom} (${donorTracks.size} donor tracks)")
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        if (now < switchGraceUntil) return // just switched quality: let the new source buffer before judging it
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        // Download speed against the file's bitrate tells a slow link (speed below bitrate) from a short buffer.
        val size = sources.getOrNull(at)?.size ?: 0L
        val fileKbps = if (size > 0 && player.duration > 0) size * 8 / player.duration else -1
        android.util.Log.i("Alder", "stall: download ${DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate / 1000} kbps, " +
            "file $fileKbps kbps, buffered ${player.totalBufferedDuration} ms, heap ${Runtime.getRuntime().maxMemory() shr 20} MB")
        // Auto quality: a stall drops one quality step in the SAME dub at once (never another dub).
        if (!atLowestQuality() && switchQuality(1, "stall")) { downscaled = true; downscaleAt = now; return }
        val height = sources[at].height
        if (stalls.size != 3 || offeringLower || upNextPanel.visibility == View.VISIBLE || lowerSourceHeight(height) == null) return
        showStall(height)
    }

    private fun showStall(height: Int) {
        val lower = lowerSourceHeight(height) ?: return
        offeringLower = true
        stallPanel.removeAllViews()
        val card = column(Gravity.START).apply {
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = videoGlass(14)
            addView(text(getString(R.string.player_switch_prompt, lower), SERIF_TITLE, serif = true))
            addView(text(getString(R.string.player_switch_desc, height, clock(position())),
                SMALL, color = TEXT_2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            val actions = LinearLayout(this@PlayerActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val lowerButton = pill(getString(R.string.player_switch_to, lower)).apply { setOnClickListener { lowerQuality() } }
            actions.addView(lowerButton, wrap())
            actions.addView(chip(getString(R.string.player_keep, height)).apply { setOnClickListener { dismissStall() } },
                wrap().apply { marginStart = dp(6) })
            actions.addView(chip(R.string.player_other_sources).apply { setOnClickListener { changeStream() } },
                wrap().apply { marginStart = dp(6) })
            addView(actions, wrap().apply { topMargin = dp(12) })
            lowerButton.post { lowerButton.requestFocus() }
        }
        stallPanel.addView(withClose(card) { dismissStall() }, FrameLayout.LayoutParams(dp(460), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
    }

    private fun dismissStall() {
        offeringLower = false
        stallPanel.visibility = View.GONE
        stallPanel.removeAllViews()
    }

    private fun showSourceError(reason: Failure) {
        stallPanel.removeAllViews()
        val card = column(Gravity.START,
            caps(R.string.player_source_failed, TUNGSTEN),
            text(failureMessage(R.string.player_try_another, reason), SERIF_TITLE, serif = true).marginTop(6),
            pill(R.string.player_other_sources).apply { setOnClickListener { changeStream() } }.marginTop(12)
        ).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = videoGlass(14)
        }
        stallPanel.addView(withClose(card) { dismissStall() }, FrameLayout.LayoutParams(dp(310), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        stallPanel.visibility = View.VISIBLE
        card.getChildAt(2).requestFocus()
    }

    /** Continue at the same second with one quality step lower. */
    private fun lowerQuality() {
        val height = sources[at].height
        val lower = lowerSourceHeight(height) ?: return
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("pick")
        }.putExtra("maxHeight", lower).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (episodic && !plausibleEpisode(player.duration)) return
        if (stopAfter || stillPanel.visibility == View.VISIBLE) return // Sleep: After this episode
        val next = nextVideo() ?: return
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        hideLoading()
        upNextPanel.removeAllViews()
        val playNext = pill(R.string.player_next_episode, R.drawable.ic_play).apply { setOnClickListener { playNext() } }
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            // Video can't be frosted: a dark base under the glass keeps it readable over white end cards.
            background = videoGlass(14)
            addView(text(next.title.ifBlank { getString(R.string.episode_number, next.episode) }, SERIF_TITLE, serif = true).onArt())
            addView(text("S${next.season} E${next.episode}", SMALL, color = TEXT_2).onArt(), wrap().apply { topMargin = dp(5) })
            addView(playNext, wrap().apply { topMargin = dp(16) })
        }
        upNextPanel.addView(withClose(content) { dismissUpNext() }, FrameLayout.LayoutParams(dp(360), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        upNextPanel.visibility = View.VISIBLE
        playNext.requestFocus()
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        upNextPanel.visibility = View.GONE
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here.
     *  [quietly]: episodes in a row that ended with no key pressed (0 when the viewer chose it). */
    private fun playNext(quietly: Int = 0) {
        val next = nextVideo() ?: return finish()
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}")
            .putExtra("logo", meta?.logo).putExtra("still", next.thumbnail).putExtra("runtime", meta?.runtime)
            .putExtra("episode", "S${next.season} E${next.episode}  ·  ${next.title}")
            .putExtra("quiet", quietly).putExtra("sleepAt", sleepAt))
        finish()
    }

    /**
     * "Still watching?", in the Up next place: before the next episode once [QUIET_EPISODES] ended with no key pressed
     * ([next]), or when the sleep timer runs out (null: Resume). Playback stops, and the screen may sleep.
     */
    private fun showStillWatching(next: Video?) {
        if (offeringLower) dismissStall()
        if (shown) hideControls(force = true)
        options.visibility = View.GONE
        upNextPanel.visibility = View.GONE
        hideLoading()
        stillPanel.removeAllViews()
        stillPanel.visibility = View.VISIBLE
        player.pause()
        pausedShade.visibility = View.VISIBLE
        view.keepScreenOn = false // nobody may be watching: let the TV's own screen saver and sleep take over
        val play = pill(if (next != null) getString(R.string.player_play_episode, next.season, next.episode)
            else getString(R.string.action_resume), R.drawable.ic_play).apply {
            setOnClickListener { if (next != null) playNext() else leaveStillWatching() }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(play, wrap())
        actions.addView(chip(R.string.player_exit_home).apply {
            setOnClickListener {
                startActivity(Intent(this@PlayerActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }, wrap().apply { marginStart = dp(8) })
        val title = next?.let { it.title.ifBlank { getString(R.string.episode_number, it.episode) } } ?: pauseTitle.text.toString()
        val why = if (next != null) getString(R.string.player_quiet_episodes, QUIET_EPISODES) else getString(R.string.player_sleep_paused)
        val content = column(Gravity.START).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = videoGlass(14)
            addView(caps(R.string.player_still_watching, TUNGSTEN).onArt())
            addView(text(title, SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(why, SMALL, color = TEXT_2).onArt(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(actions, wrap().apply { topMargin = dp(16) })
        }
        stillPanel.addView(withClose(content) { leaveStillWatching(resume = false) }, FrameLayout.LayoutParams(dp(500), -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(68)
        })
        play.requestFocus()
    }

    /** Back or Resume on "Still watching?": at the end the player closes; mid-episode it stays paused with the controls. */
    private fun leaveStillWatching(resume: Boolean = true) {
        if (player.playbackState == Player.STATE_ENDED) return finish()
        stillPanel.visibility = View.GONE
        stillPanel.removeAllViews()
        view.keepScreenOn = true
        if (resume) player.play()
        showControls(playPause)
    }

    private fun setSleepAt(at: Long) {
        sleepAt = at
        intent.putExtra("sleepAt", at) // so another source of this video keeps the timer
    }

    /** OK on the Sleep row: Off → After this episode (series) → In 60 min → Off. */
    private fun cycleSleep() {
        when {
            stopAfter -> { stopAfter = false; setSleepAt(System.currentTimeMillis() + SLEEP_TIMER) }
            sleepAt > 0 -> setSleepAt(0)
            episodic -> stopAfter = true
            else -> setSleepAt(System.currentTimeMillis() + SLEEP_TIMER)
        }
        intent.putExtra("stopAfter", stopAfter)
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun push() {
        if (isTrailer || !watched || player.isCurrentMediaItemLive || isLiveType(type)) return
        val key = authKey ?: return
        val snapshot = item?.let { JSONObject(it.toString()) } ?: return
        ProgressSaves.keep(key, snapshot, provisionalProgress)
        background({ syncWatchProgress(key) }) { result ->
            result.getOrNull()?.let { failure ->
                android.widget.Toast.makeText(this, failureMessage(R.string.progress_saved_on_tv, failure, source = "Stremio", advice = false),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Someone is awake: the count of episodes with no key pressed starts again (also for another source of this one).
        if (event.action == KeyEvent.ACTION_DOWN && quiet != 0) { quiet = 0; intent.removeExtra("quiet") }
        if (stillPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) leaveStillWatching(resume = false)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (upNextPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    wakeKey = KeyEvent.KEYCODE_BACK
                    dismissUpNext()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (stallPanel.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismissStall()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!started) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) {
                    resolvingStreams?.cancel()
                    resolvingStreams = null
                    finish()
                }
                return true
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        if (options.visibility == View.VISIBLE) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (!down) closeOptions()
            return true
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            (skip.tag as? IntroSegment)?.takeIf { canSeek() }?.let { player.seekTo(it.endMs) }
            skip.visibility = View.GONE
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        // Back with the controls up closes them; the next Back leaves the player.
        if (shown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (chapterSnapMode) {
                if (!down) setChapterSnapMode(false)
                return true
            }
            if (!down) hideControls(force = true)
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                wakeKey = event.keyCode
                if (player.playWhenReady) player.pause() else player.play()
                showControls(playPause)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK
    // jumps to its end. The credits start, when known, is when Up next appears, rather than the last 4% guess.
    // ponytail: shows with IMDb ids only (anime episode ids under an IMDb show count); anime with no IMDb id
    // (AniSkip by MAL id) and films' credits aren't asked for.
    private lateinit var skip: TextView
    private var segments = emptyList<IntroSegment>()
    private var introDbSegments = emptyList<IntroSegment>()
    private var outroAt = 0L
    private var introDbOutroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<IntroSegment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private var introDbAsked = false

    private fun fetchSegments() {
        if (!episodic || introDbAsked) return
        // A Kitsu or MAL episode id needs the show's videos for its season and number, so it waits for the meta.
        val (imdbId, season, episode) = introDbEpisode(metaId, videoId, meta?.videos) ?: return
        introDbAsked = true
        background({
            val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=$imdbId&season=$season&episode=$episode"))
            fun part(name: String) = json.optJSONObject(name)?.let { seg ->
                val startSec = seg.optDouble("start_sec", -1.0)
                val endSec = seg.optDouble("end_sec", -1.0)
                val confidence = seg.optDouble("confidence", 1.0)
                // Filter out low confidence segments (must be >= 0.8)
                if (confidence < 0.8 || startSec < 0 || endSec <= startSec) null
                else (startSec * 1000).toLong() to (endSec * 1000).toLong()
            }
            Triple(part("intro"), part("recap"), part("outro"))
        }) { r ->
            val (intro, recap, outro) = r.getOrNull() ?: return@background
            introDbSegments = listOfNotNull(
                recap?.let { IntroSegment("Skip recap", it.first, it.second) },
                intro?.let { IntroSegment("Skip intro", it.first, it.second) }
            )
            introDbOutroAt = outro?.first ?: 0L
            reconcileSegments()
        }
    }

    private fun reconcileSegments() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val mkvIntros = MkvChapters.findIntroSegments(mkvChapters, duration)
        if (mkvIntros.isNotEmpty()) {
            segments = mkvIntros.map { IntroSegment(it.label, it.startMs, it.endMs) }
            outroAt = introDbOutroAt
        } else {
            // Reject zero-second starts from IntroDB if file has chapters indicating a later start or cold open
            val hasColdOpenChapter = mkvChapters.any { it.startMs in 1L..120_000L }
            segments = introDbSegments.filterNot { seg ->
                seg.startMs == 0L && hasColdOpenChapter
            }
            outroAt = introDbOutroAt
        }
        updateMarkers()
    }

    private fun updateMarkers() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        markers = TimelineMarkers.merge(mkvChapters, segments, outroAt, duration)
        bar.setMarkers(markers)
        if (shown) updateProgress()
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.startMs && position < it.endMs - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            // The label names the kind ("Skip recap"); the words come from the viewer's language.
            skip.text = getString(if (now.label.contains("recap", ignoreCase = true)) R.string.skip_recap else R.string.skip_intro) + "   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && player.duration - position < 60_000 &&
            !upNextDismissed && upNextPanel.visibility != View.VISIBLE && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- player controls ----------
    private lateinit var controls: FrameLayout
    private lateinit var bar: SeekBar
    private lateinit var chapterPill: TextView
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private lateinit var audioButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var trackStatus: TextView // "English  ·  Spanish subtitles", under the bar whenever the controls are up
    private lateinit var nextCell: FrameLayout
    private lateinit var playerLogo: ImageView
    private lateinit var topName: TextView
    private lateinit var pauseTitle: TextView
    private lateinit var pauseInfo: LinearLayout
    private lateinit var pauseFacts: TextView
    private lateinit var pauseBadges: LinearLayout
    private lateinit var subtitle: TextView // the year or S5 E2
    private val buttonLabels = mutableMapOf<View, TextView>()
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private var chapterSnapMode = false
    private var markers = emptyList<Marker>()
    private var mkvChapters = emptyList<MkvChapter>()
    private var shownBadges = emptyList<PlayerBadge>()
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 } }

    private fun pillBackground(snapMode: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        if (snapMode) {
            setColor(0xE60E0C0A.toInt())
            setStroke(dp(1), Screen.TUNGSTEN_RIM)
        } else {
            setColor(0xCC0E0C0A.toInt())
        }
    }

    private fun setChapterSnapMode(enabled: Boolean) {
        if (chapterSnapMode == enabled) return
        chapterSnapMode = enabled
        chapterPill.background = pillBackground(enabled)
        chapterPill.setTextColor(if (enabled) Screen.TUNGSTEN else Screen.TEXT_2)
        bar.invalidate()
        if (shown) updateProgress()
    }

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name").orEmpty()
        playerLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.GONE }
        topName = text(name, SERIF_TITLE, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val logoUrl = intent.getStringExtra("logo") ?: Images.logoFor(metaId)
        Images.load(logoUrl, playerLogo, dp(300), urgent = true, failed = {
            playerLogo.visibility = View.GONE
            topName.visibility = View.VISIBLE
        }) {
            playerLogo.visibility = View.VISIBLE
            topName.visibility = View.GONE
        }
        pauseTitle = text(intent.getStringExtra("episode")?.substringAfter("  ·  ", "")?.takeIf { it.isNotBlank() }
            ?: name, SERIF_HERO, serif = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = text(if (isTrailer) getString(R.string.action_trailer) else intent.getStringExtra("episode")?.substringBefore("  ·  ").orEmpty(), SMALL, color = TEXT_2).onArt()
        pauseFacts = text(if (sources.isEmpty() && !isTrailer) getString(R.string.finding_sources) else "", SMALL, color = TEXT_2).onArt()
        pauseBadges = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val pauseMetaRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pauseFacts, wrap().apply { marginEnd = dp(10) })
            addView(pauseBadges, wrap())
        }
        pauseInfo = column(Gravity.START).apply {
            if (isTrailer) addView(caps(R.string.action_trailer, TUNGSTEN).onArt())
            addView(pauseTitle, LinearLayout.LayoutParams(dp(640), -2).apply { if (isTrailer) topMargin = dp(6) })
            addView(pauseMetaRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        refreshBadges()

        trackStatus = text("", SMALL, color = TEXT_2).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        chapterPill = text("", SMALL, color = TEXT_2).onArt().apply {
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = pillBackground(false)
            visibility = View.INVISIBLE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        elapsed = text("", SMALL).onArt()
        length = text("", SMALL).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            contentDescription = getString(R.string.player_seek)
            setOnFocusChangeListener { _, focused ->
                if (!focused) setChapterSnapMode(false)
                invalidate()
            }
            setOnClickListener {
                if (chapterSnapMode) {
                    if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                    setChapterSnapMode(false)
                } else {
                    if (player.playWhenReady) player.pause() else player.play()
                }
            }
            onDrag = { fraction, done ->
                val duration: Long = player.duration
                if (duration != C.TIME_UNSET && duration > 0 && !player.isCurrentMediaItemLive && canSeek()) {
                    scrubTo = (fraction * duration).toLong().coerceIn(0L, maxOf(0L, duration - 1_000L))
                    updateProgress()
                    main.removeCallbacks(hideTask)
                    if (done) {
                        player.seekTo(scrubTo)
                        scrubTo = -1
                        updateProgress()
                        if (player.playWhenReady) main.postDelayed(hideTask, HIDE)
                    }
                }
            }
            setOnKeyListener { _, code, event ->
                val current = if (scrubTo >= 0) scrubTo else player.currentPosition
                if (chapterSnapMode) {
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val next = TimelineMarkers.findNext(markers, current)
                            if (next != null) {
                                scrubTo = next.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val prev = TimelineMarkers.findPrev(markers, current)
                            if (prev != null) {
                                scrubTo = prev.timeMs
                                updateProgress()
                                main.removeCallbacks(seekTask)
                                main.postDelayed(seekTask, SEEK_SETTLE)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            setChapterSnapMode(false)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 }
                            setChapterSnapMode(false)
                            true
                        }
                        else -> false
                    }
                } else {
                    val by = when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (event.action == KeyEvent.ACTION_DOWN && markers.size > 1) {
                                setChapterSnapMode(true)
                                return@setOnKeyListener true
                            }
                            return@setOnKeyListener false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (scrubTo >= 0) {
                                scrubTo = -1
                                updateProgress()
                            }
                            return@setOnKeyListener false
                        }
                        else -> return@setOnKeyListener false
                    }
                    if (event.action == KeyEvent.ACTION_DOWN)
                        scrub(by * when { event.repeatCount < 10 -> SKIP; event.repeatCount < 30 -> 30_000L; else -> 60_000L })
                    true
                }
            }
        }

        audioButton = button(getString(R.string.player_audio_subtitles), R.drawable.ic_subtitles) { openOptions() }
        // Opens the Audio tab (dub languages); the language icon fits picking an audio track better than the old layers icon.
        val streamButton = button(getString(R.string.player_change_stream), R.drawable.ic_language) { openOptions(1) }
        // Shows the speed itself rather than an icon; OK opens the list of speeds above it.
        speedButton = button(getString(R.string.player_speed), 0) { openSpeedMenu() }.apply {
            text = speedLabel(speed)
            textSize = SMALL
            typeface = text("", SMALL, bold = true).typeface // Screen.fonts is private
            setTextColor(if (speed != 1f) TUNGSTEN else TEXT)
            maxLines = 1
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), 0)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
        }
        playPause = button(getString(R.string.player_pause), R.drawable.ic_pause, primary = true) {
            if (player.playWhenReady) player.pause() else player.play()
        }
        val nextButton = button(getString(R.string.player_next_episode), R.drawable.ic_next_episode) { playNext() }
        val infoButton = button(getString(R.string.player_info), R.drawable.ic_info) {
            // Stay on the back stack so Back from the details page returns here, paused, instead of leaving the player.
            startActivity(Intent(this, DetailsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("type", type).putExtra("id", metaId)
                .putExtra("name", intent.getStringExtra("name"))
                .putExtra("poster", intent.getStringExtra("poster"))
                .putExtra("background", intent.getStringExtra("background")))
        }
        nextCell = buttonCell(nextButton).apply { visibility = View.GONE }
        val buttons = LinearLayout(this).apply {
            clipChildren = false // a focused button's label is wider than its cell
            clipToPadding = false
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            if (!isTrailer) {
                addView(LinearLayout(context).apply {
                    clipChildren = false; clipToPadding = false
                    gravity = Gravity.END or Gravity.TOP
                    addView(buttonCell(streamButton))
                    addView(buttonCell(audioButton))
                    addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                        LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginEnd = dp(10) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(buttonCell(playPause, true))
            if (!isTrailer) {
                addView(LinearLayout(context).apply {
                    clipChildren = false; clipToPadding = false
                    gravity = Gravity.START or Gravity.TOP
                    addView(View(context).apply { setBackgroundColor(GLASS_EDGE) },
                        LinearLayout.LayoutParams(dp(1), dp(22)).apply { topMargin = dp(14); marginStart = dp(10) })
                    addView(buttonCell(speedButton))
                    addView(nextCell)
                    addView(buttonCell(infoButton))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        for (button in listOf(streamButton, audioButton, speedButton, playPause, nextButton, infoButton))
            button.nextFocusUpId = bar.id
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id
        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x990E0C0A.toInt(), 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xBF0E0C0A.toInt(), 0x590E0C0A, 0x000E0C0A))
            }, FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM))
            addView(playerLogo, FrameLayout.LayoutParams(dp(150), dp(50)).apply {
                leftMargin = dp(PAD); topMargin = dp(26)
            })
            addView(topName, FrameLayout.LayoutParams(dp(250), -2).apply {
                leftMargin = dp(PAD); topMargin = dp(31)
            })
            addView(TextClock(this@PlayerActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = SMALL; setTextColor(TEXT_2); typeface = text("", SMALL).typeface
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(34); rightMargin = dp(PAD)
            })
            addView(pauseInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(168)
            })
            addView(chapterPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(137)
            })
            addView(bar, FrameLayout.LayoutParams(-1, dp(24), Gravity.BOTTOM).apply {
                leftMargin = dp(PAD); rightMargin = dp(PAD); bottomMargin = dp(111)
            })
            addView(elapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(91)
            })
            addView(length, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(PAD); bottomMargin = dp(91)
            })
            addView(buttons, FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM).apply {
                bottomMargin = dp(11)
            })
        }
    }

    private fun refreshBadges(tracks: Tracks = player.currentTracks) {
        if (!::pauseBadges.isInitialized) return
        if (isTrailer) {
            pauseBadges.visibility = View.GONE
            return
        }
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)
        val textGroup = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subLang = if (textGroup == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) null
        else (if (isAddonSub(textGroup)) chosenSub?.lang else textGroup.getTrackFormat(0).language)
            ?.let(Languages::code)?.let(Languages::name) ?: getString(R.string.state_on)
        val videoHeight = player.videoFormat?.height?.takeIf { it > 0 }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.height ?: 0
        val videoWidth = player.videoFormat?.width?.takeIf { it > 0 }
            ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.width ?: 0
        val badges = playerBadges(
            stream = sources.getOrNull(at),
            videoHeight = videoHeight,
            videoWidth = videoWidth,
            audioLanguage = audio?.language,
            audioMime = audio?.sampleMimeType,
            audioLabel = audio?.label,
            audioChannels = audio?.channelCount?.takeIf { it > 0 } ?: 0,
            subtitleLanguage = subLang,
        )
        android.util.Log.i("AlderStreams", "badge: vf=${player.videoFormat?.width}x${player.videoFormat?.height} " +
            "stream.height=${sources.getOrNull(at)?.height} -> ${badges.joinToString(",") { it.label }}")
        if (badges == shownBadges && pauseBadges.childCount > 0) return
        shownBadges = badges
        pauseBadges.removeAllViews()
        pauseBadges.visibility = if (badges.isEmpty()) View.GONE else View.VISIBLE
        badges.forEachIndexed { index, badge ->
            pauseBadges.addView(badgeView(badge), LinearLayout.LayoutParams(-2, dp(20)).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
    }

    /** Monochrome House Lights brand badge: custom vector marks for Dolby / DTS / Subtitles / Debrid, or crisp typographic lockups. */
    private fun badgeView(b: PlayerBadge): View {
        val rim = if (b.accent) 0x80F3D9B1.toInt() else 0x66FFF0DC
        val frame = GradientDrawable().apply {
            setColor(0xEB0E0C0A.toInt())
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), rim)
        }
        return when (b.brand) {
            BadgeBrand.DOLBY -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dolbyIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(13), dp(9)).apply { marginEnd = dp(5) })
                addView(text("DOLBY ${b.label}", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.DTS -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
                addView(dtsIcon(if (b.accent) TUNGSTEN else TEXT), LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(4) })
                val suffix = when (b.label) { "HD" -> "-HD"; "X" -> ":X"; else -> "" }
                addView(text("DTS$suffix", 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT).apply {
                    includeFontPadding = false
                    letterSpacing = 0.06f
                }, wrap())
            }
            BadgeBrand.SUBTITLES -> LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = frame
                setPadding(dp(5), 0, dp(6), 0)
                addView(ImageView(context).apply {
                    setImageDrawable(getDrawable(R.drawable.ic_subtitles)?.mutate()?.apply { setTint(TEXT_2) })
                }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) })
                addView(text(b.label, 9.5f, bold = true, color = TEXT_2).apply {
                    includeFontPadding = false
                    letterSpacing = 0.05f
                }, wrap())
            }
            BadgeBrand.DEBRID -> if (b.label == "TB") {
                FrameLayout(this).apply {
                    background = frame
                    setPadding(dp(5), 0, dp(5), 0)
                    addView(torboxIcon(TUNGSTEN), FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
                }
            } else {
                text(b.code ?: b.label, 9.5f, bold = true, color = TUNGSTEN).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    letterSpacing = 0.04f
                    background = frame
                    setPadding(dp(6), 0, dp(6), 0)
                }
            }
            BadgeBrand.NONE -> text(b.label, 9.5f, bold = true, color = if (b.accent) TUNGSTEN else TEXT_2).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = 0.05f
                background = frame
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
    }

    /** TorBox isometric 3D cube mark, drawn in [color]. */
    private fun torboxIcon(color: Int) = object : View(this) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.25f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val path = android.graphics.Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val topY = h * 0.08f
            val midY = h * 0.32f
            val botMidY = h * 0.68f
            val botY = h * 0.92f
            val leftX = w * 0.10f
            val rightX = w * 0.90f
            path.reset()
            // Outer hexagon of the cube
            path.moveTo(cx, topY)
            path.lineTo(rightX, midY)
            path.lineTo(rightX, botMidY)
            path.lineTo(cx, botY)
            path.lineTo(leftX, botMidY)
            path.lineTo(leftX, midY)
            path.close()
            // Inner Y edges of the cube
            path.moveTo(leftX, midY)
            path.lineTo(cx, h * 0.52f)
            path.lineTo(rightX, midY)
            path.moveTo(cx, h * 0.52f)
            path.lineTo(cx, botY)
            canvas.drawPath(path, stroke)
        }
    }

    /** The Dolby double-D mark (two opposing D shapes), drawn in [color]. */
    private fun dolbyIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            path.reset()
            rect.set(0f, 0f, w * 0.44f, h)
            path.addRoundRect(rect, floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), android.graphics.Path.Direction.CW)
            rect.set(w * 0.56f, 0f, w, h)
            path.addRoundRect(rect, floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r), android.graphics.Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
    }

    /** A stylized three-bar DTS acoustic slash mark, drawn in [color]. */
    private fun dtsIcon(color: Int) = object : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val barW = w * 0.22f
            val r = barW / 2f
            canvas.drawRoundRect(0f, h * 0.30f, barW, h * 0.70f, r, r, paint)
            canvas.drawRoundRect(w * 0.39f, h * 0.14f, w * 0.39f + barW, h * 0.86f, r, r, paint)
            canvas.drawRoundRect(w - barW, 0f, w, h, r, r, paint)
        }
    }

    /** An icon of [size] dp in the middle of whatever it's drawn over (a button's circle). */
    private fun centred(res: Int, size: Int, tint: Int) = getDrawable(res)?.mutate()?.let { d ->
        d.setTint(tint)
        android.graphics.drawable.LayerDrawable(arrayOf(d)).apply {
            setLayerSize(0, dp(size), dp(size)); setLayerGravity(0, Gravity.CENTER)
        }
    }

    private fun button(label: String, res: Int, primary: Boolean = false, onClick: () -> Unit): TextView {
        val words = text(label, SMALL, color = TEXT_2).onArt().apply {
            gravity = Gravity.CENTER; visibility = View.INVISIBLE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val radius = if (primary) 25 else 20
        val control = text("", BODY).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            isFocusable = true
            if (res != 0) foreground = centred(res, if (primary) 25 else 20, if (primary) BASE else TEXT)
            if (primary) {
                fun face(focused: Boolean) = GradientDrawable().apply {
                    setColor(TEXT); cornerRadius = dp(radius).toFloat()
                }
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    view.grow(focused).start()
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            } else {
                fun face(focused: Boolean) = android.graphics.drawable.LayerDrawable(arrayOf(
                    rounded(VIDEO_CONTROL_BASE, radius), glass(radius, focused)))
                background = face(false)
                setOnFocusChangeListener { view, focused ->
                    view.background = face(focused)
                    words.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                }
            }
            setOnClickListener { onClick() }
        }
        buttonLabels[control] = words
        return control
    }

    private fun buttonCell(control: TextView, primary: Boolean = false) = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        addView(control, FrameLayout.LayoutParams(dp(if (primary) 50 else 40), dp(if (primary) 50 else 40),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(buttonLabels.getValue(control), FrameLayout.LayoutParams(dp(160), dp(18),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(if (primary) 53 else 48) })
        layoutParams = LinearLayout.LayoutParams(dp(if (primary) 70 else 62), dp(76))
    }

    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.28f) // keep captions above the seek bar and buttons
            controls.visibility = View.VISIBLE
            if (!inPip) backArrowView?.visibility = View.VISIBLE
            controls.enter(FOCUS_MS).alpha(1f).start()
            updateProgress()
            (focus ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady) main.postDelayed(hideTask, HIDE)
    }

    private fun hideControls(force: Boolean = false) {
        if (!force && !player.playWhenReady) return
        setChapterSnapMode(false)
        chapterPill.visibility = View.INVISIBLE
        shown = false
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.exit(ENTER_MS).alpha(0f).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                backArrowView?.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown && options.visibility != View.VISIBLE) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        if (shown) {
            if (!bar.isIndeterminate) {
                bar.set(position, player.bufferedPosition, if (live) 0L else duration)
                elapsed.text = if (live) getString(R.string.player_live) else clock(position)
                length.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
            }
            val active = if (markers.size > 1) TimelineMarkers.findCurrent(markers, position) else null
            if ((bar.isFocused || scrubTo >= 0 || chapterSnapMode) && active != null && !live) {
                chapterPill.text = when {
                    chapterSnapMode -> "‹  ${active.title}  ›"
                    bar.isFocused && scrubTo < 0 -> getString(R.string.player_chapters, active.title)
                    else -> active.title
                }
                chapterPill.visibility = View.VISIBLE
                val knob = dp(if (bar.isFocused) 9 else 6).toFloat()
                val barWidth = bar.width.takeIf { it > 0 } ?: (controls.width - 2 * dp(PAD))
                val knobX = knob + (barWidth - 2 * knob) * (if (duration > 0) position.coerceIn(0, duration) / duration.toFloat() else 0f)
                chapterPill.post {
                    val pillWidth = chapterPill.width
                    val targetX = (knobX - pillWidth / 2f).coerceIn(0f, (barWidth - pillWidth).toFloat().coerceAtLeast(0f))
                    chapterPill.translationX = targetX
                }
            } else {
                chapterPill.visibility = View.INVISIBLE
            }
        }
        if (options.visibility == View.VISIBLE) {
            optionsBar.set(position, player.bufferedPosition, if (live) 0L else duration)
            optionsElapsed.text = if (live) getString(R.string.player_live) else clock(position)
            optionsLeft.text = if (live || duration <= 0) "" else "−${clock((duration - position).coerceAtLeast(0))}"
        }
        val remaining = (duration - position).coerceAtLeast(0)
        val end = if (duration > 0) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(System.currentTimeMillis() + (remaining / player.playbackParameters.speed).toLong())) else null
        if (started) {
            pauseFacts.text = playerPauseFacts(subtitle.text.toString(), end, isTrailer, live, getString(R.string.player_ends_at))
            pauseFacts.visibility = if (pauseFacts.text.isEmpty()) View.GONE else View.VISIBLE
        }
        main.postDelayed(progressTask, 500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * A phone's way in: one tap on the picture shows or hides the controls, a double tap on the left or right third
     * skips back or forward [SKIP] ms. The controls' own buttons take their taps first; this sees only the rest.
     */
    private fun installTouch() {
        // The readout for a swipe: brightness, volume or the time being sought to, over the middle of the picture.
        val hud = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = glass(12)
            visibility = View.GONE
        }
        screen.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val hideHud = Runnable { hud.visibility = View.GONE }
        fun readout(words: String) { hud.text = words; hud.visibility = View.VISIBLE; hud.removeCallbacks(hideHud); hud.postDelayed(hideHud, 700) }
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        var swipe = 0 // 0 undecided, 1 brightness, 2 volume, 3 seek, -1 ignore
        var startLevel = 0f
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { swipe = 0; return true }
            override fun onLongPress(e: MotionEvent) {
                // Hold to play at 2x. Not over a panel, on live video, or on a passthrough track, which cannot change speed.
                if (!started || swipe != 0 || options.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE ||
                    stillPanel.visibility == View.VISIBLE || stallPanel.visibility == View.VISIBLE) return
                if (!player.isPlaying || player.isCurrentMediaItemLive || passthroughOut || selectedTrackPassesAudio()) return
                holdingFast = true
                swipe = -1 // this touch is a hold now: its movement does not seek or change brightness
                screen.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                player.setPlaybackSpeed(2f)
                readout("2×")
                hud.removeCallbacks(hideHud) // stays up while the finger is down
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || !started || options.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE ||
                    stillPanel.visibility == View.VISIBLE || stallPanel.visibility == View.VISIBLE) return false
                if (swipe == 0) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    // A drag that begins near a screen edge belongs to the system's back/home gestures and buttons,
                    // not to the player: don't scrub there, so reaching those controls never seeks the film.
                    swipe = swipeGesture(e1.x, e1.y, dx, dy, screen.width, screen.height, dp(28))
                    if (swipe == 0) return true
                    startLevel = when (swipe) {
                        1 -> window.attributes.screenBrightness.takeIf { it >= 0f } ?: runCatching {
                            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
                        }.getOrDefault(0.5f)
                        2 -> audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        else -> 0f
                    }
                }
                val up = (e1.y - e2.y) / (screen.height * 0.7f) // a swipe over 70% of the height covers the whole range
                when (swipe) {
                    1 -> {
                        val level = (startLevel + up).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = level }
                        readout("☀  ${(level * 100).toInt()}%")
                    }
                    2 -> {
                        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val level = (startLevel + up).coerceIn(0f, 1f)
                        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(level * max), 0)
                        readout("🔊  ${Math.round(level * 100)}%")
                    }
                    3 -> {
                        if (!player.isCurrentMediaItemLive && canSeek()) {
                            scrub((-distanceX / screen.width * 90_000).toLong()) // a swipe across the width is 90 seconds
                            if (scrubTo >= 0) readout(clock(scrubTo))
                        }
                    }
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (options.visibility == View.VISIBLE || upNextPanel.visibility == View.VISIBLE || stillPanel.visibility == View.VISIBLE) return false
                if (shown) hideControls(force = true) else showControls(null)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (options.visibility == View.VISIBLE) return false
                val third = screen.width / 3f
                when {
                    e.x < third -> scrub(-SKIP)
                    e.x > 2 * third -> scrub(SKIP)
                    else -> return false
                }
                // Say what the double tap did, as phone players do: the jump, and a small tick under the finger.
                if (scrubTo >= 0) {
                    readout(if (e.x < third) "−${SKIP / 1000} s" else "+${SKIP / 1000} s")
                    screen.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                return true
            }
        })
        screen.setOnTouchListener { _, event ->
            // In picture-in-picture the window is the small floating one: a swipe meant for the system's PiP
            // controls must not scrub or change brightness/volume, or it seeks the film and drops PiP. Leave
            // the touches unhandled so the system's overlay (expand, close, play/pause) gets them.
            if (inPip) return@setOnTouchListener false
            val handled = detector.onTouchEvent(event)
            if (holdingFast && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                endFastHold()
                hud.visibility = View.GONE
            }
            handled
        }
    }

    /** A finger held on the picture plays at 2x until it lifts (touch only). [speed] stays the speed the person chose. */
    private var holdingFast = false

    private fun endFastHold() {
        if (!holdingFast) return
        holdingFast = false
        if (::status.isInitialized) player.setPlaybackSpeed(speed)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive || !canSeek()) return
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** Whether this file can jump to another second. A seek in one that can't (no index, even by bitrate) lands on
     *  0:00, so it's refused with a word instead; once per source. */
    private fun canSeek(): Boolean {
        if (player.isCurrentMediaItemSeekable) return true
        if (!toldUnseekable) {
            toldUnseekable = true
            android.widget.Toast.makeText(this, getString(R.string.player_cannot_seek), android.widget.Toast.LENGTH_LONG).show()
        }
        return false
    }
    private var toldUnseekable = false

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    /**
     * The source list over a paused player, not in its place: Back comes back to the same picture at once, with no
     * reload (a debrid link can expire meanwhile, and a file that can't seek would restart at 0:00). A source picked
     * there comes back in [onActivityResult] and plays from the same second.
     */
    private fun changeStream() {
        playAfterSources = player.playWhenReady
        player.pause()
        startActivityForResult(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true); putExtra("fromPlayer", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", position())
        }, SOURCES_REQUEST)
    }
    private var playAfterSources = false

    /** Back from the source list: the picked source at the same second, or the paused one as it was. */
    private fun sourcesClosed(resultCode: Int, data: Intent?) {
        val picked = data?.getStringExtra("sources")?.takeIf { resultCode == RESULT_OK }
        if (picked == null) {
            // The list is see-through (Alder.Over), so this screen was only paused and its picture is still there.
            if (playAfterSources) player.play()
            return
        }
        sources = JSONArray(picked).objects().map(Stream::read)
        at = 0
        resetSourceState()
        intent.putExtra("picked", true) // as before: if it fails, say so and offer the list again
        dismissStall()
        load(if (player.isCurrentMediaItemLive) 0L else position())
    }

    // ---------- playback options, after design/player-options-cinematic-minimal-glass.png ----------
    // A panel down the left over a darkening: the audio tracks, the subtitles (Off first), and Change stream with the
    // source playing, then the Sleep row. The chosen track has a tick; OK on another switches to it there and then. Back closes it.
    private lateinit var options: FrameLayout
    private lateinit var optionsBar: SeekBar
    private lateinit var optionsElapsed: TextView
    private lateinit var optionsLeft: TextView

    private fun openOptions(startTab: Int = 0) {
        hideControls(force = true)
        controls.visibility = View.GONE
        options.removeAllViews()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            clipToPadding = true
            clipChildren = true
            background = videoGlass(14)
        }
        val sheetWidth = dp(460)
        options.addView(sheet, FrameLayout.LayoutParams(sheetWidth, resources.displayMetrics.heightPixels - dp(54)).apply {
            leftMargin = dp(27); topMargin = dp(27)
        })
        if (Handheld.isHandheld(this)) {
            // No Back key on a phone: a close button over the sheet's corner, and a tap beside the sheet closes it too.
            sheet.isClickable = true
            options.setOnClickListener { closeOptions() }
            options.addView(closeButton { closeOptions() }, FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(27) + sheetWidth - dp(44) - dp(8); topMargin = dp(27) + dp(8)
            })
        }

        sheet.addView(text(R.string.player_audio_subtitles, SERIF_TITLE, serif = true), wrap())
        sheet.addView(text(listOfNotNull(pauseTitle.text.toString().takeIf { it.isNotBlank() },
            subtitle.text.toString().takeIf { it.isNotBlank() }).joinToString(DOT),
            SMALL, color = TEXT_2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, wrap().apply { topMargin = dp(4) })

        val tabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = glass(18)
        }
        sheet.addView(tabsRow, LinearLayout.LayoutParams(-1, dp(36)).apply {
            topMargin = dp(14); bottomMargin = dp(12)
        })

        val tabPills = mutableListOf<TextView>()
        val tabNames = listOf(R.string.tab_subtitles, R.string.tab_audio, R.string.tab_sync_style).map(::getString)
        var currentTab = startTab.coerceIn(0, 2)

        val panelsContainer = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
            setPadding(0, 0, 0, dp(48))
        }
        val contentScroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
                if (childCount == 0) return 0
                val screenTop = scrollY
                val screenBottom = screenTop + height
                val margin = dp(44)
                return when {
                    rect.top < screenTop + margin -> rect.top - (screenTop + margin)
                    rect.bottom > screenBottom - margin -> rect.bottom - (screenBottom - margin)
                    else -> 0
                }
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = true
            clipChildren = true
            isFillViewport = true
            isSmoothScrollingEnabled = true
            addView(panelsContainer, FrameLayout.LayoutParams(-1, -2))
        }
        sheet.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val subtitlesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val audioPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val syncPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }

        fun styleTabPill(index: Int) {
            val pill = tabPills[index]
            val isSelected = index == currentTab
            val isFocused = pill.isFocused
            pill.background = when {
                isFocused -> GradientDrawable().apply {
                    setColor(ROW_FOCUSED)
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), TUNGSTEN_RIM)
                }
                isSelected -> GradientDrawable().apply {
                    setColor(0x3DF3D9B1.toInt())
                    cornerRadius = dp(13).toFloat()
                }
                else -> GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(13).toFloat()
                }
            }
            pill.setTextColor(when {
                isFocused -> TEXT
                isSelected -> TUNGSTEN
                else -> TEXT_2
            })
        }

        fun switchTab(newTab: Int) {
            if (currentTab == newTab) return
            currentTab = newTab
            tabPills.indices.forEach { styleTabPill(it) }
            subtitlesPanel.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
            audioPanel.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
            syncPanel.visibility = if (currentTab == 2) View.VISIBLE else View.GONE
            contentScroll.scrollTo(0, 0)
        }

        tabNames.forEachIndexed { i, title ->
            val pill = text(title, SMALL, bold = true).apply {
                id = View.generateViewId()
                isFocusable = true
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        switchTab(i)
                        contentScroll.scrollTo(0, 0)
                    }
                    styleTabPill(i)
                }
                setOnClickListener {
                    switchTab(i)
                }
            }
            tabPills.add(pill)
            tabsRow.addView(pill, LinearLayout.LayoutParams(0, -1, 1f))
        }

        tabPills[0].nextFocusRightId = tabPills[1].id
        tabPills[0].nextFocusLeftId = tabPills[0].id
        tabPills[1].nextFocusLeftId = tabPills[0].id
        tabPills[1].nextFocusRightId = tabPills[2].id
        tabPills[2].nextFocusLeftId = tabPills[1].id
        tabPills[2].nextFocusRightId = tabPills[2].id

        tabPills.forEachIndexed { index, pill ->
            pill.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val activePanel = when (index) {
                        0 -> subtitlesPanel
                        1 -> audioPanel
                        else -> syncPanel
                    }
                    val target = findFirstFocusable(activePanel)
                    if (target != null) {
                        target.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        var syncTimingHeader: TextView? = null
        var quickTimingValue: TextView? = null
        val timingResetPills = mutableListOf<TextView>()

        fun updateAllTimingHeaders() {
            val label = timing(subDelay)
            syncTimingHeader?.text = getString(R.string.subtitle_timing_heading, label).uppercase()
            quickTimingValue?.text = label
            timingResetPills.forEach { it.setTextColor(if (subDelay != 0L) TUNGSTEN else TEXT_3) }
        }

        fun adjustTiming(delta: Long) {
            setSubtitleDelay((subDelay + delta).coerceIn(-10_000L, 10_000L))
            updateAllTimingHeaders()
        }

        fun resetTiming() {
            setSubtitleDelay(0L)
            updateAllTimingHeaders()
        }

        var currentBg = Settings.subtitleBackground(this)
        fun applySubtitleStyle() {
            view.subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(this@PlayerActivity),
                if (currentBg) 0xB3000000.toInt() else Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
        }

        val colors = listOf(
            "white" to getString(R.string.color_white),
            "gray" to getString(R.string.color_gray),
            "yellow" to getString(R.string.color_yellow),
            "cyan" to getString(R.string.color_cyan),
            "cream" to getString(R.string.color_cream),
            "warm_white" to getString(R.string.color_warm_white)
        )
        var currentSubColor = Settings.subtitleColorName(this)
        val colorPillUpdaters = mutableMapOf<String, () -> Unit>()

        fun selectColor(colorValue: String) {
            currentSubColor = colorValue
            Settings.setSubtitleColor(this@PlayerActivity, colorValue)
            applySubtitleStyle()
            colorPillUpdaters.values.forEach { it() }
        }

        fun createColorRow(slot: String): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pills = mutableListOf<TextView>()
            colors.forEachIndexed { idx, (colorValue, colorLabel) ->
                val isCur = currentSubColor == colorValue
                val p = text(colorLabel, SMALL, bold = true, color = if (isCur) TUNGSTEN else TEXT_2).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    background = if (isCur) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                    rowFocus(8, rest = { if (currentSubColor == colorValue) rounded(0x3DF3D9B1.toInt(), 8) else glass(8) }) { has ->
                        setTextColor(if (has || currentSubColor == colorValue) TEXT else TEXT_2)
                    }
                    setOnClickListener { selectColor(colorValue) }
                }
                pills.add(p)
                row.addView(p, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                    if (idx < colors.lastIndex) marginEnd = dp(5)
                })
            }
            colorPillUpdaters[slot] = {
                pills.forEachIndexed { i, pill ->
                    val active = colors[i].first == currentSubColor
                    pill.background = if (active) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
                    pill.setTextColor(if (active) TUNGSTEN else TEXT_2)
                }
            }
            return row
        }

        fun timingPill(label: String, isReset: Boolean = false, onClick: () -> Unit) = text(label, SMALL, bold = true,
            color = if (isReset && subDelay == 0L) TEXT_3 else TEXT_2).apply {
            if (isReset) timingResetPills.add(this)
            gravity = Gravity.CENTER
            maxLines = 1
            background = glass(8)
            rowFocus(8, rest = { glass(8) }) { has ->
                setTextColor(when {
                    has -> TEXT
                    isReset && subDelay != 0L -> TUNGSTEN
                    isReset -> TEXT_3
                    else -> TEXT_2
                })
            }
            setOnClickListener { onClick() }
        }

        fun trackRowRest(selected: Boolean) = if (selected) GradientDrawable().apply {
            setColor(0x24F3D9B1.toInt())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x4DF3D9B1.toInt())
        } else null

        class TrackRowHolder(
            val track: TrackChoice,
            val row: LinearLayout,
            val check: View,
            val name: TextView,
            val tagText: TextView,
            val badge: TextView
        )

        fun createTrackRow(
            track: TrackChoice,
            isSelected: Boolean,
            isPreferred: Boolean,
            isRecent: Boolean,
            onUpToTabs: (() -> Unit)? = null,
            onClick: () -> Unit
        ): Pair<LinearLayout, TrackRowHolder> {
            val check = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TUNGSTEN) }
                visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            }
            val name = text(track.name, BODY, color = if (isSelected) TEXT else TEXT_2).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val tagText = text(track.tag, SMALL, color = TEXT_3).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                visibility = if (track.tag.isNotEmpty()) View.VISIBLE else View.GONE
            }
            val badgeText = when {
                isSelected && !track.isOff -> getString(R.string.track_active)
                isPreferred -> getString(R.string.track_preferred)
                isRecent -> getString(R.string.track_recent)
                else -> ""
            }
            val badge = text(badgeText, 10f, bold = true, color = if (isSelected) TUNGSTEN else TEXT_2).apply {
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = rounded(if (isSelected) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                visibility = if (badgeText.isNotEmpty()) View.VISIBLE else View.GONE
            }

            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(10), 0, dp(10), 0)
                background = trackRowRest(isSelected)

                addView(check, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(9) })
                addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                addView(badge, wrap().apply { marginStart = dp(6) })
                addView(tagText, wrap().apply { marginStart = dp(6) })

                setOnFocusChangeListener { v, hasFocus ->
                    v.background = if (hasFocus) GradientDrawable().apply {
                        setColor(ROW_FOCUSED)
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), TUNGSTEN_RIM)
                    } else trackRowRest(isSelected)
                    name.setTextColor(if (hasFocus || isSelected) TEXT else TEXT_2)
                }

                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && onUpToTabs != null) {
                            onUpToTabs()
                            return@setOnKeyListener true
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            return@setOnKeyListener true
                        }
                    }
                    false
                }

                setOnClickListener { onClick() }
            }

            return row to TrackRowHolder(track, row, check, name, tagText, badge)
        }

        // Subtitles tab setup
        val subHolders = mutableListOf<TrackRowHolder>()
        var quickTimingCard: LinearLayout? = null
        var quickTimingDivider: View? = null
        // Subtitle picker level: null shows the language list, a language code shows the subtitles inside it.
        var subLangFilter: String? = null
        var pendingFocusFirst = false

        fun populateSubtitles() {
            val focusedTrackId = subHolders.firstOrNull { it.row.isFocused }?.track?.id
            val wasTimingFocused = quickTimingCard?.hasFocus() == true
            subtitlesPanel.removeAllViews()
            subHolders.clear()

            val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
            val subs = texts.filterNot(::isAddonSub)
            val loaded = texts.firstOrNull(::isAddonSub)
            var chosenSubIndex = when {
                C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> 0
                loaded?.isSelected == true -> 1 + subs.size + addonSubs.indexOf(chosenSub)
                else -> subs.indexOfFirst { it.isSelected } + 1
            }

            val subChoices = mutableListOf<TrackChoice>()
            subChoices.add(TrackChoice("sub:0", getString(R.string.state_off), isSelected = chosenSubIndex == 0, isOff = true))
            subs.forEachIndexed { i, group ->
                val format = group.getTrackFormat(0)
                subChoices.add(TrackChoice("sub:${1 + i}", trackName(format, false), getString(R.string.track_in_file),
                    Languages.code(format.language), isSelected = chosenSubIndex == 1 + i))
            }
            addonSubs.forEachIndexed { i, sub ->
                subChoices.add(TrackChoice("sub:${1 + subs.size + i}", sub.language, sub.addon,
                    Languages.code(sub.lang), isSelected = chosenSubIndex == 1 + subs.size + i))
            }

            val initialChosenSub = subChoices.getOrNull(chosenSubIndex)
            var currentSelectedSubId = initialChosenSub?.id ?: "sub:0"

            val timingCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = glass(8)
                visibility = if (currentSelectedSubId == "sub:0") View.GONE else View.VISIBLE
            }
            quickTimingCard = timingCard
            timingCard.addView(caps(R.string.quick_timing, TEXT_3), wrap().apply { bottomMargin = dp(6) })
            val timingCardButtons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val minus = timingPill("− 0.25s") { adjustTiming(-250L) }
                val reset = timingPill(timing(subDelay), isReset = true) { resetTiming() }.also { quickTimingValue = it }
                val plus = timingPill("+ 0.25s") { adjustTiming(+250L) }
                val onUpToLastTrack = View.OnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        subHolders.lastOrNull()?.row?.requestFocus()
                        return@OnKeyListener true
                    }
                    false
                }
                minus.setOnKeyListener(onUpToLastTrack)
                reset.setOnKeyListener(onUpToLastTrack)
                plus.setOnKeyListener(onUpToLastTrack)
                addView(minus, LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(6) })
                addView(reset, LinearLayout.LayoutParams(0, dp(30), 1.2f).apply { marginEnd = dp(6) })
                addView(plus, LinearLayout.LayoutParams(0, dp(30), 1f))
            }
            timingCard.addView(timingCardButtons, LinearLayout.LayoutParams(-1, dp(30)))
            timingCard.addView(caps(R.string.color, TEXT_3), wrap().apply { topMargin = dp(8); bottomMargin = dp(4) })
            timingCard.addView(createColorRow("subtitles"), LinearLayout.LayoutParams(-1, dp(30)))

            val timingDivider = divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }
                visibility = if (currentSelectedSubId == "sub:0") View.GONE else View.VISIBLE
            }
            quickTimingDivider = timingDivider

            fun updateSubSelection(selectedId: String) {
                val selChoice = subChoices.firstOrNull { it.id == selectedId }
                val timingVisible = selChoice?.isOff == false
                timingCard.visibility = if (timingVisible) View.VISIBLE else View.GONE
                timingDivider.visibility = if (timingVisible) View.VISIBLE else View.GONE

                subHolders.forEach { holder ->
                    val track = holder.track
                    val isSel = track.id == selectedId
                    holder.check.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                    holder.name.setTextColor(if (isSel || holder.row.isFocused) TEXT else TEXT_2)

                    val isPref = !track.isOff && Languages.match(track.langCode, Settings.subtitleLanguage(this@PlayerActivity)) > 0
                    val isRec = !track.isOff && Settings.recentSubtitles(this@PlayerActivity).any { Languages.match(track.langCode, it) > 0 }

                    val badgeLabel = when {
                        isSel && !track.isOff -> getString(R.string.track_active)
                        isPref -> getString(R.string.track_preferred)
                        isRec -> getString(R.string.track_recent)
                        else -> ""
                    }
                    holder.badge.text = badgeLabel
                    holder.badge.setTextColor(if (isSel) TUNGSTEN else TEXT_2)
                    holder.badge.background = rounded(if (isSel) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                    holder.badge.visibility = if (badgeLabel.isNotEmpty()) View.VISIBLE else View.GONE

                    if (!holder.row.isFocused) {
                        holder.row.background = trackRowRest(isSel)
                    }
                }
            }

            fun selectSubtitleTrack(track: TrackChoice) {
                val i = subChoices.indexOfFirst { it.id == track.id }
                if (i < 0) return
                val p = player.trackSelectionParameters.buildUpon()
                val sub = addonSubs.getOrNull(i - 1 - subs.size)
                val lang = if (i == 0) "" else sub?.lang ?: subs[i - 1].getTrackFormat(0).language
                remember(lang, addonFile = sub != null,
                    pick = when { i == 0 -> ""; sub != null -> "addon:" + sub.url; else -> "file:" + subs[i - 1].getTrackFormat(0).id.orEmpty() })
                if (i != 0) {
                    track.langCode?.let { Settings.addRecentSubtitle(this@PlayerActivity, it) }
                }
                player.trackSelectionParameters = when {
                    i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    sub == null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(subs[i - 1].mediaTrackGroup, 0)).build()
                    sub == chosenSub && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
                    else -> { chosenSub = sub; subDelay = 0L; textRenderer?.offsetUs = 0L; selectAddonSub = true; load(position(), play = player.playWhenReady); return }
                }
                chosenSubIndex = i
                currentSelectedSubId = track.id
                updateSubSelection(track.id)
            }

            // Two-level picker: choose a language first, then the subtitle inside it. "Off" sits with the languages.
            val selectedChoice = subChoices.firstOrNull { it.id == currentSelectedSubId }
            val selectedLang = selectedChoice?.takeIf { !it.isOff }?.langCode
            val offChoice = subChoices.firstOrNull { it.isOff }

            if (subLangFilter == null) {
                // One row per language, tagged with how many subtitles it has; the playing one first, then the profile's, then English.
                val byLang = LinkedHashMap<String, MutableList<TrackChoice>>()
                subChoices.filter { !it.isOff }.forEach { ch -> byLang.getOrPut(ch.langCode ?: "") { mutableListOf() }.add(ch) }
                val langs = byLang.keys.sortedWith(
                    compareBy<String>(
                        { Languages.match(it, selectedLang) <= 0 },
                        { Languages.match(it, Settings.subtitleLanguage(this)) <= 0 },
                        { !Languages.same(it, "en") },
                        { Languages.name(it) }
                    )
                )
                subtitlesPanel.addView(caps(getString(R.string.all_languages, langs.size), TEXT_3), wrap().apply {
                    topMargin = dp(6); bottomMargin = dp(4)
                })

                var first = true
                offChoice?.let { off ->
                    val (row, holder) = createTrackRow(
                        track = off,
                        isSelected = off.id == currentSelectedSubId,
                        isPreferred = false, isRecent = false,
                        onUpToTabs = if (first) { { tabPills[0].requestFocus() } } else null,
                        onClick = { selectSubtitleTrack(off) }
                    )
                    subHolders.add(holder); subtitlesPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
                    first = false
                }
                langs.forEach { code ->
                    val choice = TrackChoice(
                        id = "lang:$code",
                        name = if (code.isEmpty()) "Unknown" else Languages.name(code),
                        tag = byLang.getValue(code).size.toString(),
                        langCode = code.ifEmpty { null },
                        isSelected = when {
                            selectedChoice == null || selectedChoice.isOff -> false
                            selectedLang != null -> Languages.same(code, selectedLang)
                            else -> code.isEmpty()
                        }
                    )
                    val (row, holder) = createTrackRow(
                        track = choice,
                        isSelected = choice.isSelected,
                        isPreferred = Languages.match(choice.langCode, Settings.subtitleLanguage(this)) > 0,
                        isRecent = Settings.recentSubtitles(this).any { Languages.match(choice.langCode, it) > 0 },
                        onUpToTabs = if (first) { { tabPills[0].requestFocus() } } else null,
                        onClick = { subLangFilter = code; pendingFocusFirst = true; populateSubtitles() }
                    )
                    subHolders.add(holder); subtitlesPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
                    first = false
                }
            } else {
                val filter = subLangFilter!!
                val (backRow, backHolder) = createTrackRow(
                    track = TrackChoice(
                        id = "lang:__back",
                        name = "‹  " + (if (filter.isEmpty()) "Unknown" else Languages.name(filter)),
                        langCode = filter.ifEmpty { null }
                    ),
                    isSelected = false, isPreferred = false, isRecent = false,
                    onUpToTabs = { tabPills[0].requestFocus() },
                    onClick = { subLangFilter = null; pendingFocusFirst = true; populateSubtitles() }
                )
                subHolders.add(backHolder); subtitlesPanel.addView(backRow, LinearLayout.LayoutParams(-1, dp(36)))

                subChoices.filter { !it.isOff && (it.langCode ?: "") == filter }.forEach { track ->
                    val (row, holder) = createTrackRow(
                        track = track,
                        isSelected = track.id == currentSelectedSubId,
                        isPreferred = Languages.match(track.langCode, Settings.subtitleLanguage(this)) > 0,
                        isRecent = Settings.recentSubtitles(this).any { Languages.match(track.langCode, it) > 0 },
                        onUpToTabs = null,
                        onClick = { selectSubtitleTrack(track) }
                    )
                    subHolders.add(holder); subtitlesPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
                }
            }

            subtitlesPanel.addView(timingDivider)
            subtitlesPanel.addView(timingCard, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(4)
            })

            when {
                pendingFocusFirst -> {
                    pendingFocusFirst = false
                    (subHolders.firstOrNull { it.track.id.startsWith("sub:") } ?: subHolders.firstOrNull())?.row?.requestFocus()
                }
                focusedTrackId != null -> subHolders.firstOrNull { it.track.id == focusedTrackId }?.row?.requestFocus()
                wasTimingFocused -> timingCardButtons.getChildAt(1)?.requestFocus()
            }
        }

        populateSubtitles()
        refreshOptionsSubtitles = {
            if (options.visibility == View.VISIBLE && currentTab == 0) {
                populateSubtitles()
            }
        }

        // Audio tab setup: the built-in source's dub languages AND the loaded file's own embedded audio tracks,
        // merged into one list (deduped by language). Showing only the dubs hid a file's embedded Italian; showing
        // only the embedded tracks hid every separate dub source. An embedded track for a language a dub also offers
        // is folded into that dub (selecting the dub switches to its clean single-audio source).
        val audio = audioGroups.filter { it.isTrackSupported(0) }
        val dubChoices = languageDubChoices()
        val selectedEmbedded = audio.indexOfFirst { it.isSelected }
        val embeddedActive = audioPicked && selectedEmbedded in audio.indices
        // "Original"/blank is English for dedup and selection, so the original dub and an embedded English track
        // don't both appear.
        fun langKey(code: String?) = (if (code.isNullOrBlank()) "en" else code).lowercase()
        val embeddedChoices = audio.mapIndexed { i, group ->
            val format = group.getTrackFormat(0)
            TrackChoice(id = "audio:$i", name = trackName(format, true), langCode = Languages.code(format.language))
        }
        android.util.Log.i("AlderStreams", "embedded audio in current file (${audio.size}): " +
            embeddedChoices.joinToString(" | ") { "${it.name}(${it.langCode ?: "?"})" })
        // The language actually playing: an explicitly-picked embedded track, else the current source's dub, else the
        // file's default audio track (so a plain multi-audio file with no dub sources still opens on the right track).
        val activeLang = when {
            embeddedActive -> embeddedChoices.getOrNull(selectedEmbedded)?.langCode?.let(::langKey)
            dubChoices.any { it.isSelected } -> dubChoices.firstOrNull { it.isSelected }?.langCode?.let(::langKey)
            selectedEmbedded in audio.indices -> embeddedChoices.getOrNull(selectedEmbedded)?.langCode?.let(::langKey)
            else -> null
        }
        val seenLang = HashSet<String>()
        val mergedAudio = ArrayList<TrackChoice>()
        for (d in dubChoices) if (seenLang.add(langKey(d.langCode))) mergedAudio.add(d)
        for (e in embeddedChoices) if (seenLang.add(langKey(e.langCode))) mergedAudio.add(e)
        // Embedded audio found on the OTHER sources: a language the playing file lacks but another source carries.
        // Selecting one switches to that source and selects the track once it loads (see selectAudioTrack).
        synchronized(probedAudioLock) {
            for (pa in probedAudio) {
                val key = langKey(pa.lang)
                if (!seenLang.add(key)) continue
                val name = pa.label?.takeIf { it.isNotBlank() } ?: pa.lang?.let { Languages.name(it) } ?: pa.lang ?: "Audio"
                mergedAudio.add(TrackChoice(id = "probe:${pa.sourceIndex}:$key", name = name, langCode = pa.lang))
            }
        }
        val audioChoices: List<TrackChoice> = mergedAudio.map { it.copy(isSelected = langKey(it.langCode) == activeLang) }
        var chosenAudioIndex = audioChoices.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        val (pinnedAudio, moreAudio) = partitionPinnedTracks(audioChoices, Settings.audioLanguage(this), Settings.recentAudio(this))
        val initialChosenAudio = audioChoices.getOrNull(chosenAudioIndex)
        var currentSelectedAudioId = initialChosenAudio?.id.orEmpty()

        val audioHolders = mutableListOf<TrackRowHolder>()

        fun updateAudioSelection(selectedId: String) {
            audioHolders.forEach { holder ->
                val track = holder.track
                val isSel = track.id == selectedId
                holder.check.visibility = if (isSel) View.VISIBLE else View.INVISIBLE
                holder.name.setTextColor(if (isSel || holder.row.isFocused) TEXT else TEXT_2)

                val isPref = Languages.match(track.langCode, Settings.audioLanguage(this@PlayerActivity)) > 0
                val isRec = Settings.recentAudio(this@PlayerActivity).any { Languages.match(track.langCode, it) > 0 }

                val badgeLabel = when {
                    isSel -> getString(R.string.track_active)
                    isPref -> getString(R.string.track_preferred)
                    isRec -> getString(R.string.track_recent)
                    else -> ""
                }
                holder.badge.text = badgeLabel
                holder.badge.setTextColor(if (isSel) TUNGSTEN else TEXT_2)
                holder.badge.background = rounded(if (isSel) 0x3DF3D9B1.toInt() else 0x22FFFFFF.toInt(), 4)
                holder.badge.visibility = if (badgeLabel.isNotEmpty()) View.VISIBLE else View.GONE

                if (!holder.row.isFocused) {
                    holder.row.background = trackRowRest(isSel)
                }
            }
        }

        fun selectAudioTrack(track: TrackChoice) {
            if (track.id.startsWith("probe:")) {
                // Embedded audio on another source: switch to that source, then select the track once it loads.
                val parts = track.id.removePrefix("probe:").split(":")
                val idx = parts.getOrNull(0)?.toIntOrNull() ?: return
                val lang = parts.getOrNull(1)
                track.langCode?.let {
                    wantAudio = it
                    Settings.pickedInPlayer(this@PlayerActivity, metaId, audio = it)
                    Settings.addRecentAudio(this@PlayerActivity, it)
                }
                pendingProbeAudio = lang
                if (idx in sources.indices && idx != at) {
                    val resumeAt = position()
                    at = idx
                    resetSourceState()
                    closeOptions()
                    load(resumeAt)
                } else {
                    // Already on this source: select its embedded track right away.
                    val g = audio.firstOrNull { Languages.match(it.getTrackFormat(0).language, lang) > 0 }
                    if (g != null) {
                        pendingProbeAudio = null
                        audioPicked = true
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, (0 until g.length).toList())).build()
                        chosenAudioIndex = audioChoices.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                        currentSelectedAudioId = track.id
                        updateAudioSelection(track.id)
                    }
                }
                return
            }
            if (track.id.startsWith("dub:")) {
                val idx = track.id.removePrefix("dub:").toIntOrNull() ?: return
                if (idx in sources.indices && idx != at) {
                    track.langCode?.let {
                        wantAudio = it
                        Settings.pickedInPlayer(this@PlayerActivity, metaId, audio = it)
                        Settings.addRecentAudio(this@PlayerActivity, it)
                    }
                    val resumeAt = position()
                    at = idx
                    resetSourceState()
                    closeOptions()
                    load(resumeAt)
                }
                return
            }
            val audioIdx = track.id.removePrefix("audio:").toIntOrNull() ?: return
            if (audioIdx in audio.indices) {
                audioPicked = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(audio[audioIdx].mediaTrackGroup, (0 until audio[audioIdx].length).toList())).build()
                track.langCode?.let {
                    wantAudio = it
                    Settings.pickedInPlayer(this@PlayerActivity, metaId, audio = it)
                    Settings.addRecentAudio(this@PlayerActivity, it)
                }
                chosenAudioIndex = audioChoices.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                currentSelectedAudioId = track.id
                updateAudioSelection(track.id)
            }
        }

        audioPanel.addView(caps(R.string.tracks_pinned, TEXT_3), wrap().apply {
            topMargin = dp(6); bottomMargin = dp(4)
        })

        pinnedAudio.forEachIndexed { idx, track ->
            val (row, holder) = createTrackRow(
                track = track,
                isSelected = track.id == currentSelectedAudioId,
                isPreferred = Languages.match(track.langCode, Settings.audioLanguage(this)) > 0,
                isRecent = Settings.recentAudio(this).any { Languages.match(track.langCode, it) > 0 },
                onUpToTabs = if (idx == 0) { { tabPills[1].requestFocus() } } else null,
                onClick = { selectAudioTrack(track) }
            )
            audioHolders.add(holder)
            audioPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
        }

        if (moreAudio.isNotEmpty()) {
            audioPanel.addView(divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(10), 0, dp(8)) }
            })
            audioPanel.addView(caps(getString(R.string.all_audio_tracks, moreAudio.size), TEXT_3), wrap().apply {
                bottomMargin = dp(4)
            })
            moreAudio.forEach { track ->
                val (row, holder) = createTrackRow(
                    track = track,
                    isSelected = track.id == currentSelectedAudioId,
                    isPreferred = Languages.match(track.langCode, Settings.audioLanguage(this)) > 0,
                    isRecent = Settings.recentAudio(this).any { Languages.match(track.langCode, it) > 0 },
                    onClick = { selectAudioTrack(track) }
                )
                audioHolders.add(holder)
                audioPanel.addView(row, LinearLayout.LayoutParams(-1, dp(36)))
            }
        }

        run {
            audioPanel.addView(divider().apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(8)) }
            })
            var clearOn = dialogueProcessor.enabled
            val clearStatus = text(if (clearOn) R.string.state_on else R.string.state_off, 10f, bold = true, color = if (clearOn) TUNGSTEN else TEXT_3).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = rounded(if (clearOn) 0x3DF3D9B1.toInt() else 0x1AFFFFFF.toInt(), 4)
            }
            val clearRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                // Wrap its two lines with a little air instead of a fixed 42 dp that the label + hint exactly filled,
                // clipping the hint's bottom on a denser screen or larger font.
                setPadding(dp(10), dp(6), dp(10), dp(6))
                val label = text(R.string.clear_dialogue, BODY, color = TEXT_2).apply { maxLines = 1 }
                val sub = text(R.string.clear_dialogue_hint, SMALL, color = TEXT_3).apply { maxLines = 1 }
                val left = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label)
                    addView(sub)
                }
                addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                addView(clearStatus, wrap())
                rowFocus(8)
                setOnClickListener {
                    clearOn = !dialogueProcessor.enabled
                    dialogueProcessor.enabled = clearOn
                    Settings.setClearDialogue(this@PlayerActivity, clearOn)
                    clearStatus.text = getString(if (clearOn) R.string.state_on else R.string.state_off)
                    clearStatus.setTextColor(if (clearOn) TUNGSTEN else TEXT_3)
                    clearStatus.background = rounded(if (clearOn) 0x3DF3D9B1.toInt() else 0x1AFFFFFF.toInt(), 4)
                }
            }
            audioPanel.addView(clearRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(4) })
        }

        // Sync & Style tab setup
        fun timingHeading(delay: Long) = getString(R.string.subtitle_timing_heading, timing(delay))
        val timingHeader = caps(timingHeading(subDelay), TUNGSTEN)
        syncTimingHeader = timingHeader
        syncPanel.addView(timingHeader, wrap().apply { topMargin = dp(4); bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.timing_earlier_heading, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val earlierRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pills = listOf(
                timingPill("− 0.5s") { adjustTiming(-500L) },
                timingPill("− 0.25s") { adjustTiming(-250L) },
                timingPill("− 0.1s") { adjustTiming(-100L) }
            )
            pills.forEachIndexed { idx, pill ->
                pill.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        tabPills[2].requestFocus()
                        return@setOnKeyListener true
                    }
                    false
                }
                addView(pill, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < pills.lastIndex) marginEnd = dp(6)
                })
            }
        }
        syncPanel.addView(earlierRow, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.timing_later_heading, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val laterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pills = listOf(
                timingPill("+ 0.1s") { adjustTiming(+100L) },
                timingPill("+ 0.25s") { adjustTiming(+250L) },
                timingPill("+ 0.5s") { adjustTiming(+500L) }
            )
            pills.forEachIndexed { idx, pill ->
                addView(pill, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < pills.lastIndex) marginEnd = dp(6)
                })
            }
        }
        syncPanel.addView(laterRow, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(8) })

        val resetPill = timingPill(getString(R.string.timing_reset), isReset = true) { resetTiming() }
        syncPanel.addView(resetPill, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(14) })

        syncPanel.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(10)) }
        })
        syncPanel.addView(caps(R.string.subtitle_appearance, TUNGSTEN), wrap().apply { bottomMargin = dp(8) })

        syncPanel.addView(caps(R.string.subtitle_size, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val sizes = listOf(80 to R.string.size_small, 100 to R.string.size_medium, 130 to R.string.size_large,
            160 to R.string.size_extra_large).map { (size, words) -> size to getString(words) }
        val sizeRow = pillRow(sizes, Settings.subtitleSize(this)) { sizeValue ->
            Settings.setSubtitleSize(this@PlayerActivity, sizeValue)
            view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizeValue / 100f)
        }
        syncPanel.addView(sizeRow, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(10) })

        syncPanel.addView(caps(R.string.color, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        syncPanel.addView(createColorRow("sync"), LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(10) })

        syncPanel.addView(caps(R.string.background_box, TEXT_3), wrap().apply { bottomMargin = dp(4) })
        val bgOptions = listOf(true to getString(R.string.state_on), false to getString(R.string.state_off))
        val bgRow = pillRow(bgOptions, currentBg, gap = 6) { bgValue ->
            currentBg = bgValue
            Settings.setSubtitleBackground(this@PlayerActivity, bgValue)
            applySubtitleStyle()
        }
        syncPanel.addView(bgRow, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(14) })

        syncPanel.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(10)) }
        })
        syncPanel.addView(caps(R.string.picture_fit, TUNGSTEN), wrap().apply { bottomMargin = dp(6) })
        val fits = listOf("fit" to getString(R.string.picture_fit_fit), "zoom" to getString(R.string.picture_fit_zoom),
            "stretch" to getString(R.string.picture_fit_stretch))
        syncPanel.addView(pillRow(fits, Settings.pictureFit(this)) { fit ->
            Settings.setPictureFit(this@PlayerActivity, fit)
            applyPictureFit()
        }, LinearLayout.LayoutParams(-1, dp(30)).apply { bottomMargin = dp(14) })

        syncPanel.addView(divider().apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(10)) }
        })

        val sleepValue = text(sleep(), SMALL, color = TEXT_3)
        val sleepRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(text(R.string.sleep_timer, BODY, color = TEXT_2), wrap())
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(sleepValue, wrap())
            rowFocus(8)
            setOnClickListener {
                cycleSleep()
                sleepValue.text = sleep()
            }
        }
        syncPanel.addView(sleepRow, LinearLayout.LayoutParams(-1, dp(36)))

        val changeStreamRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val head = text(R.string.player_change_stream_arrow, BODY, bold = true).apply { setPadding(dp(10), dp(5), dp(10), dp(3)) }
            addView(head, wrap())
            val label = sources[at].label.split(DOT.trim()).map { it.trim() }.filter { it.isNotEmpty() }
            addView(text((label.drop(1) + label.take(1)).joinToString(DOT), SMALL, color = TEXT_3).apply {
                setPadding(dp(10), 0, dp(10), 0)
            }, wrap())
            setPadding(0, 0, 0, dp(4))
            rowFocus(8)
            setOnClickListener { changeStream() }
        }
        syncPanel.addView(changeStreamRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        subtitlesPanel.visibility = if (currentTab == 0) View.VISIBLE else View.GONE
        audioPanel.visibility = if (currentTab == 1) View.VISIBLE else View.GONE
        syncPanel.visibility = if (currentTab == 2) View.VISIBLE else View.GONE
        panelsContainer.addView(subtitlesPanel, FrameLayout.LayoutParams(-1, -2))
        panelsContainer.addView(audioPanel, FrameLayout.LayoutParams(-1, -2))
        panelsContainer.addView(syncPanel, FrameLayout.LayoutParams(-1, -2))

        optionsBar = SeekBar(this).apply { isFocusable = false }
        optionsElapsed = text("", SMALL, color = TEXT_2).onArt()
        optionsLeft = text("", SMALL, color = TEXT_2).onArt()
        options.addView(optionsBar, FrameLayout.LayoutParams(dp(280), dp(12), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(48)
        })
        options.addView(optionsElapsed, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD + 246); bottomMargin = dp(27)
        })
        options.addView(optionsLeft, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(PAD); bottomMargin = dp(27)
        })
        options.visibility = View.VISIBLE
        updateProgress()
        tabPills[currentTab].requestFocus()
    }

    private fun findFirstFocusable(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view.isFocusable) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstFocusable(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun closeOptions() {
        refreshOptionsSubtitles = null
        options.visibility = View.GONE
        showControls(audioButton)
    }

    /** What's playing, in a few words: "English  ·  Spanish subtitles", or "English  ·  Subtitles off". */
    private fun trackStatus(tracks: Tracks): String {
        fun name(language: String?) = Languages.code(language)?.let(Languages::name)
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
        val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        val subtitles = when {
            text == null || C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> getString(R.string.subtitles_off)
            else -> name(if (isAddonSub(text)) chosenSub?.lang else text.getTrackFormat(0).language)
                ?.let { getString(R.string.subtitles_in, it) } ?: getString(R.string.subtitles_on)
        }
        return listOfNotNull(name(audio), subtitles).joinToString(DOT)
    }

    /** "English · Dolby Digital 5.1": the language, then the release's own name for the track or, for audio, its format. */
    /** Sets subtitle delay in milliseconds live on the renderer without reloading playback. */
    private fun setSubtitleDelay(newDelay: Long) {
        val oldDelay = subDelay
        subDelay = newDelay
        textRenderer?.offsetUs = subDelay * 1000L
        // A seek to the same second drops cues already queued for the old timing. Only where the file can seek: ExoPlayer
        // takes any seek in a file it can't seek as a seek to 0:00, so Right (subtitle later) restarted the film, which
        // the 1.2 fix (canSeek) had stopped for scrubbing.
        if (subDelay > oldDelay && player.isCurrentMediaItemSeekable) {
            player.seekTo(player.currentPosition)
        }
    }

    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    private fun trackName(f: Format, audio: Boolean): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.ifBlank { null }
        val format = if (!audio) null else listOfNotNull(when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }, when (f.channelCount) {
            1 -> getString(R.string.audio_mono); 2 -> getString(R.string.audio_stereo); 6 -> "5.1"; 8 -> "7.1"
            Format.NO_VALUE -> null; else -> getString(R.string.audio_channels, f.channelCount)
        })
            .joinToString(" ").ifEmpty { null }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        return listOfNotNull(language, label ?: format).joinToString("  ·  ").ifEmpty { getString(R.string.track_number, f.id.orEmpty()).trim() }
    }

    private fun timing(delay: Long) = formatTiming(delay, getString(R.string.timing_on_time),
        getString(R.string.timing_earlier), getString(R.string.timing_later))

    /** One choice as a row of pills (subtitle size, background box, picture fit); the picked one is in tungsten. */
    private fun <T> pillRow(options: List<Pair<T, String>>, current: T, gap: Int = 5, pick: (T) -> Unit): LinearLayout {
        var chosen = current
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val pills = options.mapIndexed { i, (value, label) ->
            text(label, SMALL, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                rowFocus(8, rest = { if (chosen == value) rounded(0x3DF3D9B1.toInt(), 8) else glass(8) }) { has ->
                    setTextColor(if (has || chosen == value) TEXT else TEXT_2)
                }
                row.addView(this, LinearLayout.LayoutParams(0, dp(30), 1f).apply { if (i < options.lastIndex) marginEnd = dp(gap) })
            }
        }
        fun paint() = pills.forEachIndexed { i, pill ->
            val on = options[i].first == chosen
            pill.background = if (on) rounded(0x3DF3D9B1.toInt(), 8) else glass(8)
            pill.setTextColor(if (on) TUNGSTEN else TEXT_2)
        }
        pills.forEachIndexed { i, pill -> pill.setOnClickListener { chosen = options[i].first; pick(chosen); paint() } }
        paint()
        return row
    }

    /**
     * Fit, Zoom or Stretch. Media3 keeps the subtitles inside the video's frame, which Zoom makes larger than the screen,
     * so under Zoom they move onto the screen itself and stay readable; Fit and Stretch put them back.
     */
    private fun applyPictureFit() {
        val fit = Settings.pictureFit(this)
        view.resizeMode = resizeModeFor(fit)
        val subs = view.subtitleView ?: return
        val frame = view.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_content_frame) ?: return
        val home: ViewGroup = if (fit == "zoom") view else frame
        if (subs.parent === home) return
        (subs.parent as? ViewGroup)?.removeView(subs)
        val at = if (home === view) view.indexOfChild(frame) + 1
            else frame.indexOfChild(frame.findViewById(androidx.media3.ui.R.id.exo_buffering)).takeIf { it >= 0 } ?: frame.childCount
        home.addView(subs, at, FrameLayout.LayoutParams(-1, -1))
    }

    private fun sleep() = sleepLabel(stopAfter, sleepAt, System.currentTimeMillis(), getString(R.string.sleep_after_episode),
        getString(R.string.sleep_in_minutes), getString(R.string.state_off))

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Handheld.isHandheld(this)) Handheld.immersive(this)
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        endFastHold()
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(loadingTask)
        // A paused source in the background shows no picture: timing it out would skip a good source.
        main.removeCallbacks(startTimeout)
        // Keep a film playing after the viewer leaves (Home, Recents, lock screen): PlaybackService wraps the same
        // player with a media notification and lock-screen controls, so the sound goes on instead of stopping. Not for
        // a trailer or a live stream, and not when the viewer only opened another Alder screen (ownNav), which pauses.
        val keepPlaying = !ownNav && !isFinishing && !isTrailer && started && player.playWhenReady &&
            !player.isCurrentMediaItemLive && !isLiveType(type) && player.playbackState != Player.STATE_IDLE
        if (keepPlaying) PlaybackService.start(this, player, titleForNotification())
        else player.pause()
        if (isTrailer || !watched || player.isCurrentMediaItemLive || isLiveType(type) || episodic && !plausibleEpisode(player.duration)) return
        record()
        flushWatch(false) // report the last watched slice (carries the completion flag when the play reached its end)
        // The player's own episode list first: Home's Resume passes no next episode when the title's details weren't in
        // yet, and a finished episode with no next one drops the title out of Continue Watching.
        item?.let { Library.stopped(it, nextVideo(onlyAired = false)?.id ?: intent.getStringExtra("nextVideoId")) }
        push()
    }

    private fun closeInFlightOpened(markDestroyed: Boolean = false) {
        val leftover = synchronized(openLock) {
            if (markDestroyed) destroyed = true
            inFlightOpened.toList().also { inFlightOpened.clear() }
        }
        leftover.forEach { it.close() }
    }

    override fun onDestroy() {
        resolvingStreams?.cancel()
        resolvingStreams = null
        closeInFlightOpened(markDestroyed = true)
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(openTimeout)
        opened?.close()
        opened = null
        borrowedOpened?.close()
        borrowedOpened = null
        main.removeCallbacks(loadingTask)
        runCatching { unregisterReceiver(pipReceiver) }
        // Detach the background service from this player before releasing it, so nothing touches a released player.
        PlaybackService.stop(this)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val SOURCES_REQUEST = 42
        const val ACTION_PIP_TOGGLE = "dev.alder.PIP_TOGGLE"
        const val TICK = 10_000L
        const val STABLE_MS = 30_000L // stable this long after a downscale -> try a higher quality again (auto quality)
        const val WATCH_SLICE_MS = 30_000L // watched time is reported to stats every ~30s of playback
        const val RESUME_SLACK = 10_000L // how far apart the saved and fresh resume points may be before it jumps
        const val SKIP = 10_000L // ms a Left/Right press on the bar moves
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val SUB_WAIT = 4_000L // ms the start waits for the addon's subtitles
        const val SUB_RETRIES = 3 // extra tries to fetch addon subtitles after an empty/failed answer
        const val SUB_RETRY_WAIT = 2_000L // ms between addon-subtitle retries
        const val PROBE_RETRIES = 2 // tries to read each source's embedded audio before giving up on it
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
        const val MID_RETRIES = 3 // mid-stream network retries per budget window before the source is skipped
        const val RESUME_REFRESH_MS = 30_000L // forward progress that gives the mid-stream retry budget back
        const val START_PROGRESS_MS = 800L // buffered this soon after start = data is flowing, so allow more time
        const val START_MAX_RETRIES = 2 // extra start waits granted while a slow source keeps buffering
        const val OPEN_WAIT = 20_000L // ms a torrent/debrid source may take to open before the next is tried
        const val SUB_PREP_WAIT = 6_000L // ms an add-on subtitle may take to fetch/unpack before playing without it
    }
}

/** Tungsten playback progress over a dark track, with a warm-white knob on focus. */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var markers = emptyList<Marker>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    var isIndeterminate = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startPulse() else stopPulse()
            invalidate()
        }
    private var pulseFraction = 0.5f
    private var pulseAnimator: android.animation.ValueAnimator? = null

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    /** Touch: dragging along the bar reports where (0..1) and whether the finger lifted. Null on a TV. */
    var onDrag: ((Float, Boolean) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val report = onDrag
        if (report == null || isIndeterminate || duration <= 0) return super.onTouchEvent(event)
        val knob = 9 * d
        val fraction = ((event.x - knob) / (width - 2 * knob).coerceAtLeast(1f)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); report(fraction, false) }
            MotionEvent.ACTION_MOVE -> report(fraction, false)
            MotionEvent.ACTION_UP -> report(fraction, true)
            MotionEvent.ACTION_CANCEL -> report(fraction, true)
        }
        return true
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0.15f, 0.85f).apply {
            duration = 1100
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseFraction = 0.5f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    fun setMarkers(markers: List<Marker>) {
        this.markers = markers
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val knob = (if (isFocused) 9 else 6) * d
        val line = (if (isFocused) 4 else 3) * d
        val y = height / 2f
        fun x(t: Long) = knob + (width - 2 * knob) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(knob, y - line / 2, to, y + line / 2, line / 2, line / 2, paint)
        }
        if (isIndeterminate) {
            stretch(width - knob, 0x33F3EEE6)
            val trackW = width - 2 * knob
            val centerX = knob + trackW * pulseFraction
            val halfSpan = trackW * 0.22f
            paint.color = Screen.TUNGSTEN
            paint.setShadowLayer(6 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
            canvas.drawRoundRect((centerX - halfSpan).coerceAtLeast(knob), y - line / 2,
                (centerX + halfSpan).coerceAtMost(width - knob), y + line / 2, line / 2, line / 2, paint)
            paint.clearShadowLayer()
            return
        }
        // A light track: the dark one vanished over a dark picture.
        stretch(width - knob, 0x40F3EEE6)

        // Tint spans for Intro / Recap if present
        if (duration > 0) {
            paint.color = 0x33F3D9B1.toInt()
            for (m in markers) {
                if (m.endMs != null && m.endMs > m.timeMs) {
                    val x1 = x(m.timeMs)
                    val x2 = x(m.endMs)
                    if (x2 > x1) canvas.drawRect(x1, y - line / 2, x2, y + line / 2, paint)
                }
            }
        }

        stretch(x(buffered), 0x73F3EEE6)
        paint.setShadowLayer(4 * d, 0f, 0f, Screen.TUNGSTEN_GLOW)
        stretch(x(position), Screen.TUNGSTEN)
        paint.clearShadowLayer()

        // Marker ticks along the bar
        if (duration > 0 && markers.size > 1) {
            val gapHalf = 1.5f * d
            val tickHalf = 0.8f * d
            val tickExtra = if (isFocused) 2.5f * d else 1f * d
            for (m in markers) {
                if (m.timeMs > 0 && m.timeMs < duration) {
                    val mx = x(m.timeMs)
                    paint.color = 0xFF0E0C0A.toInt()
                    canvas.drawRect(mx - gapHalf, y - line / 2 - 1f, mx + gapHalf, y + line / 2 + 1f, paint)
                    paint.color = if (m.type != MarkerType.CHAPTER) Screen.TUNGSTEN else 0xCCF3EEE6.toInt()
                    canvas.drawRect(mx - tickHalf, y - line / 2 - tickExtra, mx + tickHalf, y + line / 2 + tickExtra, paint)
                }
            }
        }

        paint.color = Screen.TEXT
        if (duration > 0) canvas.drawCircle(x(position), y, knob, paint)
    }
}

/** Media3's resize mode for Settings' picture fit: Zoom fills the screen and crops, Stretch fills it and changes the shape. */
internal fun resizeModeFor(fit: String) = when (fit) {
    "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "stretch" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/**
 * The buffer's size limit for the memory Android gives Alder ([maxHeap], bytes), or null for Media3's own (about 131 MB:
 * 10 to 17 s of a 4K remux). With the large heap (512 MB on most TV boxes) the buffer can use up to 200 MB, and
 * 250 MB stays free for decoders, pictures and screens. As NuvioTV does it.
 */
internal fun bufferBytesFor(maxHeap: Long): Int? {
    val mb = 1024L * 1024
    return (maxHeap - 250 * mb).coerceAtMost(200 * mb).takeIf { it > DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE }?.toInt()
}
