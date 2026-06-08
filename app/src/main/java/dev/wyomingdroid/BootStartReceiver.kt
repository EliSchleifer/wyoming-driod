package dev.wyomingdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires after [BootStartScheduler]'s post-boot delay. */
class BootStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Boot start alarm fired")
        BootStartScheduler.tryStartNow(context.applicationContext, "alarm")
    }

    companion object {
        private const val TAG = "BootStartReceiver"
    }
}
