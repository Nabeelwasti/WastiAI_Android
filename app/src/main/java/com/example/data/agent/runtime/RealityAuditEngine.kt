package com.example.data.agent.runtime

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

    fun generateSystemConnectivityReport(): List<ConnectivityStatus> {
        val realities = realityRegistry.getSystemRealityReport()
        val report = mutableListOf<ConnectivityStatus>()

        for (r in realities) {
            val authPresent = r.authenticationStatus == CapabilityAuthStatus.AUTHENTICATED || r.authenticationStatus == CapabilityAuthStatus.NOT_REQUIRED
            val liveSuccess = r.liveConnectionStatus == LiveConnectionStatus.VERIFIED

            val status = ConnectivityStatus(
                capabilityId = r.capabilityId,
                codePresent = r.implementationStatus == ImplementationStatus.READY || r.implementationStatus == ImplementationStatus.CONTRACT_ONLY,
                configPresent = r.provider != "None",
                authPresent = authPresent,
                networkAvailable = true,
                liveRequestSuccessful = liveSuccess,
                liveActionSuccessful = liveSuccess,
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
