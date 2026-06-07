package dev.wyomingdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the satellite after a reboot when the user enabled "start on boot".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val prefs = Prefs(context)
        if (prefs.startOnBoot) {
            SatelliteService.start(context)
        }
    }
}
