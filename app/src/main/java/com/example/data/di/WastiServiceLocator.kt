package com.example.data.di

import android.content.Context
import com.example.data.agent.runtime.*
import com.example.data.db.WastiDatabase
import com.example.data.repository.WastiRepository
import com.example.data.wre.WreManager

/**
 * Clean Service Locator / Dependency Container for Wasti AI OS.
 * Manages singleton lifecycles, provides loose coupling, and supports easy testing.
 */
object WastiServiceLocator {

    @Volatile
    private var appContext: Context? = null

    // Core Event Bus
    val agentEventBus: AgentEventBus by lazy {
        AgentEventBus(replay = 50, extraBufferCapacity = 200)
    }

    // Security & Stop Controller
    val emergencyStopController: WastiEmergencyStopController by lazy {
        WastiEmergencyStopController()
    }

    val workspaceManager: WorkspaceManager by lazy {
        val ctx = requireContext()
        WorkspaceManager(ctx)
    }

    val securityPolicyEngine: WastiSecurityPolicyEngine by lazy {
        WastiSecurityPolicyEngine(workspaceManager, emergencyStopController)
    }

    val capabilityRegistry: WastiCapabilityRegistry by lazy {
        WastiCapabilityRegistry()
    }

    val permissionModel: WastiPermissionModel by lazy {
        WastiPermissionModel()
    }

    val auditLogger: WastiAuditLogger by lazy {
        WastiAuditLogger()
    }

    // Database & Repository
    val database: WastiDatabase by lazy {
        val ctx = requireContext()
        WastiDatabase.getDatabase(ctx)
    }

    val repository: WastiRepository by lazy {
        WastiRepository(database)
    }

    val wreManager: WreManager by lazy {
        val ctx = requireContext()
        WreManager.getInstance(ctx)
    }

    // Execution & Runtime Managers
    val runtimeManager: WastiRuntimeManager by lazy {
        val ctx = requireContext()
        WastiRuntimeManager(ctx, workspaceManager)
    }

    val toolRegistry: AgentToolRegistry by lazy {
        AgentToolRegistry()
    }

    val taskManager: AgentTaskManager by lazy {
        AgentTaskManager()
    }

    val realityRegistry: CapabilityRealityRegistry by lazy {
        executionFabric.realityRegistry
    }

    val executionFabric: UnifiedExecutionFabric by lazy {
        val ctx = requireContext()
        UnifiedExecutionFabric.getInstance(ctx)
    }

    val toolRouter: WastiAgentToolRouter by lazy {
        WastiAgentToolRouter(
            registry = toolRegistry,
            securityPolicy = securityPolicyEngine,
            permissionModel = permissionModel,
            emergencyStop = emergencyStopController,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )
    }

    val errorAnalyzer: ErrorAnalyzer by lazy {
        ErrorAnalyzer()
    }

    val agentModelProvider: AgentModelProvider by lazy {
        RuleBasedAgentModelProvider()
    }

    val planner: AgentPlanner by lazy {
        AgentPlanner(agentModelProvider, toolRegistry, capabilityRegistry)
    }

    val selfCorrectionEngine: SelfCorrectionEngine by lazy {
        SelfCorrectionEngine(
            modelProvider = agentModelProvider,
            toolRouter = toolRouter,
            workspaceManager = workspaceManager
        )
    }

    val loopEngine: AgenticLoopEngine by lazy {
        AgenticLoopEngine(
            taskManager = taskManager,
            eventBus = agentEventBus,
            planner = planner,
            toolRouter = toolRouter,
            errorAnalyzer = errorAnalyzer,
            correctionEngine = selfCorrectionEngine,
            emergencyStopController = emergencyStopController
        )
    }

    val agentRuntime: WastiAgentRuntimeImpl by lazy {
        WastiAgentRuntimeImpl(
            taskManager = taskManager,
            eventBus = agentEventBus,
            loopEngine = loopEngine,
            emergencyStopController = emergencyStopController,
            toolRouter = toolRouter
        )
    }

    val wastiOSRuntime: com.example.data.core.WastiOSRuntime by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.core.WastiOSRuntime.getInstance(ctx)
    }

    val commandTransport: com.example.data.transport.WastiCommandTransport by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.transport.WastiCommandTransport.getInstance(ctx)
    }

    val localServerManager: com.example.data.server.WastiLocalServerManager by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.server.WastiLocalServerManager.getInstance(ctx)
    }

    val nodeManager: com.example.data.node.WastiNodeManager by lazy {
        com.example.data.node.WastiNodeManager.getInstance()
    }

    val nativeBridgeManager: com.example.data.bridge.WastiNativeBridgeManager by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.bridge.WastiNativeBridgeManager.getInstance(ctx)
    }

    val autonomousCapabilityOrchestrator: com.example.data.workflow.AutonomousCapabilityOrchestrator by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.workflow.AutonomousCapabilityOrchestrator(
            context = ctx,
            eventBus = agentEventBus,
            emergencyStopController = emergencyStopController
        )
    }

    val proactiveAutonomousEngine: com.example.data.proactive.WastiProactiveAutonomousEngine by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.proactive.WastiProactiveAutonomousEngine.getInstance(ctx)
    }

    val meshTransport: com.example.data.mesh.WebSocketMeshTransport by lazy {
        com.example.data.mesh.WebSocketMeshTransport.getInstance()
    }

    val nodeDiagnosticEngine: com.example.data.node.WastiNodeDiagnosticEngine by lazy {
        com.example.data.node.WastiNodeDiagnosticEngine.getInstance()
    }

    val conversationFabric: com.example.data.conversation.UniversalConversationFabric by lazy {
        val ctx = appContext ?: com.example.WastiApplication.instance
        com.example.data.conversation.UniversalConversationFabric.getInstance(ctx)
    }

    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: com.example.WastiApplication.instance
        ?: throw IllegalStateException("WastiServiceLocator is not initialized. Call init(context) first.")
    }
}
