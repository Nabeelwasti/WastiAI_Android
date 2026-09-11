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
import com.example.data.crm.FieldProvenanceSource
import com.example.data.crm.LeadProvenanceProfile
import com.example.data.crm.LeadProvenanceTracker
import com.example.data.crm.ProvenanceTrackedField
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
    LEAD_DISCOVERED,
    DATA_UNVERIFIED,
    DRAFT,
    HUMAN_REVIEW_REQUIRED,
    APPROVED,
    SENT,
    PROPOSAL_SENT,
    NEGOTIATING,
    CLOSED;

    val isApprovedForDispatch: Boolean
        get() = this == APPROVED || this == SENT || this == PROPOSAL_SENT

    fun toOutreachStage(): OutreachStage {
        return when (this) {
            DISCOVERED, LEAD_DISCOVERED -> OutreachStage.LEAD_DISCOVERED
            DATA_UNVERIFIED -> OutreachStage.DATA_UNVERIFIED
            DRAFT -> OutreachStage.DRAFT
            HUMAN_REVIEW_REQUIRED -> OutreachStage.HUMAN_REVIEW_REQUIRED
            APPROVED -> OutreachStage.APPROVED
            SENT, PROPOSAL_SENT -> OutreachStage.SENT
            NEGOTIATING, CLOSED -> OutreachStage.SENT
        }
    }
}

