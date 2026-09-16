package com.example.data.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.WastiDatabase
import com.example.data.node.WastiSovereignTunnelEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val autoCreateKeystore: Boolean = false,
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

    /** Executes first-run personalization without manufacturing production readiness. */
    suspend fun applyPersonalizedSetup(
        context: Context,
        plan: PersonalizedSetupPlan
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Applying personalized setup: role=${plan.role}, compute=${plan.computePreference}...")
        val db = WastiDatabase.getDatabase(context)

        val hardwareProfile = WastiDeepHardwareProfiler.profileSystem(context)
        val hardwareSummary = WastiDeepHardwareProfiler.generateSystemSummaryMarkdown(hardwareProfile)

        val roleMemory = MemoryEntity(
            id = "user_intent_role",
            key = "user_primary_mission",
            category = "UserGoal",
            value = "User is operating as ${plan.role}. Preferences: ${plan.computePreference}. Instructions: ${plan.userCustomInstructions.ifBlank { "Optimize for speed, accuracy, and autonomy." }}",
            importanceScore = 1.0f
        )
        db.memoryDao().insertMemory(roleMemory)

        val hardwareKnowledge = KnowledgeEntity(
            id = "device_hardware_matrix",
            title = "Physical Host Hardware Reality",
            category = "HardwareReality",
            content = hardwareSummary,
            tagsCsv = "Hardware,Silicon,DeviceMatrix,Autonomic",
            dateAdded = System.currentTimeMillis()
        )
        db.knowledgeDao().insertKnowledge(hardwareKnowledge)

        if (plan.autoCreateKeystore) {
            Log.w(TAG, "Automatic production-key creation is disabled: production authority must come from the configured official signing key.")
        }

        var tunnelEstablished = false
        if (plan.autoDeployCloudTunnel) {
            try {
                WastiSovereignTunnelEngine.establishTunnel(context)
                tunnelEstablished = true
                Log.i(TAG, "Sovereign cloud tunnel setup completed without claiming production readiness.")
            } catch (e: Exception) {
                Log.w(TAG, "Sovereign tunnel deferred: ${e.message}")
            }
        }

        getPrefs(context).edit()
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .putString(KEY_USER_ROLE, plan.role.name)
            .putString(KEY_COMPUTE_MODE, plan.computePreference.name)
            .apply()

        Log.i(TAG, "Personalized setup complete. External tunnel established=$tunnelEstablished; production readiness remains evidence-gated.")
    }

    fun resetSetupForTesting(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
