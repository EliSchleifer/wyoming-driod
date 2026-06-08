package dev.wyomingdroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires when [ServiceRecovery] schedules a restart after an unexpected stop. */
class ServiceRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Recovery alarm fired")
        ServiceRecovery.tryRestartNow(context.applicationContext, "alarm")
    }

    companion object {
        private const val TAG = "ServiceRecoveryReceiver"
    }
}
