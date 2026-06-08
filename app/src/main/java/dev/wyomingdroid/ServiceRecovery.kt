package dev.wyomingdroid

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Restarts the satellite after crashes, kills, or failed start attempts. */
object ServiceRecovery {

    private const val TAG = "ServiceRecovery"
    private const val REQUEST_CODE = 7002
    private const val DEFAULT_DELAY_MS = 5_000L

    fun scheduleRestart(context: Context, delayMs: Long = DEFAULT_DELAY_MS) {
        val prefs = Prefs(context.applicationContext)
        if (!prefs.serviceEnabled || prefs.userStopped) {
            Log.i(TAG, "Not scheduling restart — stopped by user")
            return
        }
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceRecoveryReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            pendingIntentFlags(),
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        Log.i(TAG, "Scheduled service restart in ${delayMs / 1000}s")
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ServiceRecoveryReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            pendingIntentFlags(),
        )
        alarm.cancel(pending)
    }

    fun tryRestartNow(context: Context, reason: String) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.serviceEnabled || prefs.userStopped) return
        if (SatelliteService.running) {
            cancel(app)
            return
        }
        Log.i(TAG, "Restarting satellite ($reason)")
        try {
            SatelliteService.start(app)
        } catch (e: Exception) {
            Log.e(TAG, "Restart failed ($reason)", e)
            scheduleRestart(app, DEFAULT_DELAY_MS * 2)
        }
    }

    private fun pendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }
}
