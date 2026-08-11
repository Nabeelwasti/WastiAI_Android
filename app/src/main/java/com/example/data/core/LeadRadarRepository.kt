package com.example.data.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.data.db.LeadEntity
import com.example.data.db.ProspectEntity
import com.example.data.db.WastiDatabase
import com.example.data.notification.WastiNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LeadStatus {
    DISCOVERED,
    PROPOSAL_SENT,
    NEGOTIATING,
    CLOSED
}

data class LeadItemEntity(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String = "",
    val category: String = "",
    val matchScore: Int = 85,
    val matchedSkills: List<String> = emptyList(),
    val draftedPitch: String = "",
    var status: LeadStatus = LeadStatus.DISCOVERED,
    val clientEmail: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object LeadRadarRepository {

    private const val TAG = "LeadRadarRepository"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _leadsFlow = MutableStateFlow<List<LeadItemEntity>>(emptyList())
    val leadsFlow: StateFlow<List<LeadItemEntity>> = _leadsFlow.asStateFlow()

    private val _prospectsFlow = MutableStateFlow<List<ProspectEntity>>(emptyList())
    val prospectsFlow: StateFlow<List<ProspectEntity>> = _prospectsFlow.asStateFlow()

    private val _lastSearchQuery = MutableStateFlow("Video Editing & Graphic Design")
    val lastSearchQuery: StateFlow<String> = _lastSearchQuery.asStateFlow()

    private var isDbInitialized = false

    fun initDatabase(context: Context) {
        appContext = context.applicationContext
        if (isDbInitialized) return
        isDbInitialized = true

        scope.launch {
            val db = WastiDatabase.getDatabase(context)
            val leadDao = db.leadDao()
            val prospectDao = db.prospectDao()

            // Observe Room DB updates reactively
            launch {
                leadDao.getAllLeads().collect { dbLeads ->
                    _leadsFlow.value = dbLeads.map { it.toUiModel() }
                }
            }

            launch {
                prospectDao.getAllProspects().collect { dbProspects ->
                    _prospectsFlow.value = dbProspects
                }
            }
        }
    }

    fun extractEmail(sourceText: String): String {
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val match = emailRegex.find(sourceText)
        return match?.value?.trim() ?: "Pending Discovery"
    }

    fun extractPhone(sourceText: String): String {
        val phoneRegex = Regex("\\+?[0-9]{1,4}?[-.\\s]?\\(?[0-9]{1,3}?\\)?[-.\\s]?[0-9]{1,4}[-.\\s]?[0-9]{1,9}")
        val matches = phoneRegex.findAll(sourceText).map { it.value.trim() }.filter { it.length >= 7 }
        return matches.firstOrNull() ?: "Pending Discovery"
    }

    fun extractCompanyName(title: String, description: String): String {
        val text = "$title $description"
        val atCompanyRegex = Regex("(?i)at\\s+([A-Z][A-Za-z0-9&\\s]{2,20})")
        val match = atCompanyRegex.find(text)
        return match?.groupValues?.get(1)?.trim() ?: "Pending Discovery"
    }

    suspend fun enrichLeadWithGeminiAndSearch(
        context: Context,
        lead: LeadItemEntity
    ): ProspectEntity = withContext(Dispatchers.IO) {
        val fullText = "${lead.title}\n${lead.description}\n${lead.clientEmail}"
        var email = if (lead.clientEmail.isNotBlank() && extractEmail(lead.clientEmail) != "Pending Discovery") lead.clientEmail else extractEmail(fullText)
        var phone = extractPhone(fullText)
        var companyName = extractCompanyName(lead.title, lead.description)
        var clientName = if (lead.title.isNotBlank()) lead.title.take(40) else "Pending Discovery"
        var country = "Pending Discovery"
        var region = "Pending Discovery"
        var websiteUrl = if (lead.link.startsWith("http")) lead.link else "Pending Discovery"
        var opportunityNature = lead.category.ifBlank { "Video Editing" }
        var aiDraftedMessage = lead.draftedPitch

        // Step 1: Gemini Intelligence Analysis
        try {
            val geminiPrompt = """
                Analyze this lead/prospect information and extract detailed CRM fields in raw JSON format.
                Lead Title: "${lead.title}"
                Lead Description: "${lead.description}"
                Link: "${lead.link}"

                Return a valid JSON object ONLY with the following exact keys:
                - clientName (String, e.g. "John Smith" or "Pending Discovery")
                - companyName (String, e.g. "Apex Media" or "Pending Discovery")
                - country (String, e.g. "United States" or "Pending Discovery")
                - region (String, e.g. "California" or "Pending Discovery")
                - email (String, valid email address or "Pending Discovery")
                - phone (String, valid phone number or "Pending Discovery")
                - websiteUrl (String, website URL or "Pending Discovery")
                - opportunityNature (String, e.g. "Video Editing", "Graphic Design", "AI Automation")
                - aiDraftedMessage (String, a personalized 2-sentence high-converting client outreach pitch)
            """.trimIndent()

            val aiResp = com.example.data.ai.AIManager.execute(
                prompt = geminiPrompt,
                systemInstruction = "You are a CRM Data Intelligence Agent. Return ONLY valid JSON."
            )

            if (!aiResp.isError && aiResp.content.isNotBlank()) {
                val rawContent = aiResp.content.trim()
                val jsonStart = rawContent.indexOf("{")
                val jsonEnd = rawContent.lastIndexOf("}")
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonObj = org.json.JSONObject(rawContent.substring(jsonStart, jsonEnd + 1))
                    if (clientName == "Pending Discovery" && jsonObj.optString("clientName").isNotBlank()) {
                        val cn = jsonObj.optString("clientName")
                        if (cn != "Pending Discovery") clientName = cn
                    }
                    if (companyName == "Pending Discovery" && jsonObj.optString("companyName").isNotBlank()) {
                        val comp = jsonObj.optString("companyName")
                        if (comp != "Pending Discovery") companyName = comp
                    }
                    if (country == "Pending Discovery" && jsonObj.optString("country").isNotBlank()) {
                        val c = jsonObj.optString("country")
                        if (c != "Pending Discovery") country = c
                    }
                    if (region == "Pending Discovery" && jsonObj.optString("region").isNotBlank()) {
                        val r = jsonObj.optString("region")
                        if (r != "Pending Discovery") region = r
                    }
                    if (email == "Pending Discovery" && jsonObj.optString("email").contains("@")) {
                        email = jsonObj.optString("email")
                    }
                    if (phone == "Pending Discovery" && jsonObj.optString("phone").length >= 7) {
                        phone = jsonObj.optString("phone")
                    }
                    if (websiteUrl == "Pending Discovery" && jsonObj.optString("websiteUrl").startsWith("http")) {
                        websiteUrl = jsonObj.optString("websiteUrl")
                    }
                    if (jsonObj.optString("opportunityNature").isNotBlank() && jsonObj.optString("opportunityNature") != "Pending Discovery") {
                        opportunityNature = jsonObj.optString("opportunityNature")
                    }
                    if (aiDraftedMessage.isBlank() && jsonObj.optString("aiDraftedMessage").isNotBlank()) {
                        aiDraftedMessage = jsonObj.optString("aiDraftedMessage")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini lead enrichment failed: ${e.message}")
        }

        // Step 2: Google Search Real-Time Contact Discovery Fallback
        if ((email == "Pending Discovery" || phone == "Pending Discovery") && (companyName != "Pending Discovery" || clientName != "Pending Discovery")) {
            try {
                val searchTarget = if (companyName != "Pending Discovery") companyName else clientName
                val searchQuery = "$searchTarget contact email phone website"
                val searchResultJson = com.example.data.ops.WebSearchEngine.search(searchQuery, context)
                
                if (email == "Pending Discovery") {
                    val foundEmail = extractEmail(searchResultJson)
                    if (foundEmail != "Pending Discovery") {
                        email = foundEmail
                    }
                }
                if (phone == "Pending Discovery") {
                    val foundPhone = extractPhone(searchResultJson)
                    if (foundPhone != "Pending Discovery") {
                        phone = foundPhone
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google search contact discovery failed: ${e.message}")
            }
        }

        val leadSource = when {
            lead.link.contains("google", ignoreCase = true) || lead.link.contains("xray", ignoreCase = true) -> "Google X-Ray"
            lead.link.contains("upwork", ignoreCase = true) -> "Upwork RSS"
            else -> "Web Scraper"
        }

        ProspectEntity(
            id = lead.id,
            clientName = clientName,
            companyName = companyName,
            country = country,
            region = region,
            email = email,
            phone = phone,
            whatsappNumber = phone,
            websiteUrl = websiteUrl,
            paymentInfo = "Pending Discovery",
            leadSource = leadSource,
            opportunityNature = opportunityNature,
            status = "NEW",
            aiDraftedMessage = aiDraftedMessage.ifBlank { lead.draftedPitch },
            timestamp = System.currentTimeMillis(),
            title = lead.title,
            link = lead.link,
            description = lead.description,
            pubDate = lead.pubDate,
            category = lead.category,
            matchScore = lead.matchScore,
            matchedSkillsCsv = lead.matchedSkills.joinToString(","),
            draftedPitch = aiDraftedMessage.ifBlank { lead.draftedPitch },
            clientEmail = email
        )
    }

    suspend fun ingestToCrm(context: Context, lead: LeadItemEntity, status: String = "NEW") = withContext(Dispatchers.IO) {
        initDatabase(context)
        val db = WastiDatabase.getDatabase(context)

        // Enrich lead with Gemini Intelligence & Google Search
        val prospect = enrichLeadWithGeminiAndSearch(context, lead).copy(
            status = status.ifBlank { "NEW" }
        )

        db.prospectDao().insertProspect(prospect)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Added lead '${prospect.clientName}' to CRM with AI & Search enrichment!", Toast.LENGTH_SHORT).show()
        }
    }

    fun ingestToCrm(lead: LeadItemEntity) {
        val targetContext = appContext
        if (targetContext != null) {
            scope.launch {
                ingestToCrm(targetContext, lead)
            }
        } else {
            val fullText = "${lead.title}\n${lead.description}\n${lead.clientEmail}"
            val extractedEmail = if (lead.clientEmail.isNotBlank() && extractEmail(lead.clientEmail) != "Pending Discovery") lead.clientEmail else extractEmail(fullText)
            val extractedPhone = extractPhone(fullText)

            val existing = _prospectsFlow.value.toMutableList()
            if (existing.none { it.id == lead.id }) {
                existing.add(
                    ProspectEntity(
                        id = lead.id,
                        clientName = if (lead.title.isNotBlank()) lead.title.take(35) else "Pending Discovery",
                        companyName = extractCompanyName(lead.title, lead.description),
                        email = extractedEmail,
                        phone = extractedPhone,
                        whatsappNumber = extractedPhone,
                        websiteUrl = if (lead.link.startsWith("http")) lead.link else "Pending Discovery",
                        leadSource = "Web Scraper",
                        opportunityNature = lead.category.ifBlank { "Video Editing" },
                        status = "NEW",
                        aiDraftedMessage = lead.draftedPitch,
                        title = lead.title,
                        link = lead.link,
                        description = lead.description,
                        pubDate = lead.pubDate,
                        category = lead.category,
                        matchScore = lead.matchScore,
                        matchedSkillsCsv = lead.matchedSkills.joinToString(","),
                        draftedPitch = lead.draftedPitch,
                        clientEmail = extractedEmail,
                        timestamp = System.currentTimeMillis()
                    )
                )
                _prospectsFlow.value = existing
            }
        }
    }

    fun updateProspectStatus(context: Context, prospectId: String, newStatus: String) {
        scope.launch {
            initDatabase(context)
            val db = WastiDatabase.getDatabase(context)
            db.prospectDao().updateProspectStatus(prospectId, newStatus)
        }
    }

    suspend fun scanAndEvaluateLeads(context: Context, query: String): List<LeadItemEntity> = withContext(Dispatchers.IO) {
        initDatabase(context)
        _lastSearchQuery.value = query
        val rawLeads = LeadScraperEngine.fetchLeadsForQuery(query, context)
        val skillMatrix = SkillMatrix()

        val evaluatedEntities = rawLeads.map { lead ->
            val fullText = "${lead.title}\n${lead.description}"
            val eval = LeadScraperEngine.evaluateLeadMatch(fullText, skillMatrix)

            val entity = LeadItemEntity(
                title = lead.title,
                link = lead.link,
                description = lead.description,
                pubDate = lead.pubDate,
                category = lead.category.ifBlank { query },
                matchScore = eval.matchScore,
                matchedSkills = eval.matchedSkills,
                draftedPitch = eval.draftedPitch,
                status = LeadStatus.DISCOVERED
            )

            if (eval.matchScore >= 85) {
                WastiNotificationManager.sendHighMatchLeadNotification(
                    context = context,
                    leadTitle = lead.title,
                    matchScore = eval.matchScore,
                    category = lead.category.ifBlank { query },
                    draftedPitch = eval.draftedPitch
                )
            }

            entity
        }

        if (evaluatedEntities.isNotEmpty()) {
            val db = WastiDatabase.getDatabase(context)
            db.leadDao().insertLeads(evaluatedEntities.map { it.toRoomEntity() })
        } else {
            val errorMsg = "No live leads found from the current feed. Please verify the RSS URL or connection."
            Log.w(TAG, errorMsg)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }

        evaluatedEntities
    }

    fun updateLeadStatus(context: Context, leadId: String, newStatus: LeadStatus) {
        scope.launch {
            initDatabase(context)
            val db = WastiDatabase.getDatabase(context)
            db.leadDao().updateLeadStatus(leadId, newStatus.name)
        }
    }

    fun updateLeadStatus(leadId: String, newStatus: LeadStatus) {
        // Fallback or memory-reflected status update
        val updated = _leadsFlow.value.map {
            if (it.id == leadId) it.copy(status = newStatus) else it
        }
        _leadsFlow.value = updated
        scope.launch {
            if (isDbInitialized) {
                // If DB context is active, update in memory list and sync
                val targetLead = updated.find { it.id == leadId }
                if (targetLead != null && appContext != null) {
                    val db = WastiDatabase.getDatabase(appContext!!)
                    db.leadDao().updateLeadStatus(leadId, newStatus.name)
                }
            }
        }
    }

    var appContext: Context? = null

    private fun LeadItemEntity.toRoomEntity(): LeadEntity {
        return LeadEntity(
            id = id,
            title = title,
            link = link,
            description = description,
            pubDate = pubDate,
            category = category,
            matchScore = matchScore,
            matchedSkillsCsv = matchedSkills.joinToString(","),
            draftedPitch = draftedPitch,
            status = status.name,
            clientEmail = clientEmail,
            timestamp = timestamp
        )
    }

    private fun LeadEntity.toUiModel(): LeadItemEntity {
        return LeadItemEntity(
            id = id,
            title = title,
            link = link,
            description = description,
            pubDate = pubDate,
            category = category,
            matchScore = matchScore,
            matchedSkills = matchedSkillsCsv.split(",").filter { it.isNotBlank() },
            draftedPitch = draftedPitch,
            status = try { LeadStatus.valueOf(status) } catch (_: Exception) { LeadStatus.DISCOVERED },
            clientEmail = clientEmail,
            timestamp = timestamp
        )
    }

    fun dispatchWhatsAppDirect(context: Context, whatsappNumber: String, message: String) {
        val cleanNum = whatsappNumber.replace(Regex("[^0-9+]"), "")
        val encodedMsg = Uri.encode(message)
        val uriStr = if (cleanNum.isNotBlank() && cleanNum != "Pending Discovery") {
            "https://wa.me/$cleanNum?text=$encodedMsg"
        } else {
            "https://wa.me/?text=$encodedMsg"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching WhatsApp Intent", e)
            dispatchViaWhatsApp(context, message)
        }
    }

    fun dispatchEmailDirect(context: Context, recipientEmail: String, subject: String, body: String) {
        val cleanEmail = if (recipientEmail.isNotBlank() && recipientEmail != "Pending Discovery") recipientEmail else ""
        val uriStr = "mailto:$cleanEmail?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uriStr)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Email Intent", e)
            dispatchViaEmail(context, subject, body, cleanEmail)
        }
    }

    fun dispatchCallDirect(context: Context, phone: String) {
        if (phone.isBlank() || phone.equals("Pending Discovery", ignoreCase = true)) {
            Toast.makeText(context, "Phone number is Pending Discovery", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Call Intent", e)
            Toast.makeText(context, "Unable to launch dialer", Toast.LENGTH_SHORT).show()
        }
    }

    fun dispatchWebsiteDirect(context: Context, websiteUrl: String) {
        if (websiteUrl.isBlank() || websiteUrl.equals("Pending Discovery", ignoreCase = true)) {
            Toast.makeText(context, "Website URL is Pending Discovery", Toast.LENGTH_SHORT).show()
            return
        }
        val safeUrl = if (!websiteUrl.startsWith("http://") && !websiteUrl.startsWith("https://")) {
            "https://$websiteUrl"
        } else websiteUrl
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Website Intent", e)
            Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
        }
    }

    fun dispatchViaWhatsApp(context: Context, pitchText: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, pitchText)
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val shareIntent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, pitchText)
                    },
                    "Send Pitch via WhatsApp / Messaging"
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening WhatsApp / Share intent", e)
                Toast.makeText(context, "Could not open messaging app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun dispatchViaEmail(context: Context, subject: String, body: String, recipientEmail: String = "") {
        try {
            val mailUri = if (recipientEmail.isNotBlank()) {
                Uri.parse("mailto:$recipientEmail")
            } else {
                Uri.parse("mailto:")
            }
            val intent = Intent(Intent.ACTION_SENDTO, mailUri).apply {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening email client", e)
            copyToClipboard(context, "Pitch Proposal", body)
            Toast.makeText(context, "Copied proposal pitch to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied $label to Clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    fun exportLeadsToCsv(leads: List<LeadItemEntity>): String {
        val sb = StringBuilder()
        sb.append("ID,Title,Category,MatchScore,Status,ClientEmail,Link,PubDate\n")
        leads.forEach { lead ->
            val safeTitle = "\"${lead.title.replace("\"", "\"\"")}\""
            val safeCategory = "\"${lead.category.replace("\"", "\"\"")}\""
            sb.append("${lead.id},$safeTitle,$safeCategory,${lead.matchScore},${lead.status.name},${lead.clientEmail},${lead.link},${lead.pubDate}\n")
        }
        return sb.toString()
    }

    fun exportLeadsToJson(leads: List<LeadItemEntity>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        leads.forEachIndexed { index, lead ->
            val comma = if (index < leads.size - 1) "," else ""
            sb.append("  {\n")
            sb.append("    \"id\": \"${lead.id}\",\n")
            sb.append("    \"title\": \"${lead.title.replace("\"", "\\\"")}\",\n")
            sb.append("    \"category\": \"${lead.category}\",\n")
            sb.append("    \"matchScore\": ${lead.matchScore},\n")
            sb.append("    \"status\": \"${lead.status.name}\",\n")
            sb.append("    \"clientEmail\": \"${lead.clientEmail}\",\n")
            sb.append("    \"link\": \"${lead.link}\"\n")
            sb.append("  }$comma\n")
        }
        sb.append("]")
        return sb.toString()
    }

    fun exportProposalsToText(leads: List<LeadItemEntity>): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("=========================================\n")
        sb.append("WASTI AI LEAD RADAR — PROPOSALS EXPORT\n")
        sb.append("Export Date: ${sdf.format(Date())}\n")
        sb.append("=========================================\n\n")

        leads.forEachIndexed { idx, lead ->
            sb.append("--- PROPOSAL #${idx + 1} ---\n")
            sb.append("Job Title: ${lead.title}\n")
            sb.append("Category: ${lead.category} | Match Score: ${lead.matchScore}/100\n")
            sb.append("Link: ${lead.link}\n\n")
            sb.append("DRAFTED PITCH:\n")
            sb.append("${lead.draftedPitch}\n\n")
            sb.append("=========================================\n\n")
        }
        return sb.toString()
    }
}
