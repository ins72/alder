package dev.alder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import java.util.concurrent.Executor

interface Cancellable {
    fun cancel()
}

object StreamsResolver {
    const val TRY = 3

    fun rank(
        context: Context,
        streams: List<Stream>,
        videoId: String,
        metaId: String?,
        maxHeight: Int = 0
    ): List<Stream> {
        val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
        val file = prefs.getString("file:$videoId", null)
        val binge = metaId?.let { prefs.getString("binge:$it", null) }
        val tv = Device.of(context)
        val preferResolution = Settings.release(context) == "remux" && Settings.hdrPref(context) == "dv"
        return rank(streams, tv, Settings.displayHeight(context), file, binge, maxHeight > 0, preference(context), preferResolution)
    }

    internal fun rank(
        streams: List<Stream>,
        tv: Device,
        displayOverride: Int,
        file: String?,
        binge: String?,
        lower: Boolean,
        prefer: (Stream) -> Int,
        preferResolution: Boolean
    ): List<Stream> {
        val displayHeight = if (displayOverride > 0) displayOverride else tv.height
        // P2P after every direct or debrid source: peers start slower and can't be checked first.
        // A P2P source its add-on says nobody shares (👤 0) can't start: after every other.
        return streams.sortedWith(compareBy<Stream> { it.torrent != null }
            .thenBy { it.torrent != null && it.seeders == 0 }
            .thenByDescending { if (lower) it.height else 0 }
            .thenByDescending { file != null && it.filename == file }
            .thenByDescending { binge != null && it.bingeGroup == binge }
            .thenBy { it.awkward }
            .thenBy { it.dolbyVisionOnly && !tv.dolbyVision }
            .thenByDescending { it.fit(tv, displayHeight) }
            .thenByDescending(prefer)
            .thenByDescending { if (preferResolution && it.height <= displayHeight) it.height else 0 }
            .thenByDescending { if (it.torrent != null) it.seeders ?: 0 else 0 })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun preference(context: Context): (Stream) -> Int {
        val release = Settings.release(context)
        val hdr = Settings.hdrPref(context)
        val dub = Settings.dub(context)
        val language = Settings.audioLanguage(context)
        val audio = if (Settings.decodeAudio(context)) null else AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        val trueHd = audio?.supportsEncoding(C.ENCODING_DOLBY_TRUEHD) == true
        val dtsHd = audio?.supportsEncoding(C.ENCODING_DTS_HD) == true
        return { s ->
            when (release) {
                "remux" -> if (s.remux) 3 else if (s.bluray) 1 else 0
                "encode" -> if (s.remux) -2 else if (s.bluray || s.web) 2 else 0
                "web" -> if (s.web && !s.remux) 2 else if (s.remux) -2 else 0
                else -> 0
            } + when (hdr) {
                "dv" -> if (s.dolbyVision) 2 else if (s.hdr) 1 else 0
                "hdr10" -> if (s.hdr) 2 else 0
                else -> 0
            } + when (dub) {
                "original" -> if (s.dubbed) -2 else 0
                "mine" -> if (s.mentions(language)) 2 else if (s.multiAudio && s.audioLanguages.isEmpty()) 1 else 0
                else -> 0
            } + if ((trueHd && s.trueHdAudio) || (dtsHd && s.dtsHdAudio)) 1 else 0
        }
    }

    fun wanted(context: Context, s: Stream, maxHeight: Int = 0): Boolean {
        val limitHeight = maxHeight.takeIf { it > 0 } ?: Settings.maxHeight(context)
        val maxBytes = Settings.maxSizeGb(context) * 1_073_741_824L
        return (limitHeight == 0 || s.height == 0 || s.height <= limitHeight) &&
            (maxBytes == 0L || s.size == 0L || s.size <= maxBytes) &&
            (Settings.hdr(context) || !s.isHdr) && !s.cam
    }

    fun resolve(
        context: Context,
        type: String,
        videoId: String,
        metaId: String,
        authKey: String?,
        maxHeight: Int = 0,
        executor: Executor = Screen.pool,
        mainHandler: Handler = Handler(Looper.getMainLooper()),
        onReady: (List<Stream>) -> Unit,
        onFallback: () -> Unit
    ): Cancellable {
        val key = authKey ?: run {
            mainHandler.post(onFallback)
            return object : Cancellable { override fun cancel() {} }
        }

        var canceled = false
        var started = false
        var waitingForEarlier = false
        val tv = Device.of(context)

        val cancellable = object : Cancellable {
            override fun cancel() {
                canceled = true
            }
        }

        val lenders = Profiles.streamLenders(context)

        executor.execute {
            if (canceled) return@execute
            val res = runCatching {
                val own = (listOf(MovieboxAddon(), HouseAddon()) + Stremio.addons(key)).filter { it.providesStreams }
                val lent = lenders.flatMap { lender ->
                    runCatching {
                        (Stremio.savedAddons(lender.authKey) ?: Stremio.addons(lender.authKey)).filter { it.shareable }
                    }.getOrDefault(emptyList())
                }
                val household = lent.filter { it.serves("stream", type, videoId) }.distinctBy { it.id }
                household + own.filter { it.serves("stream", type, videoId) }.filterNot { mine -> household.any { it.id == mine.id } }
            }

            val addons = res.getOrNull().orEmpty()
            if (addons.isEmpty()) {
                mainHandler.post { if (!canceled) onFallback() }
                return@execute
            }

            val answers = arrayOfNulls<List<Stream>>(addons.size)

            fun tryStart(allowGaps: Boolean = false) {
                if (canceled || started) return
                val ready = readyStreams(answers, allowGaps)
                val best = rank(context, ready.filter { it.isVideo && it.playableOn(tv) && wanted(context, it, maxHeight) }, videoId, metaId, maxHeight)
                if (best.isEmpty() && answers.any { it == null }) return
                if (best.isEmpty()) {
                    started = true
                    onFallback()
                    return
                }
                started = true
                // Keep EVERY built-in (Alder / operator-brand-named) stream plus the top TRY from other add-ons, in
                // ranked order. The player builds its audio-language modal and per-language quality ladder from this
                // list, so trimming to TRY hid every dub and resolution past the third row (Portuguese and a film's
                // 1080p release were invisible when the built-in source's 480p/360p filled the first rows). The
                // built-in source is one film's full dub/quality set, so all of it is offered; external add-ons stay
                // capped. Playback still starts at the best row and only advances on a real failure.
                val brand = internalSourceName(context)
                var others = 0
                val keep = best.filter { it.title == brand || others++ < TRY }
                android.util.Log.i("AlderStreams", "resolved ${best.size} -> keep ${keep.size} (brand=$brand); " +
                    keep.joinToString(" | ") { "${it.title.replace('\n', ' ')} [${it.height}p ${it.audioLanguages}]" })
                onReady(keep)
            }

            addons.forEachIndexed { i, addon ->
                executor.execute {
                    if (canceled) return@execute
                    val streamsResult = runCatching { addon.streams(type, videoId) }
                    mainHandler.post {
                        if (canceled) return@post
                        answers[i] = streamsResult.getOrNull()?.playable.orEmpty()
                        if (answers.all { it != null }) {
                            tryStart(allowGaps = true)
                        } else {
                            tryStart(allowGaps = false)
                            if (!started && !waitingForEarlier && readyStreams(answers, true).any { it.isVideo && it.playableOn(tv) && wanted(context, it, maxHeight) }) {
                                waitingForEarlier = true
                                // Wait for the built-in Moviebox/House source before starting on a partial list: it is
                                // the one carrying the film's dubs and 1080p ladder, and it is slower than the others.
                                // Starting on the fast add-on's untagged streams first froze the player's audio menu on
                                // "English + Italian" and hid every dub. A hard cap still starts if a built-in hangs.
                                val brandPending = addons.withIndex().any { (i, a) ->
                                    (a is MovieboxAddon || a is HouseAddon) && answers[i] == null
                                }
                                mainHandler.postDelayed({ tryStart(allowGaps = true) }, if (brandPending) 3_000L else 800L)
                            }
                        }
                    }
                }
            }
        }

        return cancellable
    }
}
