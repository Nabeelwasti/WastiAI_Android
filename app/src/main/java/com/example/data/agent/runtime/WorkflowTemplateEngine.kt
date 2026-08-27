package com.example.data.agent.runtime

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Workflow Template Engine for Wasti AI OS.
 * Turns successfully verified multi-step capability executions into reusable, parameterized templates.
 */

data class WorkflowTemplate(
    val templateId: String = "tmpl_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val description: String,
    val requiredParameters: List<String>,
    val steps: List<CapabilityWorkflowStep>,
    val author: String = "WastiCore",
    val usageCount: Int = 0,
    val successRate: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis()
)

class WorkflowTemplateEngine {

    private val templates = ConcurrentHashMap<String, WorkflowTemplate>()

    init {
        // Register built-in canonical workflow templates
        registerTemplate(
            WorkflowTemplate(
                templateId = "tmpl_dev_patch",
                name = "Autonomous Code Diagnostic & Patch",
                description = "Scans target project, analyzes compilation diagnostics, proposes fix, and applies verified patch.",
                requiredParameters = listOf("projectId", "filePath"),
                steps = listOf(
                    CapabilityWorkflowStep(
                        stepIndex = 0,
                        capabilityId = "files",
                        description = "Inspect file content",
                        inputParameters = mapOf("action" to "read_file", "path" to "\${filePath}")
                    ),
                    CapabilityWorkflowStep(
                        stepIndex = 1,
                        capabilityId = "debug_project",
                        description = "Parse diagnostics",
                        inputParameters = mapOf("projectId" to "\${projectId}")
                    ),
                    CapabilityWorkflowStep(
                        stepIndex = 2,
                        capabilityId = "build_project",
                        description = "Verify build",
                        inputParameters = mapOf("projectId" to "\${projectId}")
                    )
                )
            )
        )

        registerTemplate(
            WorkflowTemplate(
                templateId = "tmpl_screen_interact",
                name = "Screen Element Inspection & Tap",
                description = "Captures active Android UI accessibility nodes and performs targeted tap on desired element.",
                requiredParameters = listOf("targetElement"),
                steps = listOf(
                    CapabilityWorkflowStep(
                        stepIndex = 0,
                        capabilityId = "device_control",
                        description = "Scrape active screen UI",
                        inputParameters = mapOf("action" to "read_screen")
                    ),
                    CapabilityWorkflowStep(
                        stepIndex = 1,
                        capabilityId = "device_control",
                        description = "Tap on target UI element",
                        inputParameters = mapOf("action" to "simulate_tap", "targetElement" to "\${targetElement}")
                    )
                )
            )
        )
    }

    fun registerTemplate(template: WorkflowTemplate) {
        templates[template.templateId] = template
    }

    fun getTemplate(templateId: String): WorkflowTemplate? = templates[templateId]

    fun listTemplates(): List<WorkflowTemplate> = templates.values.toList()

    /**
     * Instantiates a concrete ComposedCapabilityWorkflow from a template with parameter substitution.
     */
    fun instantiateWorkflow(
        templateId: String,
        parameterValues: Map<String, String>
    ): ComposedCapabilityWorkflow? {
        val template = templates[templateId] ?: return null

        val concreteSteps = template.steps.map { step ->
            val substitutedParams = step.inputParameters.mapValues { (_, value) ->
                var sVal = value
                parameterValues.forEach { (k, v) ->
                    sVal = sVal.replace("\${$k}", v)
                }
                sVal
            }
            step.copy(
                stepId = UUID.randomUUID().toString(),
                inputParameters = substitutedParams,
                status = StepExecutionStatus.PENDING
            )
        }

        return ComposedCapabilityWorkflow(
            title = template.name,
            userGoal = "Executed template: ${template.name}",
            steps = concreteSteps
        )
    }
}
