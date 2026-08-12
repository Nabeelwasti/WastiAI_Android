package com.example.data.agent.runtime

class WastiAgentToolRouter(
    private val registry: AgentToolRegistry,
    private val securityPolicy: SecurityPolicy,
    private val permissionModel: PermissionModel,
    private val emergencyStop: EmergencyStopController,
    private val capabilityRegistry: CapabilityRegistry,
    private val auditLogger: AuditLogger
) : AgentToolRouter {

    override suspend fun resolveTool(
        task: AgentTask,
        requestedAction: String
    ): AgentTool? {
        if (emergencyStop.isEmergencyStopped) return null
        return registry.get(requestedAction)
    }

    suspend fun routeAndExecute(
        toolName: String,
        args: Map<String, Any?>,
        context: AgentTask
    ): ToolResult {
        val startTime = System.currentTimeMillis()

        // 1. Check emergency stop
        if (emergencyStop.isEmergencyStopped) {
            auditLogger.logSecurityViolation(
                taskId = context.taskId,
                violationType = "EMERGENCY_STOP_ACTIVE",
                details = "Blocked routing of $toolName due to active emergency stop"
            )
            return ToolResult(
                isSuccess = false,
                output = emptyMap(),
                error = "EMERGENCY_STOP: Operation blocked by emergency stop",
                isSecurityBlocked = true
            )
        }

        // 2. Validate task state
        if (context.cancellationState.isCancelled || context.status is AgenticState.Cancelled) {
            return ToolResult(
                isSuccess = false,
                output = emptyMap(),
                error = "TASK_CANCELLED: Task is cancelled",
                isCancelled = true
            )
        }

        // 3. Fetch tool from registry
        val tool = registry.get(toolName)
        if (tool == null) {
            auditLogger.logSecurityViolation(
                taskId = context.taskId,
                violationType = "TOOL_NOT_FOUND",
                details = "Tool '$toolName' is not registered"
            )
            return ToolResult(
                isSuccess = false,
                output = emptyMap(),
                error = "TOOL_NOT_FOUND: Tool '$toolName' is not registered",
                isSecurityBlocked = true
            )
        }

        // 4. Validate tool arguments
        val argumentValidationError = validateToolArguments(tool, args)
        if (argumentValidationError != null) {
            auditLogger.logSecurityViolation(
                taskId = context.taskId,
                violationType = "INVALID_ARGUMENTS",
                details = "Tool '$toolName' received invalid arguments: $argumentValidationError"
            )
            return ToolResult(
                isSuccess = false,
                output = emptyMap(),
                error = "INVALID_ARGUMENTS: $argumentValidationError",
                isSecurityBlocked = true
            )
        }

        // 5. Determine target metadata
        val targetPath = (args["path"] as? String) ?: (args["workingDirectory"] as? String)

        // 6. Determine capability availability
        val requiredCapability = mapToolToCapability(tool)
        val capabilityAvailable = capabilityRegistry.isCapabilityEnabled(requiredCapability)

        // 7. Determine risk level
        val riskLevel = WastiRiskModel.evaluateRisk(tool, args, targetPath)

        // 8. Construct AuthorizationRequest
        val authRequest = AuthorizationRequest(
            tool = tool,
            input = args,
            targetPath = targetPath,
            executionMode = context.executionMode,
            capabilityAvailable = capabilityAvailable,
            context = context
        )

        // 9. Pass request to SecurityPolicy
        val decision = securityPolicy.evaluateAuthorization(authRequest)

        // 10. Process Authorization Decision & Audit
        var permissionGranted: Boolean? = null
        when (decision) {
            AuthorizationDecision.DENIED -> {
                auditLogger.logToolInvocation(
                    taskId = context.taskId,
                    toolName = toolName,
                    sanitizedInput = args,
                    decision = decision,
                    permissionGranted = false,
                    result = null,
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
                auditLogger.logSecurityViolation(
                    taskId = context.taskId,
                    violationType = "SECURITY_POLICY_DENIED",
                    details = "Security policy denied action for tool '$toolName' with risk $riskLevel"
                )
                return ToolResult(
                    isSuccess = false,
                    output = emptyMap(),
                    error = "SECURITY_BLOCKED: Action blocked by security policy",
                    isSecurityBlocked = true
                )
            }
            AuthorizationDecision.REQUIRES_USER_APPROVAL -> {
                val actionSummary = "Tool '${tool.name}' requests action on target '$targetPath'"
                val granted = permissionModel.requestUserApproval(actionSummary, tool.permissionLevel)
                permissionGranted = granted
                if (!granted) {
                    auditLogger.logToolInvocation(
                        taskId = context.taskId,
                        toolName = toolName,
                        sanitizedInput = args,
                        decision = decision,
                        permissionGranted = false,
                        result = null,
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                    return ToolResult(
                        isSuccess = false,
                        output = emptyMap(),
                        error = "PERMISSION_DENIED: Action rejected by user",
                        isCancelled = true
                    )
                }
            }
            AuthorizationDecision.REQUIRES_BIOMETRIC_APPROVAL -> {
                val promptReason = "Biometric approval required for privileged tool '${tool.name}'"
                val granted = permissionModel.requestBiometricApproval(promptReason)
                permissionGranted = granted
                if (!granted) {
                    auditLogger.logToolInvocation(
                        taskId = context.taskId,
                        toolName = toolName,
                        sanitizedInput = args,
                        decision = decision,
                        permissionGranted = false,
                        result = null,
                        executionTimeMs = System.currentTimeMillis() - startTime
                    )
                    return ToolResult(
                        isSuccess = false,
                        output = emptyMap(),
                        error = "PERMISSION_DENIED: Biometric authorization failed or rejected",
                        isCancelled = true
                    )
                }
            }
            AuthorizationDecision.ALLOWED -> {
                permissionGranted = true
            }
        }

        // 15. CHECK EMERGENCY STOP AGAIN IMMEDIATELY BEFORE EXECUTION
        if (emergencyStop.isEmergencyStopped) {
            auditLogger.logSecurityViolation(
                taskId = context.taskId,
                violationType = "EMERGENCY_STOP_PRE_EXECUTION",
                details = "Emergency stop was activated prior to executing '$toolName'"
            )
            return ToolResult(
                isSuccess = false,
                output = emptyMap(),
                error = "EMERGENCY_STOP: Operation blocked prior to tool execution",
                isSecurityBlocked = true
            )
        }

        // 16. Invoke tool execution handler
        val resultOutput = try {
            tool.execute(args)
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "Tool execution exception"))
        }

        val executionDuration = System.currentTimeMillis() - startTime
        val isSuccess = resultOutput["success"] as? Boolean ?: false

        // 18. Audit final result
        auditLogger.logToolInvocation(
            taskId = context.taskId,
            toolName = toolName,
            sanitizedInput = args,
            decision = decision,
            permissionGranted = permissionGranted,
            result = resultOutput,
            executionTimeMs = executionDuration
        )

        return ToolResult(
            isSuccess = isSuccess,
            output = resultOutput,
            error = resultOutput["error"] as? String,
            executionTimeMs = executionDuration
        )
    }

    private fun validateToolArguments(tool: AgentTool, args: Map<String, Any?>): String? {
        return when (tool.name) {
            "read_file", "file_exists", "list_files" -> {
                if ((args["path"] as? String).isNullOrBlank()) "Missing required argument 'path'" else null
            }
            "write_file" -> {
                if ((args["path"] as? String).isNullOrBlank()) "Missing required argument 'path'"
                else if (args["content"] == null) "Missing required argument 'content'"
                else null
            }
            "create_directory" -> {
                if ((args["path"] as? String).isNullOrBlank()) "Missing required argument 'path'" else null
            }
            "patch_file" -> {
                if ((args["path"] as? String).isNullOrBlank()) "Missing required argument 'path'"
                else if (args["targetContent"] == null) "Missing required argument 'targetContent'"
                else if (args["replacementContent"] == null) "Missing required argument 'replacementContent'"
                else null
            }
            "execute_code" -> {
                if ((args["executable"] as? String).isNullOrBlank()) "Missing required argument 'executable'" else null
            }
            else -> null
        }
    }

    private fun mapToolToCapability(tool: AgentTool): String {
        return when (tool.name) {
            "read_file", "write_file", "list_files", "file_exists", "create_directory", "patch_file" -> "FILES"
            "execute_code" -> "CODING"
            else -> "FILES"
        }
    }
}
