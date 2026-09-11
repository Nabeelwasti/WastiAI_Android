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

    suspend fun executeTool(id: String, parameters: Map<String, Any>): String {
        val tool = toolsMap[id] ?: return "Error: Tool [$id] not found in ToolRegistry."
        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            "Tool Execution Error [$id]: ${e.message}"
        }
    }
}
