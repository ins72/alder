package dev.alder

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

@androidx.annotation.OptIn(UnstableApi::class)
internal fun losslessEncoding(format: Format): Int? {
    val mime = format.sampleMimeType ?: return null
    if (mime == MimeTypes.AUDIO_DTS_EXPRESS) return null
    return MimeTypes.getEncoding(mime, format.codecs).takeIf {
        it == C.ENCODING_DOLBY_TRUEHD || it == C.ENCODING_DTS_HD || it == C.ENCODING_DTS_UHD_P2
    }
}

internal fun canPassAudio(format: Format, usePcm: Boolean, outputEncoding: Int?): Boolean {
    if (format.sampleMimeType == MimeTypes.AUDIO_RAW) return true
    if (usePcm || outputEncoding == null) return false
    return losslessEncoding(format)?.let { it == outputEncoding } ?: true
}

internal fun bestAudioFormat(
    formats: List<Format>,
    language: String,
    failed: Set<Format>,
    hasLosslessOutput: (Format) -> Boolean
): Format? = formats
    .filter { it !in failed && it.label?.contains("commentary", ignoreCase = true) != true }
    .maxWithOrNull(compareBy(
        { Languages.match(it.language, language) },
        hasLosslessOutput,
        { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
        { it.channelCount }
    ))

/** Whether the audio route can take [Format]s as they are (passthrough), not decoded to PCM. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun passthroughOutput(context: Context, attributes: AudioAttributes): (Format) -> Boolean {
    val capabilities = AudioCapabilities.getCapabilities(context, attributes, null)
    return { format ->
        format.sampleMimeType != MimeTypes.AUDIO_RAW && canPassAudio(
            format, false, capabilities.getEncodingAndChannelConfigForPassthrough(format, attributes)?.first
        )
    }
}

internal fun losslessOutput(context: Context, attributes: AudioAttributes): (Format) -> Boolean {
    val passes = passthroughOutput(context, attributes)
    return { format -> losslessEncoding(format) != null && passes(format) }
}

// Playback speeds offered in the player. Any but 1x needs PCM: a passthrough stream can't be time-stretched.
internal val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

internal fun speedLabel(speed: Float): String = speed.toString().trimEnd('0').trimEnd('.') + "×"

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackAudioSink(
    context: Context,
    dialogue: DialogueAudioProcessor,
    usePcm: () -> Boolean
): AudioSink {
    val provider = object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
        override fun getFormatSupport(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
            if (config.format.sampleMimeType == MimeTypes.AUDIO_RAW) return super.getFormatSupport(config)
            if (usePcm()) return AudioOutputProvider.FormatSupport.UNSUPPORTED
            val support = super.getFormatSupport(config)
            if (losslessEncoding(config.format) == null) return support
            if (support.supportLevel == AudioOutputProvider.FORMAT_UNSUPPORTED) return support
            // Probe the encoding without asking AudioTrack for a buffer. Media3 can offer DTS core for DTS-HD.
            val encoding = try {
                super.getOutputConfig(config.buildUpon().setEnableOffload(false).setPreferredBufferSize(1).build()).encoding
            } catch (_: AudioOutputProvider.ConfigurationException) {
                null
            }
            return if (canPassAudio(config.format, false, encoding)) support else AudioOutputProvider.FormatSupport.UNSUPPORTED
        }

        override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
            if (usePcm() && config.format.sampleMimeType != MimeTypes.AUDIO_RAW) {
                throw AudioOutputProvider.ConfigurationException("Passthrough is disabled for this source")
            }
            val output = try {
                super.getOutputConfig(config)
            } catch (e: IllegalStateException) {
                if (config.format.sampleMimeType == MimeTypes.AUDIO_RAW) throw e
                throw AudioOutputProvider.ConfigurationException("Cannot configure passthrough: ${e.message}")
            }
            if (!canPassAudio(config.format, false, output.encoding)) {
                throw AudioOutputProvider.ConfigurationException("The audio route cannot pass this lossless format")
            }
            return output
        }
    }
    return DefaultAudioSink.Builder(context).setAudioOutputProvider(provider)
        .setAudioProcessors(arrayOf(dialogue)).build()
}

internal fun audioOutputFailure(errorCode: Int): Boolean = when (errorCode) {
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> true
    else -> false
}

internal fun shouldRetryWithPcm(usePcm: Boolean, outputFailed: Boolean, sinkMime: String?): Boolean =
    !usePcm && outputFailed && sinkMime != MimeTypes.AUDIO_RAW
