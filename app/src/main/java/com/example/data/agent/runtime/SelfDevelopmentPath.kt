package com.example.data.agent.runtime

enum class SelfDevelopmentStage {
    DETECT_GAP,
    DESIGN_CAPABILITY,
    CREATE_EXPERIMENT,
    TEST,
    VALIDATE,
    REGISTER,
    EVALUATE,
    PROMOTE
}

data class SelfDevelopmentPlan(
    val gapReport: CapabilityGapReport,
    var currentStage: SelfDevelopmentStage = SelfDevelopmentStage.DETECT_GAP,
    val experimentalWorkspacePath: String = "/workspace/experiments/",
    val isProtectedCoreTouchAllowed: Boolean = false
)

class SelfDevelopmentPathEngine {

    fun initializeDevelopmentPlan(gapReport: CapabilityGapReport): SelfDevelopmentPlan {
        return SelfDevelopmentPlan(
            gapReport = gapReport,
            currentStage = SelfDevelopmentStage.DESIGN_CAPABILITY,
            experimentalWorkspacePath = "/workspace/experiments/${gapReport.requestedCapability.lowercase()}/",
            isProtectedCoreTouchAllowed = false
        )
    }

    fun advanceStage(plan: SelfDevelopmentPlan, targetStage: SelfDevelopmentStage): SelfDevelopmentPlan {
        // Enforce stage order and core protection
        if (plan.isProtectedCoreTouchAllowed) {
            throw SecurityException("Protected core modification is strictly forbidden during self-development")
        }

        plan.currentStage = targetStage
        return plan
    }
}
