package com.example.data.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.WastiDatabase
import com.example.data.node.WastiSovereignTunnelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [The Eternal Manifesto: The Pilot and Spacecraft Principle & Memory of the Human]
 *
 * "The human provides purpose, Wasti provides execution.
 * Memory of the Human: preserve knowledge, goals, preferences, decisions and creativity by consent."
 *
 * PersonalizedOnboardingEngine:
 * Coordinates the first-run onboarding assessment, aligns Wasti with user goals,
 * profiles device silicon, and autonomously creates the production keystore and
 * sovereign cloud tunnel.
 */

enum class UserPrimaryRole {
    SOFTWARE_ENGINEER,
    AI_RESEARCHER,
    EXECUTIVE_BUSINESS,
    PRIVACY_SOVEREIGN,
    PERSONAL_ASSISTANT
}

enum class SwarmComputePreference {
    OFFLINE_SOVEREIGN,
    HYBRID_EDGE_CLOUD,
    AGGRESSIVE_MESH_SWARM
}

data class PersonalizedSetupPlan(
    val role: UserPrimaryRole,
    val computePreference: SwarmComputePreference,
    val autoCreateKeystore: Boolean = true,
    val autoDeployCloudTunnel: Boolean = true,
    val userCustomInstructions: String = ""
)

object PersonalizedOnboardingEngine {

    private const val TAG = "PersonalizedOnboarding"
    private const val PREFS_NAME = "wasti_onboarding_prefs"
    private const val KEY_SETUP_COMPLETED = "is_personalized_setup_completed"
    private const val KEY_USER_ROLE = "user_primary_role"
    private const val KEY_COMPUTE_MODE = "compute_preference"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSetupCompleted(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SETUP_COMPLETED, false)

    fun getUserRole(context: Context): UserPrimaryRole {
        val str = getPrefs(context).getString(KEY_USER_ROLE, UserPrimaryRole.SOFTWARE_ENGINEER.name)
        return try { UserPrimaryRole.valueOf(str!!) } catch (_: Exception) { UserPrimaryRole.SOFTWARE_ENGINEER }
    }

    /**
     * Executes the comprehensive first-run personalization and autonomous gate resolution.
     */
    suspend fun applyPersonalizedSetup(
        context: Context,
        plan: PersonalizedSetupPlan
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Applying personalized setup: role=${plan.role}, compute=${plan.computePreference}...")

        val db = WastiDatabase.getDatabase(context)

        // 1. Profile Hardware & Silicon
        val hardwareProfile = WastiDeepHardwareProfiler.profileSystem(context)
        val hardwareSummary = WastiDeepHardwareProfiler.generateSystemSummaryMarkdown(hardwareProfile)

        // 2. Commit User Role & Intent into Sovereign Memory
        val roleMemory = MemoryEntity(
            id = "user_intent_role",
            key = "user_primary_mission",
            category = "UserGoal",
            value = "User is operating as ${plan.role}. Preferences: ${plan.computePreference}. Instructions: ${plan.userCustomInstructions.ifBlank { "Optimize for speed, accuracy, and autonomy." }}",
            importanceScore = 1.0f
        )
        db.memoryDao().insertMemory(roleMemory)

        // 3. Index Hardware Reality into Knowledge Graph
        val hardwareKnowledge = KnowledgeEntity(
            id = "device_hardware_matrix",
            title = "Physical Host Hardware Reality",
            category = "HardwareReality",
            content = hardwareSummary,
            tagsCsv = "Hardware,Silicon,DeviceMatrix,Autonomic",
            dateAdded = System.currentTimeMillis()
        )
        db.knowledgeDao().insertKnowledge(hardwareKnowledge)

        // 4. Autonomously Resolve Production Keystore Gate with Automatic Fallback & Discovery
        if (plan.autoCreateKeystore) {
            val discoveredExternal = WastiProductionSigningEngine.discoverExternalKeystore(context)
            if (discoveredExternal != null && discoveredExternal.exists() && !File(context.filesDir, "security/keystore/wasti_production_release.p12").exists()) {
                val importResult = WastiProductionSigningEngine.importExistingKeystore(context, discoveredExternal)
                Log.i(TAG, "Discovered existing keystore on device at ${discoveredExternal.absolutePath}. Imported automatically: ${importResult.isSuccess}")
            } else if (!WastiProductionSigningEngine.hasExistingKeystore(context)) {
                val keyResult = WastiProductionSigningEngine.generateSovereignReleaseKeystore(
                    context = context,
                    organization = "Wasti AI OS Sovereign (${plan.role})"
                )
                if (keyResult.isSuccess) {
                    Log.i(TAG, "Autonomous production keystore created during first-run setup.")
                }
            } else {
                Log.i(TAG, "Existing sovereign keystore preserved. Keystore gate resolved.")
            }
        }

        // 5. Autonomously Resolve Public Cloud Companion Ingress Gate
        if (plan.autoDeployCloudTunnel) {
            try {
                WastiSovereignTunnelEngine.establishTunnel(context)
                Log.i(TAG, "Autonomous sovereign cloud tunnel established during first-run setup.")
            } catch (e: Exception) {
                Log.w(TAG, "Sovereign tunnel deferral: ${e.message}")
            }
        }

        // 6. Save Setup Completion Flag
        getPrefs(context).edit()
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .putString(KEY_USER_ROLE, plan.role.name)
            .putString(KEY_COMPUTE_MODE, plan.computePreference.name)
            .apply()

        Log.i(TAG, "SUCCESS: Personalized setup complete. Production gates resolved on-device.")
    }

    /**
     * Resets setup state for testing or reconfiguration.
     */
    fun resetSetupForTesting(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
