package dev.wyomingdroid.wyoming

import dev.wyomingdroid.MicLevelMonitor
import dev.wyomingdroid.audio.AudioCapture
import dev.wyomingdroid.audio.AudioPlayback
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.AUDIO_CHUNK
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.AUDIO_START
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.AUDIO_STOP
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.DESCRIBE
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.DETECTION
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.INFO
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.MONITOR
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.MONITOR_STARTED
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.AUDIO_LEVEL
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.PAUSE_SATELLITE
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.PING
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.PONG
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.RUN_PIPELINE
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.RUN_SATELLITE
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.SYNTHESIZE
import dev.wyomingdroid.wyoming.WyomingEvent.Companion.TRANSCRIPT
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A Wyoming protocol server. Home Assistant's Wyoming integration connects to
 * us as a TCP client; we advertise ourselves as an Assist satellite and stream
 * continuous microphone audio. The flow per connection is:
 *
 *   HA -> describe          we reply with `info` (satellite capabilities)
 *   HA -> run-satellite     we reply `run-pipeline` + `audio-start`, then begin
 *                           streaming `audio-chunk`s continuously
 *   HA -> ping              we reply `pong`
 *   HA -> audio-start/chunk/stop   TTS response audio -> local playback
 *   HA -> pause-satellite   stop streaming microphone audio
 */
