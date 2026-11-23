package dev.alder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads a playable URL's embedded audio tracks (their languages) without playing it, so the player's Audio menu can
 * list every source's audio and not only the one on screen — a film's 1080p source often carries several audio tracks
 * while the entry currently playing is a single-language dub.
 *
 * One reusable player on its own [HandlerThread] (creating an ExoPlayer per source is wasteful); each [extract] call
 * loads only until the track list is known, then stops. Best-effort and bounded: any failure, or a [timeoutMs] wait,
 * yields an empty list so a probe never blocks playback.
 */
object AudioProbe {
    data class Track(val lang: String?, val label: String?)

    private val thread = HandlerThread("audio-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: ExoPlayer? = null

    /**
     * Embedded audio tracks of [url], or an empty list. Must be called OFF the main thread (it blocks up to [timeoutMs]
     * waiting for the track list). [headers] are passed through (a media-proxy URL already carries its own, but an
     * add-on's direct URL may need them).
     */
    fun extract(context: Context, url: String, headers: Map<String, String>, isHls: Boolean, timeoutMs: Long = 12_000): List<Track> {
        if (url.isBlank()) return emptyList()
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        val out = Collections.synchronizedList(ArrayList<Track>())
        val seen = HashSet<String>()
        lateinit var listener: Player.Listener
        handler.post {
            val p = player ?: ExoPlayer.Builder(app).build().also { it.playWhenReady = false; player = it }
            listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    for (g in tracks.groups) {
                        if (g.type != C.TRACK_TYPE_AUDIO) continue
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            val key = "${f.language}|${f.label}|${f.id}|${f.sampleMimeType}"
                            if (seen.add(key)) out.add(Track(f.language, f.label ?: f.language))
                        }
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY || state == Player.STATE_IDLE || state == Player.STATE_ENDED) latch.countDown()
                }
                override fun onPlayerError(error: PlaybackException) = latch.countDown()
                override fun onPlayerErrorChanged(error: PlaybackException?) { if (error != null) latch.countDown() }
            }
            p.addListener(listener)
            try {
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers).setReadTimeoutMs(10_000)
                val item = MediaItem.Builder().setUri(url).apply {
                    if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                p.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http), extractors).createMediaSource(item))
                p.prepare()
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        val tracks = ArrayList(out)
        handler.post {
            player?.removeListener(listener)
            runCatching { player?.stop() }
        }
        return tracks
    }
}