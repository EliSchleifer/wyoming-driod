package dev.wyomingdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.wyomingdroid.wyoming.WyomingServer

/**
 * Foreground service that owns the Wyoming server, the microphone, and the
 * locks needed to keep streaming reliably while the screen is off.
 */
class SatelliteService : Service(), WyomingServer.Listener {

    private var server: WyomingServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        if (server != null) {
            // Already running; just refresh the notification.
            startForeground(NOTIF_ID, buildNotification())
            return START_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        startSatellite()
        return START_STICKY
    }

    private fun startSatellite() {
        val prefs = Prefs(this)
        prefs.serviceEnabled = true

        acquireLocks()

        val config = WyomingServer.Config(
            port = prefs.port,
            satelliteName = prefs.satelliteName,
            startStage = prefs.startStage,
            playTts = prefs.playTts,
            sampleRate = Prefs.SAMPLE_RATE,
            audioSource = prefs.audioSource,
        )
        val srv = WyomingServer(config, this)
        try {
            srv.start()
            server = srv
        } catch (e: Exception) {
            onLog("Failed to start server: ${e.message}")
            Log.e(TAG, "start failed", e)
            stopEverything()
            stopSelf()
            return
        }

        advertiser = NsdAdvertiser(this).also { it.register(prefs.satelliteName, prefs.port) }

        running = true
        updateNotification()
    }

    private fun stopEverything() {
        running = false
        connectionCount = 0
        streaming = false
        processingActive = false
        Prefs(this).serviceEnabled = false
        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        releaseLocks()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // --- WyomingServer.Listener ------------------------------------------------

    override fun onStateChanged(connections: Int, streaming: Boolean) {
        connectionCount = connections
        SatelliteService.streaming = streaming
        updateNotification()
    }

    override fun onProcessingChanged(active: Boolean) {
        processingActive = active
        updateNotification()
    }

    override fun onLog(message: String) {
        Log.i(TAG, message)
        statusLine = message
        updateNotification()
    }

    // --- Locks -----------------------------------------------------------------

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = wifi.createMulticastLock("$TAG:mcast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    // --- Notification ----------------------------------------------------------

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        createChannel()

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SatelliteService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags(),
        )

        val text = when {
            processingActive -> "Listening…"
            connectionCount == 0 -> "Waiting for Home Assistant…"
            streaming -> "Streaming audio ($connectionCount connected)"
            else -> "Connected ($connectionCount)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wyoming Satellite")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_satellite)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Satellite status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that the Wyoming satellite is running" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    companion object {
        private const val TAG = "SatelliteService"
        private const val CHANNEL_ID = "satellite_status"
        private const val NOTIF_ID = 1

        const val ACTION_START = "dev.wyomingdroid.action.START"
        const val ACTION_STOP = "dev.wyomingdroid.action.STOP"

        // Lightweight state surfaced to the UI (polled by MainActivity).
        @Volatile var running = false
        @Volatile var connectionCount = 0
        @Volatile var streaming = false
        @Volatile var processingActive = false
        @Volatile var statusLine = ""

        fun start(context: Context) {
            val intent = Intent(context, SatelliteService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SatelliteService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
