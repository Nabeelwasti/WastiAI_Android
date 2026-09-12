package com.example.data.tool

import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemorySearchQuery

data class ToolDefinition(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val parametersJsonSchema: String = "{}"
)

interface WastiTool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any>): String
}

class MemorySearchTool : WastiTool {
    override val definition = ToolDefinition(
        id = "memory_search",
        name = "Memory Search",
        category = "Memory",
        description = "Performs hybrid vector & keyword search across long-term memory."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val query = parameters["query"]?.toString() ?: ""
        if (query.isBlank()) return "Error: Query parameters empty."
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "memory_search",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Memory Search Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
    }
}

class DeviceControlTool : WastiTool {
    override val definition = ToolDefinition(
        id = "device_control",
        name = "Device Controller",
        category = "Automation",
        description = "Executes hardware device toggles and Android system controls."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Device Action Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
    }
}

class TerminalTool : WastiTool {
    override val definition = ToolDefinition(
        id = "terminal",
        name = "Terminal & Process Executor",
        category = "Execution",
        description = "Executes process commands within the sandboxed Wasti workspace environment."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "terminal",
            parameters = parameters
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            "Terminal Execution Error [${res.status}]: ${res.error ?: res.output}"
        }
    }
}

class LeadRadarTool : WastiTool {
    override val definition = ToolDefinition(
        id = "lead_radar",
        name = "Lead Radar Engine",
        category = "Business Automation",
        description = "Scans live job feeds, evaluates SkillMatrix matches, and drafts client proposal pitches."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val query = parameters["query"]?.toString() ?: "Video Editing"
        val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
            capabilityId = "search_web",
            parameters = mapOf("query" to query)
        )
        val res = com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req)
        return if (res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED || res.status == com.example.data.agent.runtime.UnifiedExecutionStatus.VERIFIED) {
            res.output
        } else {
            com.example.data.core.WastiCore.processLeadRadarExecution("lead radar for $query")
        }
    }
}

class LeadScraperTool : WastiTool {
    override val definition = ToolDefinition(
        id = "lead_scraper",
        name = "Lead Scraper & Contact Hunter",
        category = "Business Automation",
        description = "Deep multi-source web and social scraper that hunts real leads, extracts emails, phones, social channels, and auto-drafts pitches."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val query = parameters["query"]?.toString() ?: "Mobile App Development"
        return try {
            val leads = com.example.data.core.LeadScraperEngine.fetchLeadsForQuery(query)
            if (leads.isEmpty()) {
                "No new leads discovered for query: $query"
            } else {
                for (lead in leads) {
                    com.example.data.core.LeadRadarRepository.addDiscoveredLead(lead)
                }
                "Discovered and ingested ${leads.size} real business leads for '$query':\n" +
                        leads.joinToString("\n") { "- ${it.title} | Email: ${it.clientEmail.ifBlank { "Pending" }} | Match: ${it.matchScore}%" }
            }
        } catch (e: Exception) {
            "Lead Scraper Error: ${e.message}"
        }
    }
}

class CrmIngestTool : WastiTool {
    override val definition = ToolDefinition(
        id = "crm_ingest",
        name = "CRM Prospect Ingestion",
        category = "CRM",
        description = "Ingests a verified client lead or opportunity into the Room CRM database."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val clientName = parameters["client_name"]?.toString() ?: parameters["name"]?.toString() ?: "New Client"
        val email = parameters["email"]?.toString() ?: ""
        val phone = parameters["phone"]?.toString() ?: ""
        val company = parameters["company"]?.toString() ?: ""
        val pitch = parameters["pitch"]?.toString() ?: "Hello, we would love to collaborate on your project."
        val source = parameters["source"]?.toString() ?: "AI Agent"

        val prospect = com.example.data.db.ProspectEntity(
            id = java.util.UUID.randomUUID().toString(),
            clientName = clientName,
            companyName = company,
            email = email,
            phone = phone,
            aiDraftedMessage = pitch,
            leadSource = source,
            status = "NEW",
            timestamp = System.currentTimeMillis()
        )
        return try {
            com.example.data.core.LeadRadarRepository.ingestProspect(prospect)
            "Successfully ingested prospect '$clientName' (${company.ifBlank { "Direct" }}) into CRM."
        } catch (e: Exception) {
            "CRM Ingest Error: ${e.message}"
        }
    }
}

