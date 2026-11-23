package dev.alder

import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class Failure(val message: Int, val advice: Int) {
    SIGN_IN(R.string.error_sign_in, R.string.failure_advice_sign_in),
    ACCESS(R.string.failure_refused, R.string.failure_advice_access),
    TIMEOUT(R.string.failure_timeout, R.string.failure_advice_timeout),
    ADDRESS(R.string.failure_no_address, R.string.failure_advice_address),
    CONNECTION(R.string.failure_connection, R.string.failure_advice_connection),
    SECURE(R.string.failure_secure, R.string.failure_advice_secure),
    BUSY(R.string.failure_too_many, R.string.failure_advice_busy),
    SERVER(R.string.failure_server, R.string.failure_advice_server),
    RESPONSE(R.string.error_response, R.string.failure_advice_response),
    MISSING(R.string.error_missing, R.string.failure_advice_missing),
    FORMAT(R.string.error_format, R.string.failure_advice_format),
    NETWORK(R.string.error_network, R.string.failure_advice_network),
    WEB_PAGE(R.string.failure_web_page, R.string.failure_advice_web_page),
    OTHER(R.string.failure_error, R.string.failure_advice_other),
}

internal fun failureOf(error: Throwable): Failure = when (error) {
    is WebPageAnswer -> Failure.WEB_PAGE
    is Stremio.ApiException -> if (error.code == Stremio.NO_SESSION) Failure.SIGN_IN else Failure.SERVER
    is HttpError -> when (error.code) {
        401, 403 -> Failure.ACCESS
        404, 410 -> Failure.MISSING
        408, 504 -> Failure.TIMEOUT
        429 -> Failure.BUSY
        in 500..599 -> Failure.SERVER
        else -> Failure.RESPONSE
    }
    is SocketTimeoutException -> Failure.TIMEOUT
    is UnknownHostException -> Failure.ADDRESS
    is ConnectException -> Failure.CONNECTION
    is SSLException -> Failure.SECURE
    is JSONException, is IllegalArgumentException, is java.net.URISyntaxException -> Failure.RESPONSE
    is IOException -> Failure.NETWORK
    else -> Failure.OTHER
}

/**
 * Every error's words, in one place: what failed and why ("Couldn't load this title · timed out"), whose answer it was
 * when known ("… · Torrentio: timed out"), then what to do. [advice] false for one-line places (a toast, a status line).
 */
internal fun Context.failureMessage(title: Int, failure: Failure, source: String? = null, advice: Boolean = true) =
    failureMessage(getString(title), failure, source, advice)

internal fun Context.failureMessage(title: String, failure: Failure, source: String? = null, advice: Boolean = true): String {
    val reason = getString(failure.message)
    val first = "$title · ${if (source.isNullOrBlank()) reason else "$source: $reason"}"
    val message = if (advice) "$first\n${getString(failure.advice)}" else first
    // Swap any hardcoded product words ("Stremio", "Alder") for the operator's name, so failures read "…reach <name>".
    return Branding.brand(message, this)
}

internal fun playbackFailure(code: Int, cause: Throwable?): Failure {
    val response = cause as? HttpDataSource.InvalidResponseCodeException
    if (response != null) return failureOf(HttpError(response.responseCode))
    return when (code) {
        PlaybackException.ERROR_CODE_TIMEOUT, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> Failure.TIMEOUT
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> Failure.NETWORK
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Failure.MISSING
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Failure.ACCESS
        in 3000..3999, in 4000..4999 -> Failure.FORMAT
        else -> cause?.let(::failureOf) ?: Failure.OTHER
    }
}

internal fun searchFailure(failures: List<Failure>, completed: Int, total: Int): Failure? =
    failures.firstOrNull() ?: Failure.TIMEOUT.takeIf { completed < total }

internal fun syncWatchProgress(authKey: String): Failure? {
    if (ProgressSaves.pending(authKey).isEmpty()) return null
    try {
        Stremio.syncProgress(authKey)
        ProgressSaves.syncFailure(authKey, null)?.let { FailureLog.record(FailureLog.Operation.PROGRESS, it, recovered = true) }
    } catch (error: Exception) {
        val failure = failureOf(error)
        val previous = ProgressSaves.syncFailure(authKey, failure)
        FailureLog.record(FailureLog.Operation.PROGRESS, failure)
        return failure.takeIf { previous == null }
    }
    return null
}

internal object FailureLog {
    enum class Operation { LIBRARY_EDIT, PROGRESS, PLAYBACK, CATALOG, SEARCH, DETAILS, SIGN_IN, ADD_ON }

    private val recent = ArrayDeque<String>()

    @Synchronized
    fun record(operation: Operation, failure: Failure, recovered: Boolean = false) {
        val entry = "${operation.name}: ${failure.name} (${if (recovered) "recovered" else "failed"})"
        if (recent.lastOrNull() != entry) {
            if (recent.size == 20) recent.removeFirst()
            recent.addLast(entry)
        }
        Log.i("Alder", entry)
    }

    @Synchronized
    fun report(): String = if (recent.isEmpty()) "" else recent.joinToString("\n", "\n\nRecent failures and recovery:\n")
}
