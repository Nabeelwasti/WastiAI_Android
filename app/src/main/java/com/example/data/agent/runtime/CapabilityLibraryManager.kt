package com.example.data.agent.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Capability Library and Marketplace Foundation for Wasti AI OS.
 * Manages the catalogue of discovered, self-developed, composed, and marketplace capabilities.
 */

enum class CapabilityPromotionTier {
    SANDBOX_EXPERIMENTAL,
    COMMUNITY_VERIFIED,
    CORE_PROMOTED,
    SYSTEM_RESTRICTED
}

data class CapabilityPackage(
    val packageId: String = "pkg_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val version: String,
    val description: String,
    val tier: CapabilityPromotionTier = CapabilityPromotionTier.SANDBOX_EXPERIMENTAL,
    val author: String,
    val capabilitiesProvided: List<String>,
    val verifiedRunsCount: Int = 0,
    val regressionScore: Float = 1.0f,
    val isEnabled: Boolean = true,
    val registeredAt: Long = System.currentTimeMillis()
)

class CapabilityLibraryManager(
    private val realityRegistry: CapabilityRealityRegistry? = null
) {
    private val registry: CapabilityRealityRegistry
        get() = realityRegistry ?: UnifiedExecutionFabric.instance.realityRegistry

    private val packages = ConcurrentHashMap<String, CapabilityPackage>()

    init {
        // Register core foundational packages
        registerPackage(
            CapabilityPackage(
                packageId = "pkg_core_runtime",
                name = "Wasti Core Runtime Fabric",
                version = "1.0.0",
                description = "Core execution fabric, workspace files, WASM sandbox, and Android device bridge.",
                tier = CapabilityPromotionTier.CORE_PROMOTED,
                author = "Wasti AI OS",
                capabilitiesProvided = listOf("device_control", "files", "wasm_sandbox", "memory_search", "system_info")
            )
        )

        registerPackage(
            CapabilityPackage(
                packageId = "pkg_dev_engine",
                name = "Wasti Autonomous Dev Engine",
                version = "1.0.0",
                description = "Project management, diagnostic parsing, build verification, and test execution.",
                tier = CapabilityPromotionTier.CORE_PROMOTED,
                author = "Wasti AI OS",
                capabilitiesProvided = listOf("build_project", "test_project", "debug_project", "project_dev_manager")
            )
        )
    }

    fun registerPackage(pkg: CapabilityPackage) {
        packages[pkg.packageId] = pkg
    }

    fun getPackage(packageId: String): CapabilityPackage? = packages[packageId]

    fun listPackages(): List<CapabilityPackage> = packages.values.toList()

    fun promotePackage(packageId: String, newTier: CapabilityPromotionTier): Boolean {
        val pkg = packages[packageId] ?: return false
        packages[packageId] = pkg.copy(tier = newTier)
        return true
    }

    fun disablePackage(packageId: String): Boolean {
        val pkg = packages[packageId] ?: return false
        packages[packageId] = pkg.copy(isEnabled = false)
        return true
    }
}