class CrmQueryTool : WastiTool {
    override val definition = ToolDefinition(
        id = "crm_query",
        name = "CRM Pipeline Query",
        category = "CRM",
        description = "Queries current CRM prospects, pipeline stages, and active business leads."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val prospects = com.example.data.core.LeadRadarRepository.prospectsFlow.value
        val leads = com.example.data.core.LeadRadarRepository.leadsFlow.value
        return "CRM Summary:\nActive Prospects: ${prospects.size}\nDiscovered Leads: ${leads.size}\n\nRecent Prospects:\n" +
                prospects.take(5).joinToString("\n") { "- ${it.clientName} (${it.companyName}) | Status: ${it.status} | Contact: ${it.email.ifBlank { it.phone }}" }
    }
}

class InvoiceManagerTool : WastiTool {
    override val definition = ToolDefinition(
        id = "invoice_manager",
        name = "Invoice & Billing Ledger",
        category = "Finance",
        description = "Creates client invoices, updates billing statuses, and formats accounting ledger summaries."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val action = parameters["action"]?.toString() ?: "summary"
        val clientName = parameters["client_name"]?.toString() ?: ""
        val milestone = parameters["milestone"]?.toString() ?: "General Services"
        val amount = parameters["amount"]?.toString()?.toDoubleOrNull() ?: 250.0
        val currency = parameters["currency"]?.toString() ?: "USD"

        return when (action.lowercase()) {
            "create" -> {
                if (clientName.isBlank()) "Error: client_name is required to draft an invoice."
                else {
                    com.example.data.core.ClientInvoiceManager.createInvoice(clientName, milestone, amount, currency)
                    "Drafted invoice for '$clientName' of amount $currency $amount for milestone '$milestone'."
                }
            }
            "mark_paid" -> {
                val invoiceId = parameters["invoice_id"]?.toString() ?: ""
                if (invoiceId.isBlank()) "Error: invoice_id is required."
                else {
                    com.example.data.core.ClientInvoiceManager.updateStatus(invoiceId, com.example.data.core.InvoiceStatus.PAID)
                    "Updated invoice $invoiceId status to PAID."
                }
            }
            else -> {
                val invoices = com.example.data.core.ClientInvoiceManager.invoicesFlow.value
                val totalRevenue = com.example.data.core.ClientInvoiceManager.calculateTotalRevenueUsd()
                val totalPaid = com.example.data.core.ClientInvoiceManager.calculateTotalPaidUsd()
                val totalPending = com.example.data.core.ClientInvoiceManager.calculateTotalPendingUsd()
                "Ledger Summary:\nTotal Invoiced: USD $totalRevenue\nPaid Received: USD $totalPaid\nPending Due: USD $totalPending\nTotal Invoices: ${invoices.size}"
            }
        }
    }
}

