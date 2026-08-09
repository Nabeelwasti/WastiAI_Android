package com.example.data.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.example.data.db.LeadEntity
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

    private val _lastSearchQuery = MutableStateFlow("Video Editing & Graphic Design")
    val lastSearchQuery: StateFlow<String> = _lastSearchQuery.asStateFlow()

    private var isDbInitialized = false

    fun initDatabase(context: Context) {
        if (isDbInitialized) return
        isDbInitialized = true

        scope.launch {
            val db = WastiDatabase.getDatabase(context)
            val dao = db.leadDao()

            // Observe Room DB updates reactively
            launch {
                dao.getAllLeads().collect { dbLeads ->
                    _leadsFlow.value = dbLeads.map { it.toUiModel() }
                }
            }
        }
    }

    suspend fun scanAndEvaluateLeads(context: Context, query: String): List<LeadItemEntity> = withContext(Dispatchers.IO) {
        initDatabase(context)
        _lastSearchQuery.value = query
        val rawLeads = LeadScraperEngine.fetchLeadsForQuery(query)
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
