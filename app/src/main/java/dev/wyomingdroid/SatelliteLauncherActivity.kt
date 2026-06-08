package dev.wyomingdroid

import android.app.Activity
import android.os.Bundle

/**
 * Invisible entry point for Fully Kiosk / adb: starts the foreground service and
 * exits immediately so another app can stay on screen.
 *
 * adb shell am start -n dev.wyomingdroid/.SatelliteLauncherActivity
 */
class SatelliteLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SatelliteAutoStart.startIfNeeded(this)
        finish()
    }
}