class WyomingServer(
    private val config: Config,
    private val listener: Listener,
) {

    data class Config(
        val port: Int,
        val satelliteName: String,
        val startStage: String,
        val playTts: Boolean,
        val sampleRate: Int,
        val audioSource: Int,
    )

    interface Listener {
        fun onStateChanged(connections: Int, streaming: Boolean)
        fun onProcessingChanged(active: Boolean)
        fun onLog(message: String)
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val connections = CopyOnWriteArraySet<ClientConnection>()
    private val streamers = CopyOnWriteArraySet<ClientConnection>()
    private val monitors = CopyOnWriteArraySet<ClientConnection>()

    private val playback = AudioPlayback()

    @Volatile private var processingActive = false

    // Guarded by `captureLock`.
    private val captureLock = Any()
    private var audioCapture: AudioCapture? = null

    /** Binds the listening socket. Throws on failure (e.g. port in use). */
    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(config.port))
        serverSocket = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "wyoming-accept").also { it.start() }
        listener.onLog("Listening on port ${config.port}")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        connections.forEach { it.close() }
        connections.clear()
        streamers.clear()
        monitors.clear()
        synchronized(captureLock) {
            audioCapture?.stop()
            audioCapture = null
        }
        playback.stop()
        setProcessing(false)
        notifyState()
    }

    fun isHealthy(): Boolean = running && serverSocket?.isClosed == false

    /** Retries microphone capture when clients are connected but capture died. */
    fun ensureAudioCapture() {
        if (streamers.isEmpty() && monitors.isEmpty()) return
        synchronized(captureLock) {
            if (audioCapture?.isRunning == true) return
            audioCapture = null
        }
        ensureCapture()
    }

    private fun setProcessing(active: Boolean) {
        if (processingActive == active) return
        processingActive = active
        listener.onProcessingChanged(active)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val socket = ss.accept()
                socket.tcpNoDelay = true
                val conn = ClientConnection(socket)
                connections.add(conn)
                notifyState()
                conn.start()
            } catch (e: Exception) {
                if (running) {
                    listener.onLog("Accept error: ${e.message}")
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun notifyState() {
        listener.onStateChanged(connections.size, streamers.isNotEmpty())
    }

    // --- Shared microphone capture, started on demand --------------------------

    private fun addStreamer(conn: ClientConnection) {
        streamers.add(conn)
        ensureCapture()
        notifyState()
    }

    private fun removeStreamer(conn: ClientConnection) {
        streamers.remove(conn)
        maybeStopCapture()
        notifyState()
    }

    private fun addMonitor(conn: ClientConnection) {
        monitors.add(conn)
        ensureCapture()
    }

    private fun removeMonitor(conn: ClientConnection) {
        monitors.remove(conn)
        maybeStopCapture()
    }

    private fun ensureCapture() {
        synchronized(captureLock) {
            if (audioCapture != null) return
            val cap = AudioCapture(config.sampleRate, config.audioSource)
            try {
                cap.start { pcm ->
                    MicLevelMonitor.onPcm(pcm)
                    streamers.forEach { it.sendAudioChunk(pcm) }
                    if (monitors.isNotEmpty()) {
                        val level = MicLevelMonitor.level
                        val peak = MicLevelMonitor.peak
                        monitors.forEach { it.sendAudioLevel(level, peak) }
                    }
                }
                audioCapture = cap
                listener.onLog("Microphone capture started")
            } catch (e: Exception) {
                listener.onLog("Microphone error: ${e.message}")
            }
        }
    }

    private fun maybeStopCapture() {
        if (streamers.isNotEmpty() || monitors.isNotEmpty()) return
        val cap: AudioCapture?
        synchronized(captureLock) {
            cap = audioCapture
            audioCapture = null
        }
        if (cap != null) {
            Thread({ cap.stop() }, "wyoming-cap-stop").start()
            listener.onLog("Microphone capture stopped")
        }
    }

    // --- One Home Assistant connection -----------------------------------------

    inner class ClientConnection(private val socket: Socket) {

        private val input = BufferedInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()
        private val writeLock = Any()

        @Volatile private var alive = true
        @Volatile private var streaming = false
        @Volatile private var monitoring = false
        private var samplesSent = 0L
        private var readerThread: Thread? = null

        fun start() {
            readerThread = Thread({ readLoop() }, "wyoming-client").also { it.start() }
        }

        private fun write(event: WyomingEvent) {
            synchronized(writeLock) { WyomingIo.writeEvent(output, event) }
        }

        private fun readLoop() {
            try {
                while (alive) {
                    val event = WyomingIo.readEvent(input) ?: break
                    handle(event)
                }
            } catch (e: Exception) {
                if (alive) listener.onLog("Connection closed: ${e.message}")
            } finally {
                close()
            }
        }

        private fun handle(event: WyomingEvent) {
            when (event.type) {
                "" -> {} // stray blank line
                DESCRIBE -> write(buildInfo())
                MONITOR -> startMonitoring()
                RUN_SATELLITE -> startStreaming()
                PAUSE_SATELLITE -> stopStreaming()
                PING -> write(WyomingEvent(PONG, event.data))
                AUDIO_START -> if (config.playTts) {
                    val rate = event.data?.optInt("rate", config.sampleRate) ?: config.sampleRate
                    val channels = event.data?.optInt("channels", 1) ?: 1
                    playback.start(rate, channels)
                }
                AUDIO_CHUNK -> if (config.playTts) event.payload?.let { playback.write(it) }
                AUDIO_STOP -> {
                    if (config.playTts) {
                        playback.stop()
                        setProcessing(false)
                    }
                }
                TRANSCRIPT -> listener.onLog("Heard: \"${event.data?.optString("text", "")}\"")
                SYNTHESIZE -> {
                    listener.onLog("Reply: \"${event.data?.optString("text", "")}\"")
                    if (!config.playTts) setProcessing(false)
                }
                DETECTION -> {
                    listener.onLog("Wake word detected")
                    setProcessing(true)
                }
                WyomingEvent.VOICE_STARTED -> setProcessing(true)
                WyomingEvent.VOICE_STOPPED -> {
                    if (!config.playTts) setProcessing(false)
                }
                else -> { /* timers, etc. — ignored */ }
            }
        }

        private fun startMonitoring() {
            if (monitoring) return
            monitoring = true
            addMonitor(this)
            write(WyomingEvent(MONITOR_STARTED, JSONObject()))
            listener.onLog("Audio monitor connected")
        }

        private fun stopMonitoring() {
            if (!monitoring) return
            monitoring = false
            removeMonitor(this)
        }

        fun sendAudioLevel(level: Float, peak: Float) {
            if (!monitoring || !alive) return
            try {
                val data = JSONObject().put("level", level.toDouble()).put("peak", peak.toDouble())
                write(WyomingEvent(AUDIO_LEVEL, data))
            } catch (_: Exception) {
                close()
            }
        }

        private fun startStreaming() {
            if (streaming) return
            // Ask HA to run a pipeline, then open one continuous audio stream.
            write(buildRunPipeline())
            samplesSent = 0
            write(audioStartEvent())
            streaming = true
            addStreamer(this)
        }

        private fun stopStreaming() {
            if (!streaming) return
            streaming = false
            removeStreamer(this)
        }

        fun sendAudioChunk(pcm: ByteArray) {
            if (!streaming || !alive) return
            try {
                val data = JSONObject()
                    .put("rate", config.sampleRate)
                    .put("width", 2)
                    .put("channels", 1)
                    .put("timestamp", samplesSent * 1000 / config.sampleRate)
                write(WyomingEvent(AUDIO_CHUNK, data, pcm))
                samplesSent += pcm.size / 2
            } catch (e: Exception) {
                close()
            }
        }

        fun close() {
            if (!alive) return
            alive = false
            stopStreaming()
            stopMonitoring()
            try {
                socket.close()
            } catch (_: Exception) {
            }
            connections.remove(this)
            notifyState()
        }

        private fun buildInfo(): WyomingEvent {
            val attribution = JSONObject()
                .put("name", "wyoming-droid")
                .put("url", "https://github.com/EliSchleifer/wyoming-driod")
            val satellite = JSONObject()
                .put("name", config.satelliteName)
                .put("attribution", attribution)
                .put("installed", true)
                .put("description", "Wyoming satellite running on Android")
                .put("version", "1.0.0")
                .put("area", JSONObject.NULL)
                .put("has_vad", false)
                .put("active_wake_words", JSONArray())
                .put("max_active_wake_words", 1)
                .put("supports_trigger", false)
            val data = JSONObject()
                .put("asr", JSONArray())
                .put("tts", JSONArray())
                .put("handle", JSONArray())
                .put("intent", JSONArray())
                .put("wake", JSONArray())
                .put("mic", JSONArray())
                .put("snd", JSONArray())
                .put("satellite", satellite)
            return WyomingEvent(INFO, data)
        }

        private fun buildRunPipeline(): WyomingEvent {
            val data = JSONObject()
                .put("start_stage", config.startStage)
                .put("end_stage", if (config.playTts) "tts" else "handle")
                .put("restart_on_end", true)
            if (config.playTts) {
                data.put(
                    "snd_format",
                    JSONObject().put("rate", config.sampleRate).put("width", 2).put("channels", 1),
                )
            }
            return WyomingEvent(RUN_PIPELINE, data)
        }

        private fun audioStartEvent(): WyomingEvent {
            val data = JSONObject()
                .put("rate", config.sampleRate)
                .put("width", 2)
                .put("channels", 1)
                .put("timestamp", 0)
            return WyomingEvent(AUDIO_START, data)
        }
    }
}
