package dev.wyomingdroid

import kotlin.math.sqrt

/**
 * Thread-safe microphone level state shared by the satellite service, Live
 * view, and Home Assistant monitor clients.
 */
object MicLevelMonitor {

    const val WAVEFORM_SIZE = 96

    @Volatile var level = 0f
    @Volatile var peak = 0f

    private val waveform = FloatArray(WAVEFORM_SIZE)
    private var writeIndex = 0

    fun onPcm(pcm: ByteArray) {
        if (pcm.size < 2) return

        var sum = 0.0
        var chunkPeak = 0f
        var samples = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = pcm[i].toInt() or (pcm[i + 1].toInt() shl 8)
            val normalized = (if (sample < 32768) sample else sample - 65536) / 32768f
            val abs = kotlin.math.abs(normalized)
            sum += normalized * normalized
            if (abs > chunkPeak) chunkPeak = abs
            samples++
            i += 2
        }
        if (samples == 0) return

        val rms = sqrt(sum / samples).toFloat()
        level = rms.coerceIn(0f, 1f)
        if (chunkPeak > peak) peak = chunkPeak.coerceIn(0f, 1f)
        peak *= 0.96f

        synchronized(waveform) {
            waveform[writeIndex] = chunkPeak.coerceIn(0f, 1f)
            writeIndex = (writeIndex + 1) % WAVEFORM_SIZE
        }
    }

    /** Updates level/peak from a remote audio-level event (no PCM payload). */
    fun onLevel(level: Float, peak: Float) {
        this.level = level.coerceIn(0f, 1f)
        if (peak > this.peak) this.peak = peak.coerceIn(0f, 1f)
        this.peak *= 0.96f
        synchronized(waveform) {
            waveform[writeIndex] = peak.coerceIn(0f, 1f)
            writeIndex = (writeIndex + 1) % WAVEFORM_SIZE
        }
    }

    fun snapshotWaveform(out: FloatArray) {
        synchronized(waveform) {
            for (i in out.indices) {
                val idx = (writeIndex + i) % WAVEFORM_SIZE
                out[i] = waveform[idx]
            }
        }
    }

    fun reset() {
        level = 0f
        peak = 0f
        synchronized(waveform) {
            waveform.fill(0f)
            writeIndex = 0
        }
    }
}
