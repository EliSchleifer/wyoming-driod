package dev.wyomingdroid.wyoming

import org.json.JSONObject

/**
 * A single Wyoming protocol event.
 *
 * On the wire an event is a JSON header line (containing at least `type`, plus
 * optional `data_length`/`payload_length`/`version`) terminated by `\n`,
 * followed by `data_length` bytes of UTF-8 JSON [data], then `payload_length`
 * bytes of raw [payload].
 */
data class WyomingEvent(
    val type: String,
    val data: JSONObject? = null,
    val payload: ByteArray? = null,
) {
    // Event types used by this satellite.
    companion object {
        // Received from Home Assistant
        const val DESCRIBE = "describe"
        const val RUN_SATELLITE = "run-satellite"
        const val PAUSE_SATELLITE = "pause-satellite"
        const val PING = "ping"
        const val DETECTION = "detection"
        const val TRANSCRIPT = "transcript"
        const val SYNTHESIZE = "synthesize"
        const val VOICE_STARTED = "voice-started"
        const val VOICE_STOPPED = "voice-stopped"
        const val AUDIO_START = "audio-start"
        const val AUDIO_CHUNK = "audio-chunk"
        const val AUDIO_STOP = "audio-stop"

        // Remote audio level monitoring (Home Assistant Voice Lab)
        const val MONITOR = "monitor"
        const val MONITOR_STARTED = "monitor-started"
        const val AUDIO_LEVEL = "audio-level"

        // Sent to Home Assistant
        const val INFO = "info"
        const val RUN_PIPELINE = "run-pipeline"
        const val PONG = "pong"
        const val SATELLITE_CONNECTED = "satellite-connected"
    }
}
