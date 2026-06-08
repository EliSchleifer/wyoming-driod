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

    private val appContext = context.applicationContext

    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** App versionName, used as a TXT record. Falls back if unavailable. */
    private val appVersion: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    private var listener: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null
    private var registeredPort: Int = 0

    fun register(serviceName: String, port: Int) {
        registeredName = serviceName
        registeredPort = port
        registerInternal(serviceName, port)
    }

    /** Re-publish mDNS after Wi‑Fi drops or NSD registration expires. */
    fun refresh(serviceName: String, port: Int) {
        registeredName = serviceName
        registeredPort = port
        if (listener == null) {
            registerInternal(serviceName, port)
        }
    }

    private fun registerInternal(serviceName: String, port: Int) {
        val manager = nsdManager ?: return
        unregister()

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_wyoming._tcp."
            this.port = port
            // TXT records shown in Home Assistant's discovery card.
            setAttribute("version", appVersion)
            setAttribute("name", serviceName)
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
