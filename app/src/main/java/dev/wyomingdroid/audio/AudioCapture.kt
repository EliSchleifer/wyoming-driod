package dev.wyomingdroid.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log

/**
 * Captures 16 kHz / mono / 16-bit PCM from the microphone and delivers it in
 * fixed-size chunks on a dedicated thread. One instance is shared by the whole
 * service; it runs whenever at least one Home Assistant connection is streaming.
 */
class AudioCapture(
    private val sampleRate: Int,
    private val audioSource: Int,
    /** Samples per delivered chunk. 1024 samples == 64 ms at 16 kHz. */
    private val samplesPerChunk: Int = 1024,
) {
    private val bytesPerChunk = samplesPerChunk * 2 // 16-bit

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    val isRunning: Boolean get() = running

    /**
     * Starts recording. [onChunk] is invoked on the capture thread with a fresh
     * byte array for every chunk. Throws if AudioRecord cannot be initialised
     * (e.g. RECORD_AUDIO not granted).
     */
    fun start(onChunk: (ByteArray) -> Unit) {
        if (running) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            throw IllegalStateException("Unsupported audio config (rate=$sampleRate)")
        }
        // A few chunks of headroom so we never drop frames between reads.
        val bufferSize = maxOf(minBuf, bytesPerChunk * 4)

        @Suppress("MissingPermission") // RECORD_AUDIO is checked before start()
        val rec = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord failed to initialise")
        }

        record = rec
        running = true
        rec.startRecording()

        thread = Thread({
            val buf = ByteArray(bytesPerChunk)
            while (running) {
                var filled = 0
                // Fill a whole chunk before emitting; read() may return short.
                while (filled < bytesPerChunk && running) {
                    val r = rec.read(buf, filled, bytesPerChunk - filled)
                    if (r <= 0) {
                        if (r < 0) {
                            Log.w(TAG, "AudioRecord.read error $r")
                            running = false
                        }
                        break
                    }
                    filled += r
                }
                if (filled > 0) {
                    onChunk(if (filled == bytesPerChunk) buf.copyOf() else buf.copyOf(filled))
                }
            }
        }, "wyoming-audio-capture").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { try { it.join(1000) } catch (_: InterruptedException) {} }
        thread = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
