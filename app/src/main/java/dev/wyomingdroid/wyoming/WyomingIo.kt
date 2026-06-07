package dev.wyomingdroid.wyoming

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes [WyomingEvent]s using the exact framing of the reference
 * `wyoming` Python library so Home Assistant can talk to us unmodified.
 *
 * Wire format produced by `wyoming.event.write_event`:
 *
 *   {"type": "...", "data_length": N, "payload_length": M, "version": "..."}\n
 *   <N bytes of UTF-8 JSON `data`>
 *   <M bytes of raw payload>
 *
 * `data_length` / `payload_length` are only present when non-null. Note that
 * `data` is NOT inlined in the header line — it is length-prefixed after it.
 */
object WyomingIo {

    /** Reported in the header. Informational only; HA does not gate on it. */
    private const val VERSION = "1.5.4"

    /**
     * Writes [event] to [out]. Callers must serialize access to [out] (one
     * event at a time) because a single event spans multiple writes.
     */
    fun writeEvent(out: OutputStream, event: WyomingEvent) {
        val header = JSONObject()
        header.put("type", event.type)

        var dataBytes: ByteArray? = null
        event.data?.let {
            dataBytes = it.toString().toByteArray(Charsets.UTF_8)
            header.put("data_length", dataBytes!!.size)
        }
        event.payload?.let {
            header.put("payload_length", it.size)
        }
        header.put("version", VERSION)

        out.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
        dataBytes?.let { out.write(it) }
        event.payload?.let { out.write(it) }
        out.flush()
    }

    /**
     * Reads the next event from [input], or null on clean end-of-stream.
     * [input] should be the same buffered stream across calls.
     */
    fun readEvent(input: BufferedInputStream): WyomingEvent? {
        val line = readLine(input) ?: return null
        if (line.isBlank()) return WyomingEvent("") // ignore stray blank lines

        val header = JSONObject(line)
        val type = header.getString("type")

        // `data` is normally length-prefixed after the header, but tolerate an
        // inline `data` object too for forward/backward compatibility.
        var data: JSONObject? = header.optJSONObject("data")

        if (header.has("data_length")) {
            val dataLen = header.getInt("data_length")
            if (dataLen > 0) {
                val dataBytes = readExactly(input, dataLen)
                data = JSONObject(String(dataBytes, Charsets.UTF_8))
            }
        }

        var payload: ByteArray? = null
        if (header.has("payload_length")) {
            val payloadLen = header.getInt("payload_length")
            if (payloadLen > 0) {
                payload = readExactly(input, payloadLen)
            }
        }

        return WyomingEvent(type, data, payload)
    }

    /** Reads a single `\n`-terminated line as UTF-8 (newline not included). */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    /** Reads exactly [n] bytes or throws on premature end-of-stream. */
    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(data, off, n - off)
            if (r == -1) throw EOFException("stream closed mid-frame")
            off += r
        }
        return data
    }
}
