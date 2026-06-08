package dev.wyomingdroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
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

    private lateinit var statusView: TextView
    private lateinit var ipInfoView: TextView
    private lateinit var logView: TextView
    private lateinit var rainbowBanner: RainbowBannerView
    private lateinit var nameField: EditText
    private lateinit var portField: EditText
    private lateinit var startStageSpinner: Spinner
    private lateinit var audioSourceSpinner: Spinner
    private lateinit var playTtsSwitch: Switch
    private lateinit var startOnBootSwitch: Switch

    private lateinit var satellitePanel: ScrollView
    private lateinit var liveViewPanel: View
    private lateinit var tabSatellite: Button
    private lateinit var tabLiveView: Button
    private lateinit var liveSubtitleView: TextView
    private lateinit var liveVisualizer: AudioVisualizerView
    private lateinit var liveLevelView: TextView
    private lateinit var liveStatusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            if (liveViewVisible) updateLiveView()
            val delay = when {
                liveViewVisible -> 50L
                SatelliteService.processingActive -> 150L
                else -> 1000L
            }
            handler.postDelayed(this, delay)
        }
    }

    private var liveViewVisible = false
    private var localMonitor: LocalMonitorClient? = null
    private var previewCapture: AudioCapture? = null

    private val startStageValues = listOf("wake", "asr")
    private val audioSourceValues = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.UNPROCESSED,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusView = findViewById(R.id.status)
        ipInfoView = findViewById(R.id.ip_info)
        logView = findViewById(R.id.log_line)
        rainbowBanner = findViewById(R.id.rainbow_banner)
        nameField = findViewById(R.id.name)
        portField = findViewById(R.id.port)
        startStageSpinner = findViewById(R.id.start_stage)
        audioSourceSpinner = findViewById(R.id.audio_source)
        playTtsSwitch = findViewById(R.id.play_tts)
        startOnBootSwitch = findViewById(R.id.start_on_boot)

        satellitePanel = findViewById(R.id.satellite_panel)
        liveViewPanel = findViewById(R.id.live_view_panel)
        tabSatellite = findViewById(R.id.tab_satellite)
        tabLiveView = findViewById(R.id.tab_live_view)
        liveSubtitleView = findViewById(R.id.live_view_subtitle)
        liveVisualizer = findViewById(R.id.live_visualizer)
        liveLevelView = findViewById(R.id.live_level)
        liveStatusView = findViewById(R.id.live_status)

        startStageSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.start_stage_labels))
        audioSourceSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.audio_source_labels))

        loadPrefsIntoUi()
        setupDoubleTapEdit(nameField)
        setupDoubleTapEdit(portField, canEdit = { !SatelliteService.running }) {
            Toast.makeText(this, R.string.port_locked_running, Toast.LENGTH_SHORT).show()
        }
        showSatelliteTab()

        findViewById<Button>(R.id.start_button).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stop_button).setOnClickListener {
            savePrefsFromUi()
            SatelliteService.stop(this)
        }

        tabSatellite.setOnClickListener { showSatelliteTab() }
        tabLiveView.setOnClickListener { showLiveViewTab() }
        findViewById<Button>(R.id.live_view_close).setOnClickListener { showSatelliteTab() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        if (liveViewVisible) startLiveViewSources()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        stopLiveViewSources()
        savePrefsFromUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (liveViewVisible) {
            showSatelliteTab()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun showSatelliteTab() {
        liveViewVisible = false
        satellitePanel.visibility = View.VISIBLE
        liveViewPanel.visibility = View.GONE
        updateTabSelection(satelliteSelected = true)
        stopLiveViewSources()
    }

    private fun showLiveViewTab() {
        liveViewVisible = true
        satellitePanel.visibility = View.GONE
        liveViewPanel.visibility = View.VISIBLE
        updateTabSelection(satelliteSelected = false)
        updateLiveViewHeader()
        startLiveViewSources()
    }

    private fun updateTabSelection(satelliteSelected: Boolean) {
        tabSatellite.isSelected = satelliteSelected
        tabLiveView.isSelected = !satelliteSelected
    }

    private fun startLiveViewSources() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_LIVE)
            return
        }
        stopLiveViewSources()
        MicLevelMonitor.reset()

        if (SatelliteService.running) {
            localMonitor = LocalMonitorClient(
                port = prefs.port,
                onConnected = { handler.post { liveStatusView.setText(R.string.live_view_listening) } },
                onDisconnected = { msg ->
                    handler.post { liveStatusView.text = msg }
                },
            ).also { it.start() }
            return
        }

        val capture = AudioCapture(Prefs.SAMPLE_RATE, prefs.audioSource)
        try {
            capture.start { pcm -> MicLevelMonitor.onPcm(pcm) }
            previewCapture = capture
            liveStatusView.setText(R.string.live_view_preview)
        } catch (e: Exception) {
            liveStatusView.text = e.message ?: "Mic error"
        }
    }

    private fun stopLiveViewSources() {
        localMonitor?.stop()
        localMonitor = null
        previewCapture?.stop()
        previewCapture = null
    }

    private fun updateLiveViewHeader() {
        val ip = getLocalIpAddress() ?: "—"
        liveSubtitleView.text = getString(
            R.string.live_view_subtitle,
            prefs.satelliteName,
            ip,
            prefs.port,
        )
    }

    private fun updateLiveView() {
        updateLiveViewHeader()
        val processing = SatelliteService.processingActive
        liveVisualizer.processing = processing
        liveVisualizer.updateFromMonitor()

        val active = liveVisualizer.active || processing
        liveLevelView.text = if (active) {
            getString(
                R.string.live_view_level,
                (MicLevelMonitor.level * 100).toInt(),
                (MicLevelMonitor.peak * 100).toInt(),
            )
        } else {
            getString(R.string.live_view_idle)
        }

        if (processing) {
            liveStatusView.setText(R.string.live_view_processing)
        } else if (SatelliteService.streaming) {
            liveStatusView.setText(R.string.live_view_listening)
        }
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
        savePrefsFromUi()
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        SatelliteService.start(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MIC -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                SatelliteService.start(this)
            } else {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
            }
            REQ_MIC_LIVE -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startLiveViewSources()
            } else {
                Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatus() {
        val running = SatelliteService.running
        val processing = SatelliteService.processingActive

        rainbowBanner.active = processing
        applyBannerTextColors(processing)

        statusView.setText(
            when {
                processing -> R.string.processing
                running -> R.string.status_running
                else -> R.string.status_stopped
            },
        )

        val ip = getLocalIpAddress()
        ipInfoView.text = if (ip != null) {
            getString(R.string.ip_template, ip, prefs.port)
        } else {
            getString(R.string.ip_unknown)
        }

        logView.text = when {
            processing -> SatelliteService.statusLine.ifBlank { getString(R.string.processing) }
            !running -> ""
            SatelliteService.connectionCount == 0 -> getString(R.string.waiting_for_ha)
            SatelliteService.streaming -> getString(R.string.streaming, SatelliteService.connectionCount)
            else -> getString(R.string.connected, SatelliteService.connectionCount)
        }

        portField.isEnabled = !running
        portField.alpha = if (running) 0.55f else 1f
        if (running) {
            lockField(portField)
        }
    }

    private fun applyBannerTextColors(processing: Boolean) {
        val primary = ContextCompat.getColor(
            this,
            if (processing) R.color.banner_text else R.color.status_text,
        )
        val secondary = ContextCompat.getColor(
            this,
            if (processing) R.color.banner_text_secondary else R.color.status_text_secondary,
        )
        val muted = ContextCompat.getColor(
            this,
            if (processing) R.color.banner_text_secondary else R.color.status_text_muted,
        )
        statusView.setTextColor(primary)
        ipInfoView.setTextColor(secondary)
        logView.setTextColor(muted)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

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
        private const val REQ_MIC_LIVE = 101
    }
}
