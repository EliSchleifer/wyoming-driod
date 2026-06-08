package dev.wyomingdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the satellite after a reboot when the user enabled "start on boot".
 * Starts are delayed so Wi‑Fi and kiosk launchers can finish first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.startOnBoot) {
            Log.i(TAG, "Start on boot disabled — skipping")
            return
        }
        prefs.userStopped = false
        Log.i(TAG, "Boot completed — scheduling delayed satellite start")
        BootStartScheduler.markPendingAndSchedule(app)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
