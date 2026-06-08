package dev.wyomingdroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.wyomingdroid.audio.AudioCapture
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var rainbowBanner: RainbowBannerView
    private lateinit var titleStatusDot: StatusDotView
    private lateinit var connectionLine: TextView
    private lateinit var nameField: EditText
    private lateinit var portField: EditText
    private lateinit var startStageSpinner: Spinner
    private lateinit var audioSourceSpinner: Spinner
    private lateinit var playTtsSwitch: Switch
    private lateinit var startOnBootSwitch: Switch
    private lateinit var hideUiForKioskSwitch: Switch
    private lateinit var satelliteToggleButton: Button
    private lateinit var micVisualizer: AudioVisualizerView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            updateMicVisualizer()
            val delay = when {
                SatelliteService.processingActive -> 150L
                else -> 50L
            }
            handler.postDelayed(this, delay)
        }
    }

    private var localMonitor: LocalMonitorClient? = null
    private var previewCapture: AudioCapture? = null
    private var wasRunning = false
    private var pendingStartAfterMic = false
    private var pendingAutoStartAfterMic = false

    private val startStageValues = listOf("wake", "asr")
    private val audioSourceValues = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.UNPROCESSED,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (shouldLaunchHeadless()) {
            super.onCreate(savedInstanceState)
            SatelliteAutoStart.startIfNeeded(this)
            finish()
            return
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindUi()
        tryAutoStartIfNeeded()
        maybeHideForKiosk()
    }

    private fun shouldLaunchHeadless(): Boolean {
        if (intent.getBooleanExtra(EXTRA_SHOW_UI, false)) return false
        if (!prefs.hideUiForKiosk || !prefs.startOnBoot || prefs.userStopped) return false
        if (!SatelliteAutoStart.hasMicPermission(this)) return false
        return true
    }

    private fun bindUi() {
        rainbowBanner = findViewById(R.id.rainbow_banner)
        titleStatusDot = findViewById(R.id.title_status_dot)
        connectionLine = findViewById(R.id.connection_line)
        nameField = findViewById(R.id.name)
        portField = findViewById(R.id.port)
        startStageSpinner = findViewById(R.id.start_stage)
        audioSourceSpinner = findViewById(R.id.audio_source)
        playTtsSwitch = findViewById(R.id.play_tts)
        startOnBootSwitch = findViewById(R.id.start_on_boot)
        hideUiForKioskSwitch = findViewById(R.id.hide_ui_for_kiosk)
        satelliteToggleButton = findViewById(R.id.satellite_toggle_button)
        micVisualizer = findViewById(R.id.mic_visualizer)

        startStageSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.start_stage_labels))
        audioSourceSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.audio_source_labels))

        loadPrefsIntoUi()
        setupDoubleTapEdit(nameField)
        setupDoubleTapEdit(portField, canEdit = { !SatelliteService.running }) {
            Toast.makeText(this, R.string.port_locked_running, Toast.LENGTH_SHORT).show()
        }

        satelliteToggleButton.setOnClickListener { onToggleSatelliteClicked() }
        updateToggleButton()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        handler.post(refresh)
        tryAutoStartIfNeeded()
        startMicMonitor()
        maybeHideForKiosk()
    }

    private fun maybeHideForKiosk() {
        if (!prefs.hideUiForKiosk || !prefs.startOnBoot || prefs.userStopped) return
        if (!SatelliteService.running) return
        if (intent.getBooleanExtra(EXTRA_SHOW_UI, false)) return
        handler.postDelayed({
            if (!isFinishing && SatelliteService.running) {
                moveTaskToBack(true)
            }
        }, 400)
    }

    /** Start automatically when "Start on boot" is enabled (kiosk launch, post-reboot, etc.). */
    private fun tryAutoStartIfNeeded() {
        if (!prefs.startOnBoot || prefs.userStopped || SatelliteService.running) return
        attemptStartSatellite(auto = true)
    }

    private fun attemptStartSatellite(auto: Boolean) {
        savePrefsFromUi()
        if (!hasMicPermission()) {
            if (auto) pendingAutoStartAfterMic = true else pendingStartAfterMic = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        pendingAutoStartAfterMic = false
        pendingStartAfterMic = false
        prefs.userStopped = false
        prefs.serviceEnabled = true
        stopMicMonitor()
        SatelliteService.start(this)
        if (auto) maybeHideForKiosk()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        stopMicMonitor()
        savePrefsFromUi()
    }

    private fun startMicMonitor() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        stopMicMonitor()
        MicLevelMonitor.reset()
        micVisualizer.statusMessage = null

        if (SatelliteService.running) {
            localMonitor = LocalMonitorClient(
                port = prefs.port,
                onConnected = { handler.post { micVisualizer.statusMessage = null } },
                onDisconnected = { msg -> handler.post { micVisualizer.statusMessage = msg } },
            ).also { it.start() }
            return
        }

        val capture = AudioCapture(Prefs.SAMPLE_RATE, prefs.audioSource)
        try {
            capture.start { pcm -> MicLevelMonitor.onPcm(pcm) }
            previewCapture = capture
        } catch (e: Exception) {
            micVisualizer.statusMessage = e.message ?: "Mic error"
        }
    }

    private fun stopMicMonitor() {
        localMonitor?.stop()
        localMonitor = null
        previewCapture?.stop()
        previewCapture = null
    }

    private fun updateMicVisualizer() {
        val processing = SatelliteService.processingActive
        micVisualizer.processing = processing
        micVisualizer.updateFromMonitor()
    }

    private fun simpleAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun loadPrefsIntoUi() {
        nameField.setText(prefs.satelliteName)
        portField.setText(prefs.port.toString())
        startStageSpinner.setSelection(startStageValues.indexOf(prefs.startStage).coerceAtLeast(0))
        audioSourceSpinner.setSelection(audioSourceValues.indexOf(prefs.audioSource).coerceAtLeast(0))
        playTtsSwitch.isChecked = prefs.playTts
        startOnBootSwitch.isChecked = prefs.startOnBoot
        hideUiForKioskSwitch.isChecked = prefs.hideUiForKiosk
    }

    private fun savePrefsFromUi() {
        prefs.satelliteName = nameField.text.toString().ifBlank { Prefs.DEFAULT_NAME }
        if (!SatelliteService.running) {
            prefs.port = portField.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: Prefs.DEFAULT_PORT
        }
        prefs.startStage = startStageValues[startStageSpinner.selectedItemPosition]
        prefs.audioSource = audioSourceValues[audioSourceSpinner.selectedItemPosition]
        prefs.playTts = playTtsSwitch.isChecked
        prefs.startOnBoot = startOnBootSwitch.isChecked
        prefs.hideUiForKiosk = hideUiForKioskSwitch.isChecked
        if (!startOnBootSwitch.isChecked) {
            prefs.pendingBootStart = false
            BootStartScheduler.cancel(this)
        }
    }

    private fun setupDoubleTapEdit(
        field: EditText,
        canEdit: () -> Boolean = { true },
        onLockedTap: (() -> Unit)? = null,
    ) {
        lockField(field)
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!canEdit()) {
                        onLockedTap?.invoke()
                        return true
                    }
                    unlockField(field)
                    return true
                }
            },
        )
        field.setOnTouchListener { view, event ->
            if (detector.onTouchEvent(event)) {
                true
            } else {
                view.onTouchEvent(event)
            }
        }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                lockField(field)
            }
        }
    }

    private fun unlockField(field: EditText) {
        field.isFocusable = true
        field.isFocusableInTouchMode = true
        field.isCursorVisible = true
        field.requestFocus()
        field.setSelection(field.text.length)
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun lockField(field: EditText) {
        field.isFocusable = false
        field.isFocusableInTouchMode = false
        field.isCursorVisible = false
        field.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun onStartClicked() {
        attemptStartSatellite(auto = false)
    }

    private fun onToggleSatelliteClicked() {
        if (SatelliteService.running) {
            savePrefsFromUi()
            prefs.userStopped = true
            SatelliteService.stop(this)
        } else {
            onStartClicked()
        }
    }

    private fun updateToggleButton() {
        satelliteToggleButton.setText(
            if (SatelliteService.running) R.string.action_stop else R.string.action_start,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC || grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterMic = false
            pendingAutoStartAfterMic = false
            if (requestCode == REQ_MIC) {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (pendingStartAfterMic || pendingAutoStartAfterMic) {
            pendingStartAfterMic = false
            pendingAutoStartAfterMic = false
            prefs.userStopped = false
            prefs.serviceEnabled = true
            stopMicMonitor()
            SatelliteService.start(this)
        } else {
            startMicMonitor()
        }
    }

    private fun updateStatus() {
        val running = SatelliteService.running
        val processing = SatelliteService.processingActive

        rainbowBanner.visibility = if (processing) View.VISIBLE else View.GONE
        rainbowBanner.active = processing
        titleStatusDot.live = running
        connectionLine.text = buildConnectionLine(running, processing)

        portField.isEnabled = !running
        portField.alpha = if (running) 0.55f else 1f
        if (running) {
            lockField(portField)
        }
        updateToggleButton()

        if (running != wasRunning) {
            wasRunning = running
            startMicMonitor()
        }
    }

    private fun buildConnectionLine(running: Boolean, processing: Boolean): String {
        val ip = getLocalIpAddress()
        val address = if (ip != null) {
            getString(R.string.ip_template, ip, prefs.port)
        } else {
            getString(R.string.ip_unknown)
        }
        if (!running) {
            return getString(R.string.status_line_stopped, address)
        }
        val streamStatus = when {
            processing -> getString(R.string.processing)
            SatelliteService.connectionCount == 0 -> getString(R.string.waiting_for_ha)
            SatelliteService.streaming -> getString(R.string.streaming, SatelliteService.connectionCount)
            else -> getString(R.string.connected, SatelliteService.connectionCount)
        }
        return getString(R.string.status_line_active, address, streamStatus)
    }

    private fun hasMicPermission(): Boolean = SatelliteAutoStart.hasMicPermission(this)

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val REQ_MIC = 100
        const val EXTRA_SHOW_UI = "show_ui"
    }
}
