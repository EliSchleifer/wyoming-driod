package dev.wyomingdroid

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Advertises the satellite over mDNS/DNS-SD as `_wyoming._tcp` so Home
 * Assistant can auto-discover it. Best-effort: if it fails, the satellite can
 * always be added manually in HA by host and port.
 */
class NsdAdvertiser(context: Context) {

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, port: Int) {
        val manager = nsdManager ?: return
        unregister()

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_wyoming._tcp."
            this.port = port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered mDNS service ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        listener = l
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS register error: ${e.message}")
            listener = null
        }
    }

    fun unregister() {
        val manager = nsdManager ?: return
        listener?.let {
            try {
                manager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "mDNS unregister error: ${e.message}")
            }
        }
        listener = null
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
    }
}
