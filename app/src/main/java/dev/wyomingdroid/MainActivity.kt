package dev.wyomingdroid

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            val delay = if (SatelliteService.processingActive) 150L else 1000L
            handler.postDelayed(this, delay)
        }
    }

    // value -> label for the start-stage spinner
    private val startStageValues = listOf("wake", "asr")

    // MediaRecorder.AudioSource value for each audio-source spinner entry
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

        startStageSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.start_stage_labels))
        audioSourceSpinner.adapter = simpleAdapter(resources.getStringArray(R.array.audio_source_labels))

        loadPrefsIntoUi()

        findViewById<Button>(R.id.start_button).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stop_button).setOnClickListener {
            savePrefsFromUi()
            SatelliteService.stop(this)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
        // Persist edits so a reboot / boot-start uses the latest config.
        savePrefsFromUi()
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
        prefs.port = portField.text.toString().toIntOrNull()?.coerceIn(1, 65535) ?: Prefs.DEFAULT_PORT
        prefs.startStage = startStageValues[startStageSpinner.selectedItemPosition]
        prefs.audioSource = audioSourceValues[audioSourceSpinner.selectedItemPosition]
        prefs.playTts = playTtsSwitch.isChecked
        prefs.startOnBoot = startOnBootSwitch.isChecked
    }

    private fun onStartClicked() {
        savePrefsFromUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC,
            )
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
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                SatelliteService.start(this)
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

    /** First non-loopback IPv4 address (works for both Wi-Fi and Ethernet). */
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
    }
}
