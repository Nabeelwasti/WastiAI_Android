package com.example.data.api

import com.example.data.credential.CredentialRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class StripeChargeRequest(
    val amountCents: Long,
    val currency: String = "usd",
    val description: String,
    val customerEmail: String
)

data class StripeChargeResult(
    val success: Boolean,
    val chargeId: String?,
    val message: String
)

object StripeWorkerService {
    // Cloudflare Worker proxy endpoint holding STRIPE_SECRET_KEY server-side
    private const val CLOUDFLARE_WORKER_URL = "https://wasti-stripe-proxy.workers.dev/v1/charge"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    suspend fun executeServerSideCharge(
        amountCents: Long,
        description: String,
        customerEmail: String
    ): StripeChargeResult = withContext(Dispatchers.IO) {
        val cloudflareKey = CredentialRegistry.getRawValue("CLOUDFLARE_API_KEY")
        val publishableKey = CredentialRegistry.getRawValue("STRIPE_PUBLISHABLE_KEY")

        if (cloudflareKey.isNullOrBlank() || CredentialRegistry.isPlaceholder(cloudflareKey)) {
            return@withContext StripeChargeResult(
                success = false,
                chargeId = null,
                message = "Cloudflare API Token not configured for Stripe Server-Side Worker."
            )
        }

        val jsonPayload = """
            {
                "amount": $amountCents,
                "currency": "usd",
                "description": "$description",
                "customer_email": "$customerEmail",
                "client_publishable_key": "${publishableKey.orEmpty()}"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(CLOUDFLARE_WORKER_URL)
            .addHeader("Authorization", "Bearer $cloudflareKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                // Parse genuine charge result via JSON validation
                var authoritativeId: String? = null
                var isExplicitlySucceeded = false
                try {
                    val json = org.json.JSONObject(bodyStr)
                    val id = json.optString("id", "")
                    val status = json.optString("status", "")
                    val paid = json.optBoolean("paid", false)
                    val successFlag = json.optBoolean("success", false)

                    if (id.startsWith("ch_") || id.startsWith("pi_") || id.startsWith("in_") || id.startsWith("txn_")) {
                        authoritativeId = id
                    }

                    if (status.equals("succeeded", ignoreCase = true) || paid || (successFlag && authoritativeId != null)) {
                        isExplicitlySucceeded = true
                    }
                } catch (_: Exception) {
                    // Fallback to strict regex only if valid JSON object parsing fails
                    val chargeIdMatch = Regex("\"id\"\\s*:\\s*\"(ch_[a-zA-Z0-9]+|pi_[a-zA-Z0-9]+|txn_[a-zA-Z0-9]+)\"").find(bodyStr)?.groupValues?.get(1)
                    if (chargeIdMatch != null && (bodyStr.contains("\"status\":\"succeeded\"") || bodyStr.contains("\"paid\":true"))) {
                        authoritativeId = chargeIdMatch
                        isExplicitlySucceeded = true
                    }
                }

                if (isExplicitlySucceeded && authoritativeId != null) {
                    StripeChargeResult(
                        success = true,
                        chargeId = authoritativeId,
                        message = "Success: Charged $$amountCents via Cloudflare Server-Side Worker Proxy."
                    )
                } else if (authoritativeId == null && isExplicitlySucceeded) {
                    StripeChargeResult(
                        success = false,
                        chargeId = null,
                        message = "PAYMENT_STATUS_UNKNOWN: Response succeeded but lacked authoritative Stripe charge identifier."
                    )
                } else {
                    StripeChargeResult(
                        success = false,
                        chargeId = null,
                        message = "Stripe charge declined or unsuccessful: ${bodyStr.take(120)}"
                    )
                }
            } else {
                StripeChargeResult(
                    success = false,
                    chargeId = null,
                    message = "Stripe Cloudflare Worker failed with HTTP ${response.code}: ${bodyStr.take(100)}"
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            StripeChargeResult(
                success = false,
                chargeId = null,
                message = "Stripe charge network error: ${e.message ?: e.toString()}"
            )
        }
    }
}
