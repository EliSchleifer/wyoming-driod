package dev.wyomingdroid

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Shared auto-start logic for MainActivity, boot receivers, and the headless launcher. */
object SatelliteAutoStart {

    fun startIfNeeded(context: Context): Boolean {
        val prefs = Prefs(context.applicationContext)
        if (!prefs.startOnBoot || prefs.userStopped || SatelliteService.running) {
            return SatelliteService.running
        }
        if (!hasMicPermission(context)) return false
        prefs.userStopped = false
        prefs.serviceEnabled = true
        SatelliteService.start(context.applicationContext)
        return true
    }

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
