package dev.wyomingdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private var userStopped = false
    private var advertisedName: String? = null
    private var advertisedPort: Int = Prefs.DEFAULT_PORT

    private val healthCheck = object : Runnable {
        override fun run() {
            if (!userStopped && Prefs(this@SatelliteService).serviceEnabled) {
                runHealthCheck()
            }
            if (!userStopped && running) {
                handler.postDelayed(this, HEALTH_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            userStopped = true
            Prefs(this).serviceEnabled = false
            Prefs(this).pendingBootStart = false
            Prefs(this).userStopped = true
            ServiceRecovery.cancel(this)
            BootStartScheduler.cancel(this)
            tearDown()
            stopSelf()
            return START_NOT_STICKY
        }

        userStopped = false
        startForeground(NOTIF_ID, buildNotification())

        if (server == null) {
            startSatellite()
        } else {
            runHealthCheck()
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!userStopped && Prefs(this).serviceEnabled) {
            Log.i(TAG, "Task removed — keeping satellite alive")
            start(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val shouldRecover = !userStopped && Prefs(this).serviceEnabled
        tearDown()
        if (shouldRecover) {
            Log.w(TAG, "Unexpected stop — scheduling recovery")
            ServiceRecovery.scheduleRestart(applicationContext)
        }
        super.onDestroy()
    }

    private fun startSatellite() {
        val prefs = Prefs(this)
        prefs.serviceEnabled = true

        acquireLocks()
        registerNetworkCallback()

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
            releaseServerOnly()
            if (!userStopped) {
                ServiceRecovery.scheduleRestart(this, RECOVERY_DELAY_MS)
            }
            return
        }

        advertisedName = prefs.satelliteName
        advertisedPort = prefs.port
        advertiser = NsdAdvertiser(this).also { it.register(advertisedName!!, advertisedPort) }

        running = true
        prefs.pendingBootStart = false
        BootStartScheduler.cancel(this)
        ServiceRecovery.cancel(this)
        updateNotification()
        handler.removeCallbacks(healthCheck)
        handler.postDelayed(healthCheck, HEALTH_INTERVAL_MS)
    }

    private fun tearDown() {
        handler.removeCallbacks(healthCheck)
        unregisterNetworkCallback()
        advertiser?.unregister()
        advertiser = null
        releaseServerOnly()
        releaseLocks()
        running = false
        connectionCount = 0
        streaming = false
        processingActive = false
    }

    private fun releaseServerOnly() {
        server?.stop()
        server = null
    }

    private fun runHealthCheck() {
        if (userStopped || !Prefs(this).serviceEnabled) return

        ensureLocksHeld()

        val srv = server
        if (srv == null || !srv.isHealthy()) {
            Log.w(TAG, "Health check: restarting Wyoming server")
            onLog("Restarting server…")
            releaseServerOnly()
            try {
                val prefs = Prefs(this)
                val config = WyomingServer.Config(
                    port = prefs.port,
                    satelliteName = prefs.satelliteName,
                    startStage = prefs.startStage,
                    playTts = prefs.playTts,
                    sampleRate = Prefs.SAMPLE_RATE,
                    audioSource = prefs.audioSource,
                )
                val replacement = WyomingServer(config, this)
                replacement.start()
                server = replacement
                running = true
            } catch (e: Exception) {
                Log.e(TAG, "Health restart failed", e)
                running = false
                if (!userStopped) {
                    ServiceRecovery.scheduleRestart(this, RECOVERY_DELAY_MS)
                }
                return
            }
        } else {
            srv.ensureAudioCapture()
        }

        refreshAdvertisement()
        updateNotification()
    }

    private fun refreshAdvertisement() {
        val name = advertisedName ?: Prefs(this).satelliteName
        val port = advertisedPort
        advertiser?.refresh(name, port)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (!userStopped && running) {
                        Log.i(TAG, "Network available — refreshing")
                        onLog("Network back — refreshing")
                        refreshAdvertisement()
                        server?.ensureAudioCapture()
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (!userStopped && running) {
                        Log.w(TAG, "Network lost")
                        onLog("Network lost — waiting to reconnect")
                    }
                }
            }
        }
        networkCallback = callback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed: ${e.message}")
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        networkCallback = null
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

    private fun ensureLocksHeld() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock = wifi.createMulticastLock("$TAG:mcast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Re-acquiring locks failed: ${e.message}")
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
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UI, true),
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
        private const val HEALTH_INTERVAL_MS = 30_000L
        private const val RECOVERY_DELAY_MS = 10_000L

        const val ACTION_START = "dev.wyomingdroid.action.START"
        const val ACTION_STOP = "dev.wyomingdroid.action.STOP"

        @Volatile var running = false
        @Volatile var connectionCount = 0
        @Volatile var streaming = false
        @Volatile var processingActive = false
        @Volatile var statusLine = ""

        fun start(context: Context) {
            val intent = Intent(context, SatelliteService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService failed", e)
                ServiceRecovery.scheduleRestart(context.applicationContext)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SatelliteService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