data class ClientActionChannel(
    val id: String,
    val platformName: String,
    val label: String,
    val iconType: String,
    val targetData: String,
    val isPrimary: Boolean = false,
    val colorHex: Long = 0xFF6366F1,
    val onDispatch: (Context) -> Unit
)

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

    fun extractLinkedInUrl(sourceText: String): String {
        val linkedInRegex = Regex("https?://([a-zA-Z0-9]+\\.)?linkedin\\.com/(in|company)/[A-Za-z0-9_.-]+")
        val match = linkedInRegex.find(sourceText)
        return match?.value?.trim() ?: ""
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
        var opportunityNature = lead.category.ifBlank { "Creative & Technical Solutions" }
        var aiDraftedMessage = lead.draftedPitch

        val linkedInFromText = extractLinkedInUrl(fullText)
        if (linkedInFromText.isNotBlank() && (websiteUrl == "Pending Discovery" || !websiteUrl.contains("linkedin.com", ignoreCase = true))) {
            websiteUrl = linkedInFromText
        }

        // Step 1: Gemini Intelligence Analysis
        try {
            val geminiPrompt = """
                Analyze this lead/prospect information and extract detailed CRM fields in raw JSON format.
                Agency: ThriveBridge Growth Solutions
                Owner: Syed Nabeel Wasti (Creative, Digital & Technical Solutions Specialist)
                Direct Contact: WhatsApp/Call +923067370864 (03067370864), Email wastinabeel99@gmail.com
                Key Offer: Free consultation & scoping. Serving global (USA, UAE, International) and domestic Pakistan markets.
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
                - opportunityNature (String, e.g. "Graphic Design & Branding", "Web & App Solutions", "AI Integration & Automation", "Advanced Visuals", "DMCA Protection")
                - aiDraftedMessage (String, a high-converting personalized outreach proposal pitch highlighting solutions, free consultation, and signed off with Syed Nabeel Wasti | ThriveBridge Growth Solutions | 03067370864 | wastinabeel99@gmail.com. If the job lead is in Urdu, write the pitch in Urdu; otherwise write in English)
            """.trimIndent()

            val aiResp = com.example.data.ai.AIManager.execute(
                prompt = geminiPrompt,
                systemInstruction = "You are a Senior Client Engagement & CRM Agent for ThriveBridge Growth Solutions (Syed Nabeel Wasti). Return ONLY valid JSON."
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
                val foundLinkedIn = extractLinkedInUrl(searchResultJson)
                if (foundLinkedIn.isNotBlank() && (websiteUrl == "Pending Discovery" || !websiteUrl.contains("linkedin.com", ignoreCase = true))) {
                    websiteUrl = foundLinkedIn
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

        // [P0-39] Register lead provenance profile
        val isAiInferredContact = (lead.clientEmail.isBlank() && !fullText.contains("@"))
        LeadProvenanceTracker.createScrapedWithAiEnrichment(
            leadId = lead.id,
            clientName = clientName,
            email = email,
            phone = phone,
            companyName = companyName,
            websiteUrl = websiteUrl,
            budgetOrPayment = "Pending Discovery",
            opportunityNature = opportunityNature,
            isAiInferredContact = isAiInferredContact,
            scraperSource = leadSource
        )

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
                        opportunityNature = lead.category.ifBlank { "Creative & Technical Solutions" },
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

    fun addDiscoveredLead(lead: LeadItemEntity) {
        val targetContext = appContext
        if (targetContext != null) {
            scope.launch {
                val db = WastiDatabase.getDatabase(targetContext)
                db.leadDao().insertLead(lead.toRoomEntity())
            }
        }
        val current = _leadsFlow.value.toMutableList()
        if (current.none { it.id == lead.id }) {
            current.add(0, lead)
            _leadsFlow.value = current
        }
    }

    fun ingestProspect(prospect: ProspectEntity) {
        val targetContext = appContext
        if (targetContext != null) {
            scope.launch {
                val db = WastiDatabase.getDatabase(targetContext)
                db.prospectDao().insertProspect(prospect)
            }
        }
        val current = _prospectsFlow.value.toMutableList()
        if (current.none { it.id == prospect.id }) {
            current.add(0, prospect)
            _prospectsFlow.value = current
        }
    }

    suspend fun scanAndEvaluateLeads(context: Context, query: String): List<LeadItemEntity> = withContext(Dispatchers.IO) {
        initDatabase(context)
        _lastSearchQuery.value = query
        val evaluatedEntities = LeadScraperEngine.fetchLeadsForQuery(query, context)

        evaluatedEntities.forEach { lead ->
            if (lead.matchScore >= 85) {
                WastiNotificationManager.sendHighMatchLeadNotification(
                    context = context,
                    leadTitle = lead.title,
                    matchScore = lead.matchScore,
                    category = lead.category.ifBlank { query },
                    draftedPitch = lead.draftedPitch
                )
            }
        }

        if (evaluatedEntities.isNotEmpty()) {
            val db = WastiDatabase.getDatabase(context)
            db.leadDao().insertLeads(evaluatedEntities.map { it.toRoomEntity() })
        } else {
            val errorMsg = "No live leads found from the current feed/search. Please check your query or network connection."
            Log.w(TAG, errorMsg)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }

        return@withContext evaluatedEntities
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

    /**
     * Resolves the live [WastiDatabase] instance from the initialized application context.
     * Used by streaming/count operations that don't receive a Context parameter directly,
     * mirroring the pattern already used by ingestToCrm(lead)/addDiscoveredLead/ingestProspect.
     */
    private fun requireDatabase(): WastiDatabase {
        val ctx = appContext
            ?: throw IllegalStateException(
                "LeadRadarRepository: database accessed before initDatabase(context) was called."
            )
        return WastiDatabase.getDatabase(ctx)
    }

    /**
     * [P0-38] & [P0-39] Human Review Signoff:
     * Advances lead stage in OutreachSafetyEngine, verifies contact provenance, and updates status to APPROVED.
     */
    fun recordHumanOutreachApproval(
        leadId: String,
        recipient: String,
        channel: String,
        reviewer: String
    ): Result<OutreachApprovalRecord> {
        val profile = LeadProvenanceTracker.getProfile(leadId)
        if (profile != null) {
            // Explicit human approval verifies contact fields
            LeadProvenanceTracker.recordProfile(
                profile.verifyField("email", reviewer).verifyField("phone", reviewer)
            )
        }
        val result = OutreachSafetyEngine.recordHumanApproval(leadId, recipient, channel, reviewer)
        if (result.isSuccess) {
            updateLeadStatus(leadId, LeadStatus.APPROVED)
        }
        return result
    }

    /**
     * [P0-39] Get provenance profile for a lead.
     */
    fun getLeadProvenance(leadId: String): LeadProvenanceProfile? {
        return LeadProvenanceTracker.getProfile(leadId)
    }

    /**
     * [P0-39] Manually verify a lead field with reviewer identification.
     */
    fun verifyLeadField(leadId: String, fieldName: String, verifier: String): Boolean {
        val profile = LeadProvenanceTracker.getProfile(leadId) ?: return false
        val updated = profile.verifyField(fieldName, verifier)
        LeadProvenanceTracker.recordProfile(updated)
        return true
    }

    /**
     * [P0-39] Ingest user-entered lead with authentic provenance.
     */
    fun recordUserEnteredLead(
        leadId: String,
        clientName: String,
        email: String,
        phone: String,
        companyName: String,
        websiteUrl: String,
        budget: String,
        opportunityNature: String,
        userIdentifier: String = "USER"
    ): LeadProvenanceProfile {
        return LeadProvenanceTracker.createUserEntered(
            leadId = leadId,
            clientName = clientName,
            email = email,
            phone = phone,
            companyName = companyName,
            websiteUrl = websiteUrl,
            budgetOrPayment = budget,
            opportunityNature = opportunityNature,
            userIdentifier = userIdentifier
        )
    }

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

    fun formatForWhatsApp(phone: String): String {
        var clean = phone.replace(Regex("[^0-9]"), "")
        if (clean.startsWith("00")) {
            clean = clean.substring(2)
        }
        if (clean.startsWith("03") && clean.length == 11) {
            clean = "92" + clean.substring(1)
        }
        return clean
    }

    fun dispatchWhatsAppDirect(context: Context, whatsappNumber: String, message: String) {
        val cleanNum = formatForWhatsApp(whatsappNumber)
        val encodedMsg = Uri.encode(message)
        val uriStr = if (cleanNum.isNotBlank() && whatsappNumber != "Pending Discovery" && cleanNum.length >= 7) {
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

    fun dispatchSmsDirect(context: Context, phone: String, message: String) {
        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        val uriStr = if (cleanPhone.isNotBlank() && cleanPhone != "Pending Discovery") {
            "smsto:$cleanPhone"
        } else {
            "smsto:"
        }
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uriStr)).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching SMS Intent", e)
            dispatchViaWhatsApp(context, message)
        }
    }

    fun dispatchLinkedInDirect(context: Context, clientOrCompany: String, existingUrl: String = "") {
        val targetUrl = when {
            existingUrl.contains("linkedin.com", ignoreCase = true) -> existingUrl
            clientOrCompany.isNotBlank() && clientOrCompany != "Pending Discovery" -> {
                val cleanName = clientOrCompany.replace(Regex("(?i)company:"), "").trim()
                val encoded = Uri.encode(cleanName)
                "https://www.linkedin.com/search/results/all/?keywords=$encoded"
            }
            else -> "https://www.linkedin.com"
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching LinkedIn Intent", e)
            Toast.makeText(context, "Unable to open LinkedIn", Toast.LENGTH_SHORT).show()
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

    // Dynamic Multi-Platform Action Channels
    fun dispatchFacebookDirect(context: Context, identifierOrUrl: String) {
        val targetUrl = when {
            identifierOrUrl.contains("facebook.com", ignoreCase = true) -> identifierOrUrl
            identifierOrUrl.isNotBlank() && identifierOrUrl != "Pending Discovery" -> {
                val clean = identifierOrUrl.replace("@", "").trim()
                "https://www.facebook.com/$clean"
            }
            else -> "https://www.facebook.com"
        }
        openUrlIntent(context, targetUrl, "Facebook")
    }

    fun dispatchInstagramDirect(context: Context, handleOrUrl: String) {
        val targetUrl = when {
            handleOrUrl.contains("instagram.com", ignoreCase = true) -> handleOrUrl
            handleOrUrl.isNotBlank() && handleOrUrl != "Pending Discovery" -> {
                val clean = handleOrUrl.replace("@", "").trim()
                "https://www.instagram.com/$clean/"
            }
            else -> "https://www.instagram.com"
        }
        openUrlIntent(context, targetUrl, "Instagram")
    }

    fun dispatchTwitterDirect(context: Context, handleOrUrl: String) {
        val targetUrl = when {
            handleOrUrl.contains("twitter.com", ignoreCase = true) || handleOrUrl.contains("x.com", ignoreCase = true) -> handleOrUrl
            handleOrUrl.isNotBlank() && handleOrUrl != "Pending Discovery" -> {
                val clean = handleOrUrl.replace("@", "").trim()
                "https://x.com/$clean"
            }
            else -> "https://x.com"
        }
        openUrlIntent(context, targetUrl, "Twitter / X")
    }

    fun dispatchGitHubDirect(context: Context, usernameOrUrl: String) {
        val targetUrl = when {
            usernameOrUrl.contains("github.com", ignoreCase = true) -> usernameOrUrl
            usernameOrUrl.isNotBlank() && usernameOrUrl != "Pending Discovery" -> {
                val clean = usernameOrUrl.replace("@", "").trim()
                "https://github.com/$clean"
            }
            else -> "https://github.com"
        }
        openUrlIntent(context, targetUrl, "GitHub")
    }

    fun dispatchTelegramDirect(context: Context, usernameOrPhone: String, message: String = "") {
        val clean = usernameOrPhone.replace("@", "").replace("+", "").trim()
        val encodedMsg = Uri.encode(message)
        val targetUrl = when {
            usernameOrPhone.contains("t.me", ignoreCase = true) -> usernameOrPhone
            clean.isNotBlank() && clean != "Pending Discovery" -> {
                if (encodedMsg.isNotBlank()) "https://t.me/$clean?text=$encodedMsg" else "https://t.me/$clean"
            }
            else -> "https://t.me"
        }
        openUrlIntent(context, targetUrl, "Telegram")
    }

    fun dispatchDiscordDirect(context: Context, inviteOrUser: String) {
        val targetUrl = when {
            inviteOrUser.contains("discord.gg", ignoreCase = true) || inviteOrUser.contains("discord.com", ignoreCase = true) -> inviteOrUser
            inviteOrUser.isNotBlank() && inviteOrUser != "Pending Discovery" -> "https://discord.com/users/$inviteOrUser"
            else -> "https://discord.com"
        }
        openUrlIntent(context, targetUrl, "Discord")
    }

    fun dispatchYouTubeDirect(context: Context, channelOrUrl: String) {
        val targetUrl = when {
            channelOrUrl.contains("youtube.com", ignoreCase = true) || channelOrUrl.contains("youtu.be", ignoreCase = true) -> channelOrUrl
            channelOrUrl.isNotBlank() && channelOrUrl != "Pending Discovery" -> "https://www.youtube.com/$channelOrUrl"
            else -> "https://www.youtube.com"
        }
        openUrlIntent(context, targetUrl, "YouTube")
    }

    fun dispatchTikTokDirect(context: Context, handleOrUrl: String) {
        val targetUrl = when {
            handleOrUrl.contains("tiktok.com", ignoreCase = true) -> handleOrUrl
            handleOrUrl.isNotBlank() && handleOrUrl != "Pending Discovery" -> {
                val clean = if (handleOrUrl.startsWith("@")) handleOrUrl else "@$handleOrUrl"
                "https://www.tiktok.com/$clean"
            }
            else -> "https://www.tiktok.com"
        }
        openUrlIntent(context, targetUrl, "TikTok")
    }

    fun dispatchRedditDirect(context: Context, userOrSubreddit: String) {
        val targetUrl = when {
            userOrSubreddit.contains("reddit.com", ignoreCase = true) -> userOrSubreddit
            userOrSubreddit.startsWith("u/") || userOrSubreddit.startsWith("r/") -> "https://www.reddit.com/$userOrSubreddit"
            userOrSubreddit.isNotBlank() && userOrSubreddit != "Pending Discovery" -> "https://www.reddit.com/user/$userOrSubreddit"
            else -> "https://www.reddit.com"
        }
        openUrlIntent(context, targetUrl, "Reddit")
    }

    private fun openUrlIntent(context: Context, url: String, platformName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching $platformName intent: $url", e)
            Toast.makeText(context, "Unable to open $platformName", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Dynamically constructs the list of available, customized outreach and intelligence channels
     * for a lead item based on detected contact points, social profiles, and online presence.
     */
    fun getDynamicActionChannels(lead: LeadItemEntity): List<ClientActionChannel> {
        val channels = mutableListOf<ClientActionChannel>()
        val fullText = "${lead.title} ${lead.description} ${lead.link}"
        val pitch = lead.draftedPitch.ifBlank { "Hello, I noticed your business opportunity: ${lead.title} and would love to collaborate." }
        val subject = "Collaboration Inquiry: ${lead.title}"

        // 1. WhatsApp Channel
        val phone = extractPhone(fullText)
        if (phone != "Pending Discovery" && phone.isNotBlank()) {
            channels.add(
                ClientActionChannel(
                    id = "whatsapp",
                    platformName = "WhatsApp",
                    label = "WhatsApp ($phone)",
                    iconType = "whatsapp",
                    targetData = phone,
                    isPrimary = true,
                    colorHex = 0xFF25D366,
                    onDispatch = { ctx -> dispatchWhatsAppDirect(ctx, phone, pitch) }
                )
            )
            // 2. Call / Phone Channel
            channels.add(
                ClientActionChannel(
                    id = "phone",
                    platformName = "Call",
                    label = "Call ($phone)",
                    iconType = "phone",
                    targetData = phone,
                    isPrimary = false,
                    colorHex = 0xFF3B82F6,
                    onDispatch = { ctx -> dispatchCallDirect(ctx, phone) }
                )
            )
            // 3. SMS Channel
            channels.add(
                ClientActionChannel(
                    id = "sms",
                    platformName = "SMS",
                    label = "SMS ($phone)",
                    iconType = "sms",
                    targetData = phone,
                    isPrimary = false,
                    colorHex = 0xFF10B981,
                    onDispatch = { ctx -> dispatchSmsDirect(ctx, phone, pitch) }
                )
            )
        } else {
            // General WhatsApp Pitch Dispatcher
            channels.add(
                ClientActionChannel(
                    id = "whatsapp_general",
                    platformName = "WhatsApp",
                    label = "WhatsApp Pitch",
                    iconType = "whatsapp",
                    targetData = "",
                    isPrimary = true,
                    colorHex = 0xFF25D366,
                    onDispatch = { ctx -> dispatchViaWhatsApp(ctx, pitch) }
                )
            )
        }

        // 4. Email Channel
        val email = if (lead.clientEmail.isNotBlank() && lead.clientEmail != "Pending Discovery") {
            lead.clientEmail
        } else {
            extractEmail(fullText)
        }
        if (email != "Pending Discovery" && email.isNotBlank()) {
            channels.add(
                ClientActionChannel(
                    id = "email",
                    platformName = "Email",
                    label = "Email ($email)",
                    iconType = "email",
                    targetData = email,
                    isPrimary = true,
                    colorHex = 0xFFEA4335,
                    onDispatch = { ctx -> dispatchEmailDirect(ctx, email, subject, pitch) }
                )
            )
        } else {
            channels.add(
                ClientActionChannel(
                    id = "email_general",
                    platformName = "Email",
                    label = "Draft Email",
                    iconType = "email",
                    targetData = "",
                    isPrimary = false,
                    colorHex = 0xFFEA4335,
                    onDispatch = { ctx -> dispatchViaEmail(ctx, subject, pitch, "") }
                )
            )
        }

        // 5. LinkedIn Channel
        val linkedInUrl = extractLinkedInUrl(fullText)
        val companyName = extractCompanyName(lead.title, lead.description)
        channels.add(
            ClientActionChannel(
                id = "linkedin",
                platformName = "LinkedIn",
                label = if (companyName.isNotBlank() && companyName != "Pending Discovery") "LinkedIn ($companyName)" else "LinkedIn",
                iconType = "linkedin",
                targetData = linkedInUrl.ifBlank { companyName },
                isPrimary = false,
                colorHex = 0xFF0A66C2,
                onDispatch = { ctx -> dispatchLinkedInDirect(ctx, companyName, linkedInUrl) }
            )
        )

        // 6. Facebook Channel (if detected in text)
        val fbMatch = Regex("https?://(?:www\\.)?facebook\\.com/[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE).find(fullText)
        if (fbMatch != null) {
            channels.add(
                ClientActionChannel(
                    id = "facebook",
                    platformName = "Facebook",
                    label = "Facebook",
                    iconType = "facebook",
                    targetData = fbMatch.value,
                    isPrimary = false,
                    colorHex = 0xFF1877F2,
                    onDispatch = { ctx -> dispatchFacebookDirect(ctx, fbMatch.value) }
                )
            )
        }

        // 7. Instagram Channel (if detected)
        val igMatch = Regex("https?://(?:www\\.)?instagram\\.com/[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE).find(fullText)
        if (igMatch != null) {
            channels.add(
                ClientActionChannel(
                    id = "instagram",
                    platformName = "Instagram",
                    label = "Instagram",
                    iconType = "instagram",
                    targetData = igMatch.value,
                    isPrimary = false,
                    colorHex = 0xFFE4405F,
                    onDispatch = { ctx -> dispatchInstagramDirect(ctx, igMatch.value) }
                )
            )
        }

        // 8. Twitter / X Channel
        val twMatch = Regex("https?://(?:www\\.)?(?:twitter\\.com|x\\.com)/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE).find(fullText)
        if (twMatch != null) {
            channels.add(
                ClientActionChannel(
                    id = "twitter",
                    platformName = "X / Twitter",
                    label = "X (Twitter)",
                    iconType = "twitter",
                    targetData = twMatch.value,
                    isPrimary = false,
                    colorHex = 0xFF1DA1F2,
                    onDispatch = { ctx -> dispatchTwitterDirect(ctx, twMatch.value) }
                )
            )
        }

        // 9. Telegram Channel
        val tgMatch = Regex("https?://(?:www\\.)?t\\.me/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE).find(fullText)
        if (tgMatch != null) {
            channels.add(
                ClientActionChannel(
                    id = "telegram",
                    platformName = "Telegram",
                    label = "Telegram",
                    iconType = "telegram",
                    targetData = tgMatch.value,
                    isPrimary = false,
                    colorHex = 0xFF229ED9,
                    onDispatch = { ctx -> dispatchTelegramDirect(ctx, tgMatch.value, pitch) }
                )
            )
        }

        // 10. GitHub Channel
        val ghMatch = Regex("https?://(?:www\\.)?github\\.com/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE).find(fullText)
        if (ghMatch != null) {
            channels.add(
                ClientActionChannel(
                    id = "github",
                    platformName = "GitHub",
                    label = "GitHub",
                    iconType = "github",
                    targetData = ghMatch.value,
                    isPrimary = false,
                    colorHex = 0xFF333333,
                    onDispatch = { ctx -> dispatchGitHubDirect(ctx, ghMatch.value) }
                )
            )
        }

        // 11. Website / Portfolio link
        if (lead.link.startsWith("http://") || lead.link.startsWith("https://")) {
            channels.add(
                ClientActionChannel(
                    id = "website",
                    platformName = "Website",
                    label = "Visit Link",
                    iconType = "web",
                    targetData = lead.link,
                    isPrimary = false,
                    colorHex = 0xFF8B5CF6,
                    onDispatch = { ctx -> dispatchWebsiteDirect(ctx, lead.link) }
                )
            )
        }

        return channels
    }

    /**
     * Dynamically constructs the list of outreach channels for a ProspectEntity.
     */
    fun getDynamicActionChannels(prospect: com.example.data.db.ProspectEntity): List<ClientActionChannel> {
        val channels = mutableListOf<ClientActionChannel>()
        val pitch = prospect.aiDraftedMessage.ifBlank { prospect.draftedPitch.ifBlank { "Hello ${prospect.clientName}, I am reaching out regarding ${prospect.opportunityNature}." } }
        val subject = "Inquiry regarding ${prospect.opportunityNature} - ${prospect.companyName}"

        // WhatsApp
        val wa = prospect.whatsappNumber.ifBlank { prospect.phone }
        if (wa.isNotBlank() && wa != "Pending Discovery") {
            channels.add(
                ClientActionChannel(
                    id = "whatsapp",
                    platformName = "WhatsApp",
                    label = "WhatsApp ($wa)",
                    iconType = "whatsapp",
                    targetData = wa,
                    isPrimary = true,
                    colorHex = 0xFF25D366,
                    onDispatch = { ctx -> dispatchWhatsAppDirect(ctx, wa, pitch) }
                )
            )
        }

        // Phone / Call
        if (prospect.phone.isNotBlank() && prospect.phone != "Pending Discovery") {
            channels.add(
                ClientActionChannel(
                    id = "phone",
                    platformName = "Call",
                    label = "Call (${prospect.phone})",
                    iconType = "phone",
                    targetData = prospect.phone,
                    isPrimary = false,
                    colorHex = 0xFF3B82F6,
                    onDispatch = { ctx -> dispatchCallDirect(ctx, prospect.phone) }
                )
            )
            channels.add(
                ClientActionChannel(
                    id = "sms",
                    platformName = "SMS",
                    label = "SMS (${prospect.phone})",
                    iconType = "sms",
                    targetData = prospect.phone,
                    isPrimary = false,
                    colorHex = 0xFF10B981,
                    onDispatch = { ctx -> dispatchSmsDirect(ctx, prospect.phone, pitch) }
                )
            )
        }

        // Email
        val email = prospect.email.ifBlank { prospect.clientEmail }
        if (email.isNotBlank() && email != "Pending Discovery") {
            channels.add(
                ClientActionChannel(
                    id = "email",
                    platformName = "Email",
                    label = "Email ($email)",
                    iconType = "email",
                    targetData = email,
                    isPrimary = true,
                    colorHex = 0xFFEA4335,
                    onDispatch = { ctx -> dispatchEmailDirect(ctx, email, subject, pitch) }
                )
            )
        }

        // LinkedIn
        val companyOrClient = prospect.companyName.ifBlank { prospect.clientName }
        if (companyOrClient.isNotBlank() && companyOrClient != "Pending Discovery") {
            channels.add(
                ClientActionChannel(
                    id = "linkedin",
                    platformName = "LinkedIn",
                    label = "LinkedIn ($companyOrClient)",
                    iconType = "linkedin",
                    targetData = companyOrClient,
                    isPrimary = false,
                    colorHex = 0xFF0A66C2,
                    onDispatch = { ctx -> dispatchLinkedInDirect(ctx, companyOrClient) }
                )
            )
        }

        // Website
        if (prospect.websiteUrl.isNotBlank() && prospect.websiteUrl != "Pending Discovery") {
            channels.add(
                ClientActionChannel(
                    id = "website",
                    platformName = "Website",
                    label = "Website",
                    iconType = "web",
                    targetData = prospect.websiteUrl,
                    isPrimary = false,
                    colorHex = 0xFF8B5CF6,
                    onDispatch = { ctx -> dispatchWebsiteDirect(ctx, prospect.websiteUrl) }
                )
            )
        }

        return channels
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

    suspend fun streamAllLeads(
        batchSize: Int = 100,
        onBatch: suspend (List<LeadEntity>) -> Unit
    ) = withContext(Dispatchers.IO) {
        LargeDatasetEngine.processInBatches(
            pageSize = batchSize,
            fetchPage = { limit, offset -> requireDatabase().leadDao().getPagedLeads(limit, offset) },
            onBatchProcessed = { chunk -> onBatch(chunk.items) }
        )
    }

    suspend fun streamAllProspects(
        batchSize: Int = 100,
        onBatch: suspend (List<ProspectEntity>) -> Unit
    ) = withContext(Dispatchers.IO) {
        LargeDatasetEngine.processInBatches(
            pageSize = batchSize,
            fetchPage = { limit, offset -> requireDatabase().prospectDao().getPagedProspects(limit, offset) },
            onBatchProcessed = { chunk -> onBatch(chunk.items) }
        )
    }

    suspend fun getLeadsCount(): Int = withContext(Dispatchers.IO) {
        requireDatabase().leadDao().getLeadsCount()
    }

    suspend fun getProspectsCount(): Int = withContext(Dispatchers.IO) {
        requireDatabase().prospectDao().getProspectsCount()
    }
}
