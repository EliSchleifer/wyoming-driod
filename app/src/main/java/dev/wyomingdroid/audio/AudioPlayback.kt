package dev.wyomingdroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Plays back the TTS audio that Home Assistant streams to the satellite as
 * audio-start / audio-chunk(s) / audio-stop. A new [AudioTrack] is created per
 * response because the sample rate is announced in each audio-start.
 */
class AudioPlayback {

    private var track: AudioTrack? = null

    @Synchronized
    fun start(rate: Int, channels: Int) {
        stopInternal()

        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            Log.w(TAG, "Unsupported playback config rate=$rate channels=$channels")
            return
        }

        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(channelMask)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        track = AudioTrack(
            attrs,
            format,
            maxOf(minBuf, rate), // ~0.5s buffer
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { it.play() }
    }

    @Synchronized
    fun write(payload: ByteArray) {
        track?.write(payload, 0, payload.size)
    }

    @Synchronized
    fun stop() = stopInternal()

    private fun stopInternal() {
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
