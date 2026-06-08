package dev.wyomingdroid

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/** Schedules a delayed satellite start after reboot (Wi‑Fi / kiosk need time to settle). */
object BootStartScheduler {

    private const val TAG = "BootStartScheduler"
    private const val REQUEST_CODE = 7001
    /** Give Wi‑Fi and Fully Kiosk time to finish launching before we bind the server. */
    private const val DELAY_MS = 45_000L

    fun markPendingAndSchedule(context: Context) {
        Prefs(context).pendingBootStart = true
        Prefs(context).serviceEnabled = true
        Prefs(context).userStopped = false
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BootStartReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            pendingIntentFlags(),
        )
        val triggerAt = SystemClock.elapsedRealtime() + DELAY_MS
        alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
        Log.i(TAG, "Scheduled satellite start in ${DELAY_MS / 1000}s")
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BootStartReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            pendingIntentFlags(),
        )
        alarm.cancel(pending)
    }

    fun tryStartNow(context: Context, reason: String) {
        val prefs = Prefs(context)
        if (!prefs.startOnBoot || prefs.userStopped) return
        if (!prefs.pendingBootStart && reason == "alarm") return
        if (SatelliteService.running) {
            prefs.pendingBootStart = false
            cancel(context)
            return
        }
        prefs.serviceEnabled = true
        Log.i(TAG, "Starting satellite ($reason)")
        try {
            SatelliteService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Start failed ($reason)", e)
            ServiceRecovery.scheduleRestart(context)
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
