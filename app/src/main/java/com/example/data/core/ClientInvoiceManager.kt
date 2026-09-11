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
    val currency: String = "USD",
    var status: InvoiceStatus = InvoiceStatus.DRAFT,
    val issueDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val dueDate: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(System.currentTimeMillis() + 864000000L)), // 10 days
    val clientFeedback: String? = null
)

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

            // Purge legacy seeded fake invoice data if present
            try {
                val existing = dao.getAllInvoicesSync()
                existing.filter {
                    it.clientName == "ClientCorp Media Studio" ||
                    it.clientName == "BuildTech Architectural Agency" ||
                    it.clientName == "BrandStudio Global"
                }.forEach { fakeInvoice ->
                    dao.deleteInvoiceById(fakeInvoice.id)
                }
            } catch (e: Exception) {
                Log.e("ClientInvoiceManager", "Error clearing legacy fake invoices", e)
            }

            launch {
                dao.getAllInvoices().collect { dbInvoices ->
                    _invoicesFlow.value = dbInvoices.map { it.toUiModel() }
                }
            }
        }
    }

    fun createInvoice(context: Context, clientName: String, milestone: String, amountUsd: Double, currency: String = "USD"): ClientInvoiceItem {
        initDatabase(context)
        val newInvoice = ClientInvoiceItem(
            clientName = clientName,
            projectMilestone = milestone,
            amountUsd = amountUsd,
            currency = currency,
            status = InvoiceStatus.INVOICED
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
            currency = currency,
            status = InvoiceStatus.INVOICED
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

    fun calculateTotalRevenueUsd(): Double {
        return _invoicesFlow.value.sumOf { it.amountUsd }
    }

    fun calculateTotalPaidUsd(): Double {
        return _invoicesFlow.value.filter { it.status == InvoiceStatus.PAID }.sumOf { it.amountUsd }
    }

    fun calculateTotalPendingUsd(): Double {
        return _invoicesFlow.value.filter { it.status != InvoiceStatus.PAID }.sumOf { it.amountUsd }
    }

    private fun ClientInvoiceItem.toRoomEntity(): InvoiceEntity {
        return InvoiceEntity(
            id = id,
            clientName = clientName,
            projectMilestone = projectMilestone,
            amountUsd = amountUsd,
            currency = currency,
            status = status.name,
            issueDate = issueDate,
            dueDate = dueDate,
            clientFeedback = clientFeedback
        )
    }

    private fun InvoiceEntity.toUiModel(): ClientInvoiceItem {
        return ClientInvoiceItem(
            id = id,
            clientName = clientName,
            projectMilestone = projectMilestone,
            amountUsd = amountUsd,
            currency = currency ?: "USD",
            status = try { InvoiceStatus.valueOf(status) } catch (_: Exception) { InvoiceStatus.DRAFT },
            issueDate = issueDate,
            dueDate = dueDate,
            clientFeedback = clientFeedback
        )
    }

    private val fallbackRates = mapOf(
        "USD" to 1.0,
        "EUR" to 1.09,
        "GBP" to 1.28,
        "PKR" to 0.0036, // ~278 PKR = 1 USD
        "AUD" to 0.65,
        "CAD" to 0.73,
        "INR" to 0.012
    )

    /**
     * Live currency conversion rates using api.exchangerate-api.com / open.er-api.com
     */
    suspend fun getLiveExchangeRate(base: String, target: String): Double = withContext(Dispatchers.IO) {
        val baseUpper = base.uppercase()
        val targetUpper = target.uppercase()
        if (baseUpper == targetUpper) return@withContext 1.0

        try {
            val url = "https://open.er-api.com/v6/latest/$baseUpper"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val ratesObj = json.optJSONObject("rates")
                    if (ratesObj != null && ratesObj.has(targetUpper)) {
                        val rate = ratesObj.getDouble(targetUpper)
                        Log.i("ClientInvoiceManager", "Live exchange rate fetched: 1 $baseUpper = $rate $targetUpper")
                        return@withContext rate
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ClientInvoiceManager", "Live exchange rate API fetch failed ($baseUpper -> $targetUpper): ${e.message}")
        }

        // Fallback computation using static rates table
        val baseInUsd = fallbackRates[baseUpper] ?: 1.0
        val targetInUsd = fallbackRates[targetUpper] ?: 1.0
        val fallbackRate = baseInUsd / targetInUsd
        Log.i("ClientInvoiceManager", "Using fallback exchange rate: 1 $baseUpper = $fallbackRate $targetUpper")
        fallbackRate
    }

    /**
     * Converts any currency amount to USD equivalent.
     */
    suspend fun convertToUsd(amount: Double, currency: String): Double {
        if (currency.equals("USD", ignoreCase = true)) return amount
        val rate = getLiveExchangeRate(currency, "USD")
        return amount * rate
    }

    fun formatCurrencyAmount(amount: Double, currency: String): String {
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            "PKR" -> "₨"
            "AUD" -> "A$"
            else -> currency
        }
        val formattedNumber = if (amount % 1.0 == 0.0 && amount >= 1000) {
            String.format(Locale.US, "%,.0f", amount)
        } else {
            String.format(Locale.US, "%,.2f", amount)
        }
        return "$symbol $formattedNumber"
    }

    fun generateInvoiceText(invoice: ClientInvoiceItem): String {
        return """
            =========================================
            WASTI AI CLIENT INVOICE & PAYMENT LEDGER
            Invoice ID: ${invoice.id.take(8).uppercase()}
            Date: ${invoice.issueDate}
            Payment Due Date: ${invoice.dueDate}
            =========================================

            CLIENT: ${invoice.clientName}
            PROJECT MILESTONE:
            ${invoice.projectMilestone}

            -----------------------------------------
            TOTAL AMOUNT DUE: ${formatCurrencyAmount(invoice.amountUsd, invoice.currency)}
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

    private const val PREFS_STRIPE_EVENTS = "wasti_stripe_events"

    fun isEventProcessed(context: Context, eventId: String): Boolean {
        if (eventId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_STRIPE_EVENTS, Context.MODE_PRIVATE)
        return prefs.contains(eventId)
    }

    fun markEventProcessed(context: Context, eventId: String, invoiceId: String? = null) {
        if (eventId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_STRIPE_EVENTS, Context.MODE_PRIVATE)
        prefs.edit().putLong(eventId, System.currentTimeMillis()).apply()
    }

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
                                val eventId = event.optString("id", "")
                                if (eventId.isNotBlank() && isEventProcessed(context, eventId)) {
                                    continue
                                }
                                val dataObj = event.optJSONObject("data")?.optJSONObject("object")
                                val amountReceived = dataObj?.optDouble("amount_received", 0.0) ?: 0.0
                                val amountUsd = amountReceived / 100.0
                                val metadata = dataObj?.optJSONObject("metadata")
                                val targetInvoiceId = metadata?.optString("invoice_id") ?: metadata?.optString("invoiceId")

                                // Match against open invoices
                                val db = WastiDatabase.getDatabase(context)
                                val currentInvoices = db.invoiceDao().getAllInvoicesSync()
                                val openInvoices = currentInvoices.filter { it.status != InvoiceStatus.PAID.name }

                                val matchedInvoice = if (!targetInvoiceId.isNullOrBlank()) {
                                    openInvoices.firstOrNull { it.id == targetInvoiceId }
                                } else {
                                    openInvoices.firstOrNull { Math.abs(it.amountUsd - amountUsd) < 1.0 }
                                }

                                if (matchedInvoice != null) {
                                    db.invoiceDao().updateInvoiceStatus(matchedInvoice.id, InvoiceStatus.PAID.name)
                                    if (eventId.isNotBlank()) {
                                        markEventProcessed(context, eventId, matchedInvoice.id)
                                    }
                                    syncedCount++
                                    WastiNotificationManager.sendVoiceAlertNotification(
                                        context,
                                        "Stripe Payment Received!",
                                        "Payment of \$${matchedInvoice.amountUsd} USD received from ${matchedInvoice.clientName}."
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ClientInvoiceManager", "Error connecting to Stripe API: ${e.message}")
            }
        }

        // Truthful return: never fabricate paid invoices when no payment arrived
        syncedCount
    }

    /**
     * Processes incoming Stripe Webhook JSON event callbacks (e.g. charge.succeeded, payment_intent.succeeded).
     * Strictly verifies event ID idempotency to prevent duplicate replay attacks.
     */
    suspend fun handleStripeWebhookEvent(context: Context, payloadJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            initDatabase(context)
            val json = JSONObject(payloadJson)
            val eventId = json.optString("id", "")
            if (eventId.isBlank()) {
                Log.w("ClientInvoiceManager", "Stripe webhook payload rejected: missing event ID.")
                return@withContext false
            }

            if (isEventProcessed(context, eventId)) {
                Log.w("ClientInvoiceManager", "Stripe webhook replay rejected: event $eventId already processed.")
                return@withContext false
            }

            val eventType = json.optString("type", "")
            if (eventType == "payment_intent.succeeded" || eventType == "charge.succeeded") {
                val dataObj = json.optJSONObject("data")?.optJSONObject("object")
                val amountReceived = dataObj?.optDouble("amount_received", 0.0)
                    ?: dataObj?.optDouble("amount", 0.0)
                    ?: 0.0
                val amountUsd = amountReceived / 100.0
                val metadata = dataObj?.optJSONObject("metadata")
                val targetInvoiceId = metadata?.optString("invoice_id") ?: metadata?.optString("invoiceId")

                val db = WastiDatabase.getDatabase(context)
                val openInvoices = db.invoiceDao().getAllInvoicesSync().filter { it.status != InvoiceStatus.PAID.name }

                val matchedInvoice = if (!targetInvoiceId.isNullOrBlank()) {
                    openInvoices.firstOrNull { it.id == targetInvoiceId }
                } else if (amountUsd > 0.0) {
                    openInvoices.firstOrNull { Math.abs(it.amountUsd - amountUsd) < 1.0 }
                } else null

                if (matchedInvoice != null) {
                    db.invoiceDao().updateInvoiceStatus(matchedInvoice.id, InvoiceStatus.PAID.name)
                    markEventProcessed(context, eventId, matchedInvoice.id)
                    WastiNotificationManager.sendVoiceAlertNotification(
                        context,
                        "Stripe Webhook Event Verified",
                        "Automated Payment Callback: Invoice \$${matchedInvoice.amountUsd} USD marked as PAID for ${matchedInvoice.clientName}."
                    )
                    return@withContext true
                } else {
                    Log.w("ClientInvoiceManager", "Stripe webhook received for event $eventId but no matching open invoice was found.")
                }
            }
        } catch (e: Exception) {
            Log.e("ClientInvoiceManager", "Error parsing Stripe Webhook payload", e)
        }
        false
    }
}
