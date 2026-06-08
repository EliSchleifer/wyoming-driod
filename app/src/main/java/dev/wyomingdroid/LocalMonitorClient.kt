package dev.wyomingdroid

import dev.wyomingdroid.wyoming.WyomingEvent
import dev.wyomingdroid.wyoming.WyomingIo
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Subscribes to audio-level events from the local satellite service so Live
 * view can show the same mic stream Home Assistant receives.
 */
class LocalMonitorClient(
    private val port: Int,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "local-monitor").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        while (running) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                WyomingIo.writeEvent(output, WyomingEvent(WyomingEvent.DESCRIBE))
                readUntil(input, WyomingEvent.INFO)
                WyomingIo.writeEvent(output, WyomingEvent(WyomingEvent.MONITOR))
                readUntil(input, WyomingEvent.MONITOR_STARTED)
                onConnected()

                while (running) {
                    val event = WyomingIo.readEvent(input) ?: break
                    when (event.type) {
                        WyomingEvent.AUDIO_LEVEL -> {
                            val level = event.data?.optDouble("level", 0.0)?.toFloat() ?: 0f
                            val peak = event.data?.optDouble("peak", 0.0)?.toFloat() ?: 0f
                            MicLevelMonitor.onLevel(level, peak)
                        }
                        WyomingEvent.PING -> WyomingIo.writeEvent(output, WyomingEvent(WyomingEvent.PONG, event.data))
                    }
                }
            } catch (e: Exception) {
                if (running) onDisconnected(e.message ?: "Disconnected")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            if (!running) break
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun readUntil(input: BufferedInputStream, type: String) {
        repeat(20) {
            val event = WyomingIo.readEvent(input) ?: return
            if (event.type == type) return
        }
    }
}
