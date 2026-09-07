package com.example.data.core

/**
 * P0-31: Canonical Test Classification Taxonomy.
 *
 * Explicitly distinguishes test execution environments to prevent simulation,
 * mock, or host-runner tests from masquerading as proof of real-world device
 * or neural capabilities.
 */
enum class TestTier {
    UNIT,
    ROBOLECTRIC,
    HOST_SIMULATION,
    INTEGRATION,
    DEVICE,
    EMULATOR,
    E2E,
    EXTERNAL,
    SYNTHETIC,
    MOCK,
    REAL_PROVIDER;

    val provesRealWorldCapability: Boolean
        get() = this in setOf(DEVICE, EMULATOR, REAL_PROVIDER, E2E)

    val isMockOrSimulation: Boolean
        get() = this in setOf(MOCK, SYNTHETIC, HOST_SIMULATION, ROBOLECTRIC)
}

/**
 * Annotation to explicitly categorize tests and test classes across the repository.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestCategory(
    val tier: TestTier,
    val description: String = "",
    val requiresHardwareAcceleration: Boolean = false,
    val requiresLiveNetwork: Boolean = false
)

object TestClassificationRegistry {
    fun canProveProductionCapability(tier: TestTier): Boolean {
        return tier.provesRealWorldCapability
    }

    fun classifyTest(className: String, methodName: String? = null): TestTier {
        try {
            val simpleOrFullName = if (className.contains(".")) className else "com.example.data.core.$className"
            val clazz = try {
                Class.forName(simpleOrFullName)
            } catch (e: Exception) {
                try {
                    Class.forName(className)
                } catch (e2: Exception) {
                    null
                }
            }
            if (clazz != null) {
                val category = clazz.getAnnotation(TestCategory::class.java)
                if (category != null) {
                    return category.tier
                }
            }
        } catch (_: Throwable) {}

        return when {
            className.contains("RealDevice", ignoreCase = true) -> TestTier.DEVICE
            className.contains("Emulator", ignoreCase = true) -> TestTier.EMULATOR
            className.contains("E2E", ignoreCase = true) -> TestTier.E2E
            className.contains("Integration", ignoreCase = true) -> TestTier.INTEGRATION
            className.contains("Mock", ignoreCase = true) -> TestTier.MOCK
            className.contains("Simulation", ignoreCase = true) -> TestTier.HOST_SIMULATION
            className.contains("Robolectric", ignoreCase = true) ||
            className.contains("EternalManifesto", ignoreCase = true) ||
            className.contains("TruthAudit", ignoreCase = true) -> TestTier.ROBOLECTRIC
            else -> TestTier.UNIT
        }
    }
}
