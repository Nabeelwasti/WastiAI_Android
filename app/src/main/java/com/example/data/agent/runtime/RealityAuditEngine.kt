package com.example.data.agent.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.credential.CredentialRegistry

data class ConnectivityStatus(
    val capabilityId: String,
    val codePresent: Boolean,
    val configPresent: Boolean,
    val authPresent: Boolean,
    val networkAvailable: Boolean,
    val liveRequestSuccessful: Boolean,
    val liveActionSuccessful: Boolean,
    val summaryRealityState: CapabilityRealityState
)

class RealityAuditEngine(
    private val realityRegistry: CapabilityRealityRegistry,
    private val credentialBroker: WastiCredentialBroker
) {

    private fun isSystemNetworkConnected(): Boolean {
        val ctx = CredentialRegistry.appContext ?: com.example.WastiApplication.instance ?: return false // Fail-closed when context unavailable
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun generateSystemConnectivityReport(): List<ConnectivityStatus> {
        val realities = realityRegistry.getSystemRealityReport()
        val report = mutableListOf<ConnectivityStatus>()
        val systemNetworkOnline = isSystemNetworkConnected()

        for (r in realities) {
            val isNetworkCapability = r.capabilityId in listOf("search_web", "gemini_pro", "cloud_deployment", "github_sync", "api_client", "websocket_mesh", "B2B_XRAY", "LEAD_RADAR", "WEB_RESEARCH_SCRAPER")
            val networkAvailable = if (isNetworkCapability) systemNetworkOnline else true
            val authPresent = if (r.authenticationStatus == CapabilityAuthStatus.NOT_REQUIRED) {
                true
            } else {
                credentialBroker.hasValidCredentials(r.capabilityId) || r.authenticationStatus == CapabilityAuthStatus.AUTHENTICATED
            }
            val liveRequestOk = r.liveConnectionStatus == LiveConnectionStatus.VERIFIED
            val liveActionOk = liveRequestOk && r.realityState == CapabilityRealityState.LIVE_CONNECTED

            val status = ConnectivityStatus(
                capabilityId = r.capabilityId,
                codePresent = r.implementationStatus == ImplementationStatus.READY,
                configPresent = r.provider != "None" && r.provider.isNotBlank(),
                authPresent = authPresent,
                networkAvailable = networkAvailable,
                liveRequestSuccessful = liveRequestOk,
                liveActionSuccessful = liveActionOk,
                summaryRealityState = r.realityState
            )
            report.add(status)
        }

        return report
    }

    fun generateRealityAuditReport(): List<CapabilityReality> {
        return realityRegistry.getSystemRealityReport()
    }
}
