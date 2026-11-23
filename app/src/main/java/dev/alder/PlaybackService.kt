package dev.alder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Keeps a film playing after the viewer leaves Alder — Home, Recents, or the lock screen — with a media
 * notification whose controls drive the same player and show up on the lock screen.
 *
 * The player itself still lives in [PlayerActivity] (so all of its playback, subtitle and audio logic is
 * untouched); this service only wraps it in a [MediaSession] and posts a [Notification.MediaStyle]
 * notification. It is a foreground service of type mediaPlayback, so Android keeps the process alive for
 * the audio and never kills it for sitting in the background.
 */
internal class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "alder_playback"
        private const val NOTIF_ID = 0xA1DE
        private const val ACTION_TOGGLE = "dev.alder.PLAYBACK_TOGGLE"
        private const val ACTION_STOP = "dev.alder.PLAYBACK_STOP"

        /** The player to control, handed over right before the service starts (same process, so no binding needed). */
        @Volatile private var pending: ExoPlayer? = null

        /** Hands [player] to the service and promotes it to the foreground. Called from PlayerActivity when it leaves. */
        fun start(context: Context, player: ExoPlayer, title: String) {
            pending = player
            val i = Intent(context, PlaybackService::class.java).putExtra("title", title)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            pending = null
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var title: String = ""
    private var stopped = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { runCatching { refresh() } }
        override fun onPlaybackStateChanged(playbackState: Int) {
            runCatching { if (playbackState == Player.STATE_IDLE) stopSelfCompat() else refresh() }
        }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            runCatching { refresh() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Post a valid notification straight away; the media session (and its token on the style) follows below.
        startForeground(NOTIF_ID, buildNotification(null))
        session = MediaSession(this, "alder").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { player?.play() }
                override fun onPause() { player?.pause() }
                override fun onStop() { player?.pause(); stopSelfCompat() }
                override fun onSeekTo(pos: Long) { player?.seekTo(pos) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> { val p = player; if (p != null) if (p.playWhenReady) p.pause() else p.play() }
            ACTION_STOP -> stopSelfCompat()
            else -> {
                val p = pending ?: player
                if (p != null && p !== player) {
                    player?.removeListener(listener)
                    player = p
                    p.addListener(listener)
                }
                intent?.getStringExtra("title")?.let { title = it }
                refresh()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** Pushes the player's current state into the session and the notification (lock screen, Bluetooth, etc.). */
    private fun refresh() {
        if (stopped) return
        val p = player ?: return
        val s = session ?: return
        val playing = p.playWhenReady && p.isPlaying
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.coerceAtLeast(0L)
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos,
                if (playing) p.playbackParameters.speed else 0f)
            .build())
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { Branding.name(this) })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Branding.name(this))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
            .build())
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(p))
    }

    private fun action(id: String, icon: Int, label: CharSequence): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, id.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun buildNotification(p: ExoPlayer?): Notification {
        val playing = p?.let { it.playWhenReady && it.isPlaying } == true
        val toggle = action(ACTION_TOGGLE, if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.player_pause else R.string.action_play))
        val stop = action(ACTION_STOP, R.drawable.ic_stop, getString(R.string.action_stop))
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title.ifEmpty { Branding.name(this) })
            .setContentText(Branding.name(this))
            .setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setStyle(style)
            .addAction(toggle)
            .addAction(stop)
        return b.build()
    }

    private fun stopSelfCompat() {
        if (stopped) return
        stopped = true
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        session?.let { s ->
            player?.removeListener(listener)
            s.isActive = false
            s.release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}