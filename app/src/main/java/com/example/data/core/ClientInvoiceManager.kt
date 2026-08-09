package com.example.data.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.data.credential.CredentialRegistry
import com.example.data.db.InvoiceEntity
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class InvoiceStatus {
    DRAFT,
    INVOICED,
    PENDING_PAYMENT,
    PAID
}

data class ClientInvoiceItem(
    val id: String = UUID.randomUUID().toString(),
    val clientName: String,
    val projectMilestone: String,
    val amountUsd: Double,
    var status: InvoiceStatus = InvoiceStatus.DRAFT,
    val issueDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val dueDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(System.currentTimeMillis() + 864000000L)), // 10 days
    val currency: String = "USD",
    val isSynthetic: Boolean = false
)

/**
 * Supported invoice currencies with display symbols. amountUsd is always the reference
 * amount for internal math (Stripe matching, totals) — currency only affects display
 * formatting on the generated invoice text, not the stored numeric value semantics.
 * If true multi-currency amounts (not just display) are needed later, add a separate
 * conversion step here rather than mutating amountUsd's meaning.
 */
object CurrencyFormatter {
    private val symbols = mapOf(
        "USD" to "$",
        "PKR" to "Rs ",
        "GBP" to "£",
        "EUR" to "€",
        "AED" to "AED ",
        "SAR" to "SAR ",
        "INR" to "₹",
        "CAD" to "C$",
        "AUD" to "A$"
    )

    fun format(amount: Double, currency: String): String {
        val symbol = symbols[currency.uppercase()] ?: "$currency "
        return "$symbol${String.format(Locale.US, "%.2f", amount)}"
    }

    fun supportedCurrencies(): List<String> = symbols.keys.toList()
}

object ClientInvoiceManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _invoicesFlow = MutableStateFlow<List<ClientInvoiceItem>>(emptyList())
    val invoicesFlow: StateFlow<List<ClientInvoiceItem>> = _invoicesFlow.asStateFlow()

    private var isDbInitialized = false

    fun initDatabase(context: Context) {
        if (isDbInitialized) return
        isDbInitialized = true

        scope.launch {
            val db = WastiDatabase.getDatabase(context)
            val dao = db.invoiceDao()

            launch {
                dao.getAllInvoices().collect { dbInvoices ->
                    if (dbInvoices.isEmpty()) {
                        populateInitialInvoicesInDb(context)
                    } else {
                        _invoicesFlow.value = dbInvoices.map { it.toUiModel() }
                    }
                }
            }
        }
    }

    private suspend fun populateInitialInvoicesInDb(context: Context) {
        val db = WastiDatabase.getDatabase(context)
        val dao = db.invoiceDao()

        val defaultInvoices = listOf(
            ClientInvoiceItem(
                clientName = "[Sample] ClientCorp Media Studio",
                projectMilestone = "Example: Milestone 1 — 10 Short-Form Reels & YouTube Shorts Production",
                amountUsd = 450.0,
                status = InvoiceStatus.DRAFT,
                isSynthetic = true
            ),
            ClientInvoiceItem(
                clientName = "[Sample] BuildTech Architectural Agency",
                projectMilestone = "Example: Milestone 2 — 2D Floor Plan DWG Conversion & Layouts",
                amountUsd = 350.0,
                status = InvoiceStatus.DRAFT,
                isSynthetic = true
            ),
            ClientInvoiceItem(
                clientName = "[Sample] BrandStudio Global",
                projectMilestone = "Example: Complete Vector Logo Suite & CorelDRAW Brand Kit",
                amountUsd = 600.0,
                status = InvoiceStatus.DRAFT,
                isSynthetic = true
            )
        )

        defaultInvoices.forEach { invoice ->
            dao.insertInvoice(invoice.toRoomEntity())
        }
    }

    fun createInvoice(context: Context, clientName: String, milestone: String, amountUsd: Double, currency: String = "USD"): ClientInvoiceItem {
        initDatabase(context)
        val newInvoice = ClientInvoiceItem(
            clientName = clientName,
            projectMilestone = milestone,
            amountUsd = amountUsd,
            status = InvoiceStatus.INVOICED,
            currency = currency
        )
        scope.launch {
            val db = WastiDatabase.getDatabase(context)
            db.invoiceDao().insertInvoice(newInvoice.toRoomEntity())
        }
        return newInvoice
    }

    fun createInvoice(clientName: String, milestone: String, amountUsd: Double, currency: String = "USD"): ClientInvoiceItem {
        val newInvoice = ClientInvoiceItem(
            clientName = clientName,
            projectMilestone = milestone,
            amountUsd = amountUsd,
            status = InvoiceStatus.INVOICED,
            currency = currency
        )
        val current = _invoicesFlow.value.toMutableList()
        current.add(0, newInvoice)
        _invoicesFlow.value = current
        return newInvoice
    }

    fun updateStatus(context: Context, invoiceId: String, newStatus: InvoiceStatus) {
        scope.launch {
            initDatabase(context)
            val db = WastiDatabase.getDatabase(context)
            db.invoiceDao().updateInvoiceStatus(invoiceId, newStatus.name)
        }
    }

    fun updateStatus(invoiceId: String, newStatus: InvoiceStatus) {
        val updated = _invoicesFlow.value.map {
            if (it.id == invoiceId) it.copy(status = newStatus) else it
        }
        _invoicesFlow.value = updated
    }

    private fun ClientInvoiceItem.toRoomEntity(): InvoiceEntity {
        return InvoiceEntity(
            id = id,
            clientName = clientName,
            projectMilestone = projectMilestone,
            amountUsd = amountUsd,
            status = status.name,
            issueDate = issueDate,
            dueDate = dueDate,
            currency = currency,
            isSynthetic = isSynthetic
        )
    }

    private fun InvoiceEntity.toUiModel(): ClientInvoiceItem {
        return ClientInvoiceItem(
            id = id,
            clientName = clientName,
            projectMilestone = projectMilestone,
            amountUsd = amountUsd,
            status = try { InvoiceStatus.valueOf(status) } catch (_: Exception) { InvoiceStatus.DRAFT },
            issueDate = issueDate,
            dueDate = dueDate,
            currency = currency,
            isSynthetic = isSynthetic
        )
    }

    fun generateInvoiceText(invoice: ClientInvoiceItem): String {
        val demoNotice = if (invoice.isSynthetic) "\n            (SAMPLE DATA — not a real invoice)\n" else ""
        return """
            =========================================
            WASTI AI CLIENT INVOICE & PAYMENT LEDGER
            Invoice ID: ${invoice.id.take(8).uppercase()}
            Date: ${invoice.issueDate}
            Payment Due Date: ${invoice.dueDate}$demoNotice
            =========================================

            CLIENT: ${invoice.clientName}
            PROJECT MILESTONE:
            ${invoice.projectMilestone}

            -----------------------------------------
            TOTAL AMOUNT DUE: ${CurrencyFormatter.format(invoice.amountUsd, invoice.currency)} ${invoice.currency}
            STATUS: ${invoice.status.name}
            -----------------------------------------

            PAYMENT METHODS ACCEPTED:
            • Bank Transfer / IBAN / ACH
            • PayPal / Payoneer / Stripe Direct
            • Crypto (USDT / USDC)

            Thank you for your business!
            Wasti AI Autonomous Operating Systems
        """.trimIndent()
    }

    fun copyInvoiceToClipboard(context: Context, invoice: ClientInvoiceItem) {
        val text = generateInvoiceText(invoice)
        LeadRadarRepository.copyToClipboard(context, "Invoice ${invoice.id.take(8)}", text)
    }

    fun shareInvoiceViaEmail(context: Context, invoice: ClientInvoiceItem) {
        val subject = "Invoice ${invoice.id.take(8)} — ${invoice.projectMilestone}"
        val body = generateInvoiceText(invoice)
        LeadRadarRepository.dispatchViaEmail(context, subject, body)
    }

    private val httpClient = OkHttpClient()

    /**
     * Connects ClientInvoiceManager to Stripe API / Webhooks.
     * Polls Stripe events or verifies sandbox restricted key for payment intents.
     */
    suspend fun syncPaymentsWithStripe(context: Context): Int = withContext(Dispatchers.IO) {
        initDatabase(context)
        val stripeKey = CredentialRegistry.getRawValue("STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN")
            ?: CredentialRegistry.getRawValue("STRIPE_SECRET_KEY")

        var syncedCount = 0

        if (!stripeKey.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url("https://api.stripe.com/v1/events?limit=20&type=payment_intent.succeeded")
                    .addHeader("Authorization", "Bearer $stripeKey")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val dataArray = json.optJSONArray("data")
                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val event = dataArray.getJSONObject(i)
                                val dataObj = event.optJSONObject("data")?.optJSONObject("object")
                                val amountReceived = dataObj?.optDouble("amount_received", 0.0) ?: 0.0
                                val amountUsd = amountReceived / 100.0

                                // Match against open invoices
                                val db = WastiDatabase.getDatabase(context)
                                val currentInvoices = db.invoiceDao().getAllInvoicesSync()
                                currentInvoices.filter { it.status != InvoiceStatus.PAID.name }.forEach { inv ->
                                    if (Math.abs(inv.amountUsd - amountUsd) < 1.0) {
                                        db.invoiceDao().updateInvoiceStatus(inv.id, InvoiceStatus.PAID.name)
                                        syncedCount++
                                        WastiNotificationManager.sendVoiceAlertNotification(
                                            context,
                                            "Stripe Payment Received!",
                                            "Payment of \$${inv.amountUsd} USD received from ${inv.clientName}."
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ClientInvoiceManager", "Error connecting to Stripe API: ${e.message}")
            }
        }

        // Honest reporting only: never mark an invoice PAID without a real, matched Stripe
        // payment event above. A previous version of this function auto-marked the oldest
        // pending invoice as PAID whenever no real payment was found — that fabricated
        // financial state and could have told the user they were paid when they weren't.
        if (stripeKey.isNullOrBlank()) {
            Log.w("ClientInvoiceManager", "Stripe sync skipped: no STRIPE_SECRET_KEY or STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN configured.")
        }

        syncedCount
    }

    /**
     * Processes incoming Stripe Webhook JSON event callbacks (e.g. charge.succeeded).
     *
     * NOT YET WIRED TO ANY REAL ENTRY POINT (no caller in the codebase as of this fix) —
     * before connecting this to anything real, it needs two things it doesn't have yet:
     * 1. Stripe webhook signature verification (the `Stripe-Signature` header against your
     *    webhook signing secret) — right now any JSON matching this shape would be trusted.
     * 2. Match against the specific invoice the event refers to (by amount and/or a stored
     *    Stripe payment intent ID), not just "the first open invoice" — picking the first
     *    one can mark the wrong invoice paid if more than one is open.
     */
    suspend fun handleStripeWebhookEvent(context: Context, payloadJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            initDatabase(context)
            val json = JSONObject(payloadJson)
            val eventType = json.optString("type", "")
            if (eventType == "payment_intent.succeeded" || eventType == "charge.succeeded") {
                val db = WastiDatabase.getDatabase(context)
                val openInvoices = db.invoiceDao().getAllInvoicesSync().filter { it.status != InvoiceStatus.PAID.name }
                if (openInvoices.isNotEmpty()) {
                    val inv = openInvoices.first()
                    db.invoiceDao().updateInvoiceStatus(inv.id, InvoiceStatus.PAID.name)
                    WastiNotificationManager.sendVoiceAlertNotification(
                        context,
                        "Stripe Webhook Event Verified",
                        "Automated Payment Callback: Invoice \$${inv.amountUsd} USD marked as PAID for ${inv.clientName}."
                    )
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e("ClientInvoiceManager", "Error parsing Stripe Webhook payload", e)
        }
        false
    }
}
