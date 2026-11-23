package dev.alder

import android.content.Context
import android.os.Build
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEngineConfig
import com.nuvio.engine.NuvioEngineException
import com.nuvio.engine.NuvioTorrentFile
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal val supportsTorrentPlayback get() = Build.VERSION.SDK_INT >= 24

private const val METADATA_NOT_READY = 9 // NUVIO_ENGINE_STATUS_METADATA_NOT_READY
private const val CANCEL_POLL = 200L
private fun logInfo(message: String) {
    runCatching { android.util.Log.i("Alder", message) }
}
private val episodeMarker = Regex("""(?i)(?<![a-z0-9])(?:s(\d+)[ ._-]*e(\d+)|(\d{1,2})x(\d+))(?!\d)""")
private val episodeRange = Regex("""(?i)^(?:[ ._]*e\d+|[ ._]*-[ ._]*e?\d+(?![a-z0-9]))""")
private val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv", "vob", "3gp")

/** Closes [opened] when the caller is already [stale], returning whether it was stale. */
internal fun closeStaleOpen(opened: Opened?, stale: Boolean): Boolean {
    if (!stale) return false
    opened?.close()
    return true
}

/** Variant of [Stream.open] that stops waiting on a torrent as soon as [canceled] turns true. */
internal fun Stream.open(context: Context, episode: Pair<Int, Int>? = null, canceled: () -> Boolean): Opened =
    torrent?.open(context, filename, episode, canceled) ?: Opened(url, headers)

internal suspend fun <T> withCancellation(
    canceled: () -> Boolean,
    pollMs: Long = CANCEL_POLL,
    block: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    if (canceled()) throw CancellationException("torrent open canceled")
    val work = async { block() }
    val watcher = launch {
        while (work.isActive) {
            if (canceled()) {
                work.cancel(CancellationException("torrent open canceled"))
                break
            }
            delay(pollMs)
        }
    }
    try {
        work.await()
    } finally {
        watcher.cancel()
    }
}

internal suspend fun awaitTorrentFiles(
    canceled: () -> Boolean = { false },
    pollMs: Long = 250L,
    files: suspend () -> List<NuvioTorrentFile>,
): List<NuvioTorrentFile> {
    while (true) {
        if (canceled()) throw CancellationException("torrent open canceled")
        try {
            return files()
        } catch (e: NuvioEngineException) {
            if (e.status != METADATA_NOT_READY) throw e
        }
        delay(pollMs)
    }
}

internal fun torrentEpisodeFileIndex(files: List<NuvioTorrentFile>, episode: Pair<Int, Int>): Int? {
    val videos = files.filter {
        it.size > 0 && (it.pathTruncated || it.path.substringAfterLast('.').lowercase() in videoExtensions)
    }
    return videos.filter { file ->
        if (file.pathTruncated) return@filter false
        val name = file.path.substringAfterLast('/')
        val markers = episodeMarker.findAll(name).toList()
        val marker = markers.singleOrNull() ?: return@filter markers.isEmpty() && videos.size == 1
        if (episodeRange.containsMatchIn(name.substring(marker.range.last + 1))) return@filter false
        val season = marker.groupValues[1].ifEmpty { marker.groupValues[3] }.toIntOrNull()
        val number = marker.groupValues[2].ifEmpty { marker.groupValues[4] }.toIntOrNull()
        season == episode.first && number == episode.second
    }.singleOrNull()?.index
}

class TorrentSource private constructor(val infoHash: String, val fileIndex: Int?, private val trackers: List<String>) {
    // The add-on's trackers, then a few public ones (as NuvioTV does): add-ons often name one tracker or none.
    val magnet: String get() = buildString {
        append("magnet:?xt=urn:btih:").append(infoHash)
        (trackers + PUBLIC_TRACKERS).distinct().forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    }

    fun toJson() = JSONObject().put("infoHash", infoHash).put("fileIdx", fileIndex)
        .put("sources", JSONArray(trackers.map { "tracker:$it" }))

