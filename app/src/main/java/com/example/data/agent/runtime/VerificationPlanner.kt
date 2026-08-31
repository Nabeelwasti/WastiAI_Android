package com.example.data.agent.runtime

import java.util.UUID

/**
 * Verification Planner for Wasti AI OS.
 * Pre-computes and specifies the exact evidence requirements needed to objectively prove
 * that an action succeeded without relying on self-reported success or optimistic assumptions.
 */

enum class VerificationCriterionType {
    FILE_EXISTS_AND_NONEMPTY,
    FILE_CONTENT_MATCHES,
    DATABASE_RECORD_CREATED,
    ACCESSIBILITY_NODE_PRESENT,
    SYSTEM_STATE_CHANGED,
    EXIT_CODE_ZERO,
    STRUCTURED_OUTPUT_VALID,
    NETWORK_STATUS_OK,
    MEMORY_STORED_VERIFIED
}

data class VerificationCriterion(
    val criterionId: String = "crit_${UUID.randomUUID().toString().take(8)}",
    val type: VerificationCriterionType,
    val description: String,
    val targetKey: String,
    val expectedValue: Any? = null,
    val isMandatory: Boolean = true
)

data class ActionVerificationPlan(
    val actionId: String,
    val capabilityId: String,
    val criteria: List<VerificationCriterion>,
    val timeoutMs: Long = 5000L
)

class VerificationPlanner {

    /**
     * Synthesizes the required verification criteria for a given execution request.
     */
    fun createVerificationPlan(
        actionId: String,
        capabilityId: String,
        actionName: String,
        parameters: Map<String, Any>
    ): ActionVerificationPlan {
        val criteria = mutableListOf<VerificationCriterion>()
        val capLower = capabilityId.lowercase()
        val actionLower = actionName.lowercase()

        when {
            capLower.contains("file") || actionLower in listOf("write_file", "append_file", "create_file", "modify_file") -> {
                val path = parameters["path"]?.toString() ?: parameters["filePath"]?.toString() ?: ""
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.FILE_EXISTS_AND_NONEMPTY,
                        description = "Target file '$path' must exist and have size > 0 bytes",
                        targetKey = "filePath",
                        expectedValue = path
                    )
                )
            }

            capLower.contains("device") || capLower.contains("accessibility") || actionLower in listOf("simulate_tap", "type_text", "click_element") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.ACCESSIBILITY_NODE_PRESENT,
                        description = "Accessibility Service must be active and observe target UI node response",
                        targetKey = "accessibility_state",
                        expectedValue = "ACTIVE"
                    )
                )
            }

            capLower.contains("wasm") || (capLower.contains("terminal") && parameters["language"]?.toString()?.lowercase() == "wasm") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.STRUCTURED_OUTPUT_VALID,
                        description = "WASM sandboxed engine must return non-null result with fuel > 0",
                        targetKey = "fuelConsumed",
                        expectedValue = ">0"
                    )
                )
            }

            capLower.contains("build") || capLower.contains("test") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.EXIT_CODE_ZERO,
                        description = "Build and test runner must complete with zero failure count",
                        targetKey = "failedTests",
                        expectedValue = 0
                    )
                )
            }

            capLower.contains("memory") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.MEMORY_STORED_VERIFIED,
                        description = "Memory entity must be searchable and retrievable via hybrid query",
                        targetKey = "memoryQuery",
                        expectedValue = parameters["key"] ?: parameters["query"]
                    )
                )
            }

            capLower.contains("b2b") || capLower.contains("lead") || capLower.contains("research") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.STRUCTURED_OUTPUT_VALID,
                        description = "Research or lead scan must return verified result entities or verifiable error state",
                        targetKey = "output",
                        expectedValue = null
                    )
                )
            }

            capLower.contains("draft") -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.DATABASE_RECORD_CREATED,
                        description = "Draft record must be saved in persistence engine",
                        targetKey = "draft_status",
                        expectedValue = "SAVED"
                    )
                )
            }

            capLower.startsWith("wre_tool_") || capLower.contains("dynamic") -> {
                if (parameters.containsKey("output_file") || parameters.containsKey("path")) {
                    val path = (parameters["output_file"] ?: parameters["path"])?.toString() ?: ""
                    criteria.add(
                        VerificationCriterion(
                            type = VerificationCriterionType.FILE_EXISTS_AND_NONEMPTY,
                            description = "Dynamic tool output file '$path' must exist and have size > 0 bytes",
                            targetKey = "filePath",
                            expectedValue = path
                        )
                    )
                } else {
                    criteria.add(
                        VerificationCriterion(
                            type = VerificationCriterionType.STRUCTURED_OUTPUT_VALID,
                            description = "Dynamic WRE tool must produce non-empty stdout evidence without unhandled process exception",
                            targetKey = "stdout",
                            expectedValue = null
                        )
                    )
                    criteria.add(
                        VerificationCriterion(
                            type = VerificationCriterionType.EXIT_CODE_ZERO,
                            description = "Dynamic WRE tool process exit code must be zero",
                            targetKey = "exitCode",
                            expectedValue = 0
                        )
                    )
                }
            }

            else -> {
                criteria.add(
                    VerificationCriterion(
                        type = VerificationCriterionType.STRUCTURED_OUTPUT_VALID,
                        description = "Execution result must provide truthful non-empty evidence payload",
                        targetKey = "output",
                        expectedValue = null
                    )
                )
            }
        }

        return ActionVerificationPlan(
            actionId = actionId,
            capabilityId = capabilityId,
            criteria = criteria
        )
    }
}
