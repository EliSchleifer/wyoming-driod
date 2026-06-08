package dev.wyomingdroid

import android.content.Context
import android.media.MediaRecorder

/**
 * Thin wrapper around SharedPreferences holding all satellite configuration.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("wyoming_droid", Context.MODE_PRIVATE)

    var satelliteName: String
        get() = sp.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    /** Wyoming pipeline start stage: "wake" (server-side wake word) or "asr". */
    var startStage: String
        get() = sp.getString(KEY_START_STAGE, DEFAULT_START_STAGE) ?: DEFAULT_START_STAGE
        set(value) = sp.edit().putString(KEY_START_STAGE, value).apply()

    /** Whether to play TTS audio that Home Assistant streams back. */
    var playTts: Boolean
        get() = sp.getBoolean(KEY_PLAY_TTS, true)
        set(value) = sp.edit().putBoolean(KEY_PLAY_TTS, value).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = sp.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    /** One of the MediaRecorder.AudioSource constants. */
    var audioSource: Int
        get() = sp.getInt(KEY_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        set(value) = sp.edit().putInt(KEY_AUDIO_SOURCE, value).apply()

    /** Set at boot when auto-start is pending; cleared once the service runs or the user stops it. */
    var pendingBootStart: Boolean
        get() = sp.getBoolean(KEY_PENDING_BOOT_START, false)
        set(value) = sp.edit().putBoolean(KEY_PENDING_BOOT_START, value).apply()

    /** Remembers whether the user last wanted the service running (for recovery). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    /** Set when the user taps Stop; cleared on boot and manual start. */
    var userStopped: Boolean
        get() = sp.getBoolean(KEY_USER_STOPPED, false)
        set(value) = sp.edit().putBoolean(KEY_USER_STOPPED, value).apply()

    /** When true, auto-start runs headless and the UI moves to the background for kiosk use. */
    var hideUiForKiosk: Boolean
        get() = sp.getBoolean(KEY_HIDE_UI_FOR_KIOSK, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_UI_FOR_KIOSK, value).apply()

    companion object {
        const val SAMPLE_RATE = 16_000 // Wyoming/Assist expects 16 kHz mono 16-bit
        const val DEFAULT_PORT = 10700
        const val DEFAULT_NAME = "Android Satellite"
        const val DEFAULT_START_STAGE = "wake"

        private const val KEY_NAME = "name"
        private const val KEY_PORT = "port"
        private const val KEY_START_STAGE = "start_stage"
        private const val KEY_PLAY_TTS = "play_tts"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_PENDING_BOOT_START = "pending_boot_start"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_USER_STOPPED = "user_stopped"
        private const val KEY_HIDE_UI_FOR_KIOSK = "hide_ui_for_kiosk"
    }
}