    /**
     * Starts the torrent engine and gives the player its local http link, like any other source. [Opened.close] stops
     * the engine and deletes what it downloaded. Blocks until the file is ready, so call it off the main thread.
     */
    fun open(
        context: Context,
        filename: String? = null,
        episode: Pair<Int, Int>? = null,
        canceled: () -> Boolean = { false },
    ): Opened {
        if (canceled()) throw CancellationException("torrent open canceled")
        try {
            return engineThread.submit<Opened> {
                if (canceled()) throw CancellationException("torrent open canceled")
                val cache = File(context.cacheDir, "torrent")
                val engine = NuvioEngine.create(NuvioEngineConfig(
                    dataDirectory = File(cache, "data"),
                    cacheDirectory = File(cache, "payload"),
                    memoryCacheCapacityBytes = 16L * 1024 * 1024,
                    diskCacheCapacityBytes = 256L * 1024 * 1024,
                    torrentProfile = NuvioTorrentProfile.Soft,
                    uploadMode = NuvioUploadMode.Disabled,
                    // The engine's default ends a stream after 30 s without a read: a pause, or the player's full buffer
                    // near the end, then left the player waiting for bytes that never came. NuvioTV turns it off too.
                    streamInactivityTimeoutMilliseconds = 0,
                ))
                logInfo("P2P engine created: $infoHash")
                val stopped = AtomicBoolean(false)
                fun stop() {
                    if (!stopped.compareAndSet(false, true)) return
                    try {
                        engine.close()
                    } finally {
                        File(cache, "payload").deleteRecursively()
                        logInfo("P2P engine closed: $infoHash")
                    }
                }
                try {
                    val stream = runBlocking {
                        withTimeout(CONNECT_WAIT) {
                            withCancellation(canceled) {
                                if (canceled()) throw CancellationException("torrent open canceled")
                                val id = engine.addMagnet(magnet)
                                if (canceled()) throw CancellationException("torrent open canceled")
                                val index = fileIndex ?: episode?.let {
                                    checkNotNull(torrentEpisodeFileIndex(torrentFiles(engine, id, canceled), it)) {
                                        "torrent has no unique file for the requested episode $it"
                                    }
                                }
                                if (canceled()) throw CancellationException("torrent open canceled")
                                val prepared = engine.prepareStream(id, index, filename.takeIf { index == null })
                                if (canceled()) throw CancellationException("torrent open canceled")
                                check(index == null || prepared.fileIndex == index) { "the torrent has no file $index" }
                                prepared
                            }
                        }
                    }
                    logInfo("P2P engine ready: $infoHash -> ${stream.url}")
                    val opened = Opened(stream.url, emptyMap(), startWait = START_WAIT, readTimeoutMs = READ_WAIT) {
                        engineThread.execute(::stop)
                    }
                    if (canceled()) {
                        stop()
                        throw CancellationException("torrent open canceled")
                    }
                    opened
                } catch (e: Throwable) {
                    stop()
                    throw e
                }
            }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private suspend fun torrentFiles(
        engine: NuvioEngine,
        id: String,
        canceled: () -> Boolean = { false },
    ): List<NuvioTorrentFile> = awaitTorrentFiles(canceled) { engine.files(id) }

    companion object {
        private val hashFormat = Regex("[0-9a-fA-F]{40}")
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        // One thread for every engine start and stop, in order: two engines must never share the cache at once.
        private val engineThread = Executors.newSingleThreadExecutor()
        private const val CONNECT_WAIT = 45_000L // ms to find peers and the file before the source fails
        private const val START_WAIT = 45_000L // ms from the link to the first picture: the first pieces come from peers
        private const val READ_WAIT = 35_000 // ms a read may wait for a piece
        private val PUBLIC_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
        )

        fun isTorrentUrl(url: String) = url.startsWith("magnet:", ignoreCase = true) ||
            url.startsWith("torrent:", ignoreCase = true)

        fun from(json: JSONObject): TorrentSource? {
            val uri = json.text("url")?.takeIf(::isTorrentUrl)?.let {
                runCatching { URI(it) }.getOrNull()
            }
            val magnetParams = uri?.takeIf { it.scheme.equals("magnet", ignoreCase = true) }
                ?.rawSchemeSpecificPart?.takeIf { it.startsWith('?') }?.drop(1)?.split('&').orEmpty()
            fun magnetValues(key: String) = magnetParams.asSequence().filter { it.startsWith("$key=") }
                .mapNotNull { runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrNull() }
            val btih = "urn:btih:"
            val magnetHash = magnetValues("xt").filter { it.startsWith(btih, ignoreCase = true) }
                .firstNotNullOfOrNull { magnetInfoHash(it.drop(btih.length)) }
            val torrentHash = uri?.takeIf { it.scheme.equals("torrent", ignoreCase = true) }
                ?.rawAuthority?.takeIf(hashFormat::matches)
            val hash = json.text("infoHash")?.takeIf(hashFormat::matches) ?: magnetHash ?: torrentHash ?: return null
            val index = json.text("fileIdx")?.let { it.toIntOrNull()?.takeIf { i -> i >= 0 } ?: return null }
            val addonTrackers = json.optJSONArray("sources").strings().asSequence()
                .filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
            val trackers = (magnetValues("tr") + addonTrackers)
                .filter { tracker ->
                    tracker.length <= 512 && runCatching {
                        val uri = URI(tracker)
                        uri.scheme in setOf("http", "https", "udp") && uri.host != null
                    }.getOrDefault(false)
                }.distinct().take(16).toList()
            return TorrentSource(hash.lowercase(), index, trackers)
        }

        private fun magnetInfoHash(hash: String): String? {
            if (hashFormat.matches(hash)) return hash
            val base32 = hash.uppercase()
            if (base32.length != 32 || base32.any { it !in BASE32 }) return null
            // Eight base32 digits encode five bytes, which are ten hex digits.
            return base32.chunked(8).joinToString("") { digits ->
                digits.fold(0L) { value, digit -> (value shl 5) or BASE32.indexOf(digit).toLong() }
                    .toString(16).padStart(10, '0')
            }
        }
    }
}
