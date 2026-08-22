package com.example.data.node

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Stage 12: Network Service Discovery (mDNS/NSD) for Wasti AI OS Nodes.
 * Registers `_wasti-os._tcp` on the local Wi-Fi / hotspot subnet
 * allowing Web and Desktop companion clients to auto-discover Wasti without manual IP entry.
 */
class WastiNodeDiscoveryManager(
    private val context: Context
) {
    private val nsdManager: NsdManager? by lazy {
        try {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        } catch (e: Exception) {
            Log.w(TAG, "NsdManager unavailable: ${e.message}")
            null
        }
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    @Synchronized
    fun registerService(port: Int, serviceName: String = "WastiAI-OS-Node"): Boolean {
        if (isRegistered) return true
        val nsd = nsdManager ?: return false

        try {
            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = SERVICE_TYPE
                this.port = port
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    isRegistered = true
                    Log.i(TAG, "Wasti mDNS/NSD Service successfully registered: ${NsdServiceInfo.serviceName} on port $port")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isRegistered = false
                    Log.w(TAG, "Wasti mDNS/NSD Service registration failed with code: $errorCode")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    isRegistered = false
                    Log.i(TAG, "Wasti mDNS/NSD Service unregistered.")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Wasti mDNS/NSD Service unregistration failed: $errorCode")
                }
            }

            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service: ${e.message}", e)
            return false
        }
    }

    @Synchronized
    fun unregisterService() {
        if (!isRegistered) return
        val nsd = nsdManager ?: return
        val listener = registrationListener ?: return

        try {
            nsd.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering mDNS service: ${e.message}")
        } finally {
            isRegistered = false
            registrationListener = null
        }
    }

    companion object {
        private const val TAG = "WastiNodeDiscovery"
        const val SERVICE_TYPE = "_wasti-os._tcp."

        @Volatile
        private var instance: WastiNodeDiscoveryManager? = null

        fun getInstance(context: Context): WastiNodeDiscoveryManager {
            return instance ?: synchronized(this) {
                instance ?: WastiNodeDiscoveryManager(context.applicationContext ?: context).also { instance = it }
            }
        }
    }
}