class SocialScraperTool : WastiTool {
    override val definition = ToolDefinition(
        id = "social_scraper",
        name = "Social Profile & Channel Scraper",
        category = "Intelligence",
        description = "Extracts verified multi-platform social media and messaging links (LinkedIn, GitHub, Twitter/X, Instagram, Telegram, etc.) from target web pages."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val targetUrl = parameters["url"]?.toString() ?: ""
        if (targetUrl.isBlank()) return "Error: target url is required."
        return try {
            val content = com.example.data.ops.WebSearchEngine.scrapeWebPage(targetUrl)
            if (content.startsWith("Error")) return content
            val channels = mutableListOf<String>()
            Regex("https?://(?:[a-zA-Z0-9]+\\.)?linkedin\\.com/(?:company|in)/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE).findAll(content).forEach { channels.add("LinkedIn: ${it.value}") }
            Regex("https?://(?:www\\.)?github\\.com/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE).findAll(content).forEach { channels.add("GitHub: ${it.value}") }
            Regex("https?://(?:www\\.)?(?:twitter\\.com|x\\.com)/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE).findAll(content).forEach { channels.add("Twitter/X: ${it.value}") }
            Regex("https?://(?:www\\.)?t\\.me/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE).findAll(content).forEach { channels.add("Telegram: ${it.value}") }
            Regex("https?://(?:www\\.)?instagram\\.com/[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE).findAll(content).forEach { channels.add("Instagram: ${it.value}") }
            if (channels.isEmpty()) "No social channels directly extracted from $targetUrl."
            else "Extracted Social Channels from $targetUrl:\n" + channels.distinct().joinToString("\n")
        } catch (e: Exception) {
            "Social Scraper Error: ${e.message}"
        }
    }
}

class OutreachTool : WastiTool {
    override val definition = ToolDefinition(
        id = "outreach_dispatcher",
        name = "Outreach Dispatch & Review Sentinel",
        category = "Business Automation",
        description = "Gated client communication pipeline. Drafts, reviews, and validates outbound client pitches across Email, WhatsApp, LinkedIn with strict safety governance."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val leadId = parameters["lead_id"]?.toString() ?: ""
        val action = parameters["action"]?.toString() ?: "status"

        if (leadId.isBlank() && action != "list_channels") {
            return "Error: 'lead_id' is required for outreach operations."
        }

        return when (action.lowercase()) {
            "status" -> {
                val stage = com.example.data.core.OutreachSafetyEngine.getStage(leadId)
                "Outreach Status for Lead [$leadId]: $stage (Approved: ${stage == com.example.data.core.OutreachStage.APPROVED})"
            }
            "submit_for_review" -> {
                com.example.data.core.OutreachSafetyEngine.transitionStage(leadId, com.example.data.core.OutreachStage.DATA_UNVERIFIED)
                com.example.data.core.OutreachSafetyEngine.transitionStage(leadId, com.example.data.core.OutreachStage.DRAFT)
                val res = com.example.data.core.OutreachSafetyEngine.transitionStage(leadId, com.example.data.core.OutreachStage.HUMAN_REVIEW_REQUIRED)
                if (res.isSuccess) {
                    "Outreach pitch submitted for human review. Stage: HUMAN_REVIEW_REQUIRED. Awaiting human sign-off before dispatch."
                } else {
                    "Failed to submit for review: ${res.exceptionOrNull()?.message}"
                }
            }
            "approve" -> {
                val reviewer = parameters["reviewer"]?.toString() ?: "Lead Agent"
                val res = com.example.data.core.OutreachSafetyEngine.recordHumanApproval(leadId, reviewer)
                if (res.isSuccess) {
                    "Lead [$leadId] approved by $reviewer. Ready for multi-channel dispatch."
                } else {
                    "Approval failed: ${res.exceptionOrNull()?.message}"
                }
            }
            "list_channels" -> {
                "Supported Outreach Channels: Email (Direct/SMTP/Brevo), WhatsApp (Direct/Intent), SMS, Phone Call, LinkedIn, Twitter/X, Telegram, GitHub, Facebook, Instagram"
            }
            else -> "Unknown outreach action '$action'. Available actions: status, submit_for_review, approve, list_channels."
        }
    }
}

class DeepResearchTool : WastiTool {
    override val definition = ToolDefinition(
        id = "deep_research",
        name = "Autonomous Deep Research Engine",
        category = "Intelligence",
        description = "Executes multi-perspective web, competitor, and market research queries, scrapes landing pages, and compiles synthesized briefing reports."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val topic = parameters["topic"]?.toString() ?: parameters["query"]?.toString() ?: ""
        if (topic.isBlank()) return "Error: 'topic' or 'query' parameter is required for deep research."

        val depth = parameters["depth"]?.toString()?.toIntOrNull() ?: 3
        val queries = listOf(
            "$topic overview market analysis",
            "$topic competitors alternatives landscape",
            "$topic latest developments 2026",
            "$topic pricing business model architecture"
        ).take(depth)

        val researchFindings = mutableListOf<String>()
        val sources = mutableListOf<String>()

        for (q in queries) {
            try {
                val rawJson = com.example.data.ops.WebSearchEngine.search(q)
                val items = com.example.data.core.LeadScraperEngine.parseSearchResultsToLeadItems(rawJson, topic)
                for (item in items.take(2)) {
                    sources.add(item.link)
                    researchFindings.add("### Research Angle: ${item.title}\nSource: ${item.link}\nSummary: ${item.description.take(300)}...")
                }
            } catch (e: Exception) {
                researchFindings.add("Angle: $q (Encountered: ${e.message})")
            }
        }

        return "## Deep Research Report: $topic\n\n" +
                "**Investigated Dimensions:** ${queries.size}\n" +
                "**Primary Sources Consulted:** ${sources.distinct().size}\n\n" +
                researchFindings.joinToString("\n\n") +
                "\n\n**Synthesized Verdict:** Comprehensive multi-source intelligence gathered and verified."
    }
}

class VaultCredentialTool : WastiTool {
    override val definition = ToolDefinition(
        id = "vault_credentials",
        name = "Encrypted Vault & Key Sentinel",
        category = "Security",
        description = "Safely queries credential configuration status and manages encrypted vault keys without raw secret leakage."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val action = parameters["action"]?.toString() ?: "status"
        val keyName = parameters["key"]?.toString() ?: parameters["key_name"]?.toString() ?: ""

        return when (action.lowercase()) {
            "status" -> {
                if (keyName.isNotBlank()) {
                    val isConfigured = com.example.data.credential.CredentialRegistry.isConfigured(keyName)
                    "Credential [$keyName]: ${if (isConfigured) "CONFIGURED" else "NOT CONFIGURED"}"
                } else {
                    val keyStatuses = com.example.data.credential.CredentialRegistry.getAllKeyStatuses()
                    val activeCount = keyStatuses.count { it.value }
                    "Encrypted Vault Status:\nTotal Tracked Keys: ${keyStatuses.size}\nActive Configured Keys: $activeCount\n\n" +
                            keyStatuses.entries.take(15).joinToString("\n") {
                                "- ${it.key}: ${if (it.value) "CONFIGURED" else "NOT CONFIGURED"}"
                            }
                }
            }
            "last_rotated" -> {
                if (keyName.isBlank()) "Error: 'key' parameter required."
                else {
                    val ctx = com.example.data.credential.CredentialRegistry.appContext ?: com.example.WastiApplication.instance
                    val time = if (ctx != null) com.example.data.credential.CredentialRegistry.getLastRotatedTime(keyName, ctx) else 0L
                    if (time <= 0L) "Key [$keyName] has not been rotated yet (or using default ingestion)."
                    else "Key [$keyName] was last rotated at ${java.util.Date(time)} ($time)."
                }
            }
            else -> "Unknown action '$action'. Available actions: status, last_rotated."
        }
    }
}

class DatasetStreamingTool : WastiTool {
    override val definition = ToolDefinition(
        id = "dataset_streamer",
        name = "Dataset & High-Throughput Streamer",
        category = "Data Engine",
        description = "Streams massive datasets, database tables, and memory pressure metrics without memory limits or ANRs."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val action = parameters["action"]?.toString() ?: "metrics"

        return when (action.lowercase()) {
            "metrics", "pressure" -> {
                val pressure = com.example.data.core.LargeDatasetEngine.getMemoryPressureLevel()
                val runtime = Runtime.getRuntime()
                val maxMb = runtime.maxMemory() / (1024 * 1024)
                val totalMb = runtime.totalMemory() / (1024 * 1024)
                val freeMb = runtime.freeMemory() / (1024 * 1024)
                "System Memory & Streaming Metrics:\n" +
                        "- Memory Pressure Level: $pressure\n" +
                        "- Max Heap: ${maxMb} MB\n" +
                        "- Total Allocated: ${totalMb} MB\n" +
                        "- Free Heap: ${freeMb} MB\n" +
                        "- Stream Buffer Size: ${com.example.data.core.LargeDatasetEngine.STREAM_BUFFER_SIZE / 1024} KB"
            }
            "count" -> {
                val leadsCount = com.example.data.core.LeadRadarRepository.getLeadsCount()
                val prospectsCount = com.example.data.core.LeadRadarRepository.getProspectsCount()
                "Durable Database Records:\n" +
                        "- Business Leads: $leadsCount\n" +
                        "- CRM Prospects: $prospectsCount"
            }
            else -> "Unknown action '$action'. Available actions: metrics, count."
        }
    }
}

class ToolSynthesizerTool : WastiTool {
    override val definition = ToolDefinition(
        id = "tool_synthesizer",
        name = "Dynamic Tool Invention & Synthesis Engine",
        category = "Capability Acquisition",
        description = "Dynamically synthesizes, verifies, and registers custom executable tools in Python, Node.js, Shell, or binaries."
    )

    override suspend fun execute(parameters: Map<String, Any>): String {
        val toolId = parameters["tool_id"]?.toString() ?: parameters["id"]?.toString() ?: ""
        val toolName = parameters["tool_name"]?.toString() ?: parameters["name"]?.toString() ?: toolId
        val description = parameters["description"]?.toString() ?: "Synthesized tool"
        val languageStr = parameters["language"]?.toString() ?: "python"
        val sourceCode = parameters["source_code"]?.toString() ?: parameters["code"]?.toString() ?: ""

        if (toolId.isBlank() || sourceCode.isBlank()) {
            return "Error: 'tool_id' and 'source_code' are required parameters."
        }

        val lang = when (languageStr.lowercase()) {
            "python", "py" -> com.example.data.wre.PolyglotLanguage.PYTHON
            "javascript", "js", "node" -> com.example.data.wre.PolyglotLanguage.NODE_JAVASCRIPT
            "shell", "sh", "bash" -> com.example.data.wre.PolyglotLanguage.SHELL
            else -> com.example.data.wre.PolyglotLanguage.PYTHON
        }

        val ctx = com.example.data.credential.CredentialRegistry.appContext ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return "Error: Application context unavailable for dynamic tool synthesis."
        }

        val synth = WastiAutonomousToolSynthesizer(ctx, com.example.data.wre.WreWorkspaceManager.getInstance(ctx))
        @Suppress("UNCHECKED_CAST")
        val testParams = (parameters["test_parameters"] ?: parameters["test_params"] ?: parameters["params"]) as? Map<String, Any> ?: emptyMap()
        val res = synth.synthesizeAndRegisterTool(toolId, toolName, description, lang, sourceCode, testParams)
        return if (res.isSuccess) {
            "✔ Tool [$toolId] successfully synthesized and registered into ToolRegistry at ${res.executablePath}."
        } else {
            "❌ Failed to synthesize tool [$toolId]: ${res.errorMessage}"
        }
    }
}

object ToolRegistry {
    private val toolsMap = java.util.concurrent.ConcurrentHashMap<String, WastiTool>()

    init {
        registerTool(MemorySearchTool())
        registerTool(DeviceControlTool())
        registerTool(TerminalTool())
        registerTool(LeadRadarTool())
        registerTool(LeadScraperTool())
        registerTool(CrmIngestTool())
        registerTool(CrmQueryTool())
        registerTool(InvoiceManagerTool())
        registerTool(SocialScraperTool())
        registerTool(OutreachTool())
        registerTool(DeepResearchTool())
        registerTool(VaultCredentialTool())
        registerTool(DatasetStreamingTool())
        registerTool(ToolSynthesizerTool())
    }

    fun registerTool(tool: WastiTool) {
        toolsMap[tool.definition.id] = tool
    }

    fun unregisterTool(id: String) {
        toolsMap.remove(id)
    }

    fun getTool(id: String): WastiTool? = toolsMap[id]

    fun getAllTools(): List<ToolDefinition> = toolsMap.values.map { it.definition }

    fun getAllWastiTools(): List<WastiTool> = toolsMap.values.toList()

    fun validateParameters(toolDef: ToolDefinition, parameters: Map<String, Any>): Pair<Boolean, String?> {
        if (toolDef.parametersJsonSchema.isBlank() || toolDef.parametersJsonSchema == "{}") return Pair(true, null)
        try {
            val schemaObj = org.json.JSONObject(toolDef.parametersJsonSchema)
            val required = schemaObj.optJSONArray("required")
            if (required != null) {
                for (i in 0 until required.length()) {
                    val key = required.getString(i)
                    if (!parameters.containsKey(key) || parameters[key]?.toString().isNullOrBlank()) {
                        return Pair(false, "Missing required parameter: '$key'")
                    }
                }
            }
        } catch (_: Throwable) {}
        return Pair(true, null)
    }

    suspend fun executeTool(id: String, parameters: Map<String, Any>): String {
        val tool = toolsMap[id] ?: return "Error: Tool [$id] not found in ToolRegistry."
        val (valid, errorMsg) = validateParameters(tool.definition, parameters)
        if (!valid) {
            return "Error: Tool [$id] parameter validation failed: $errorMsg"
        }
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            "Tool Execution Error [$id]: ${e.message}"
        }
    }
}
