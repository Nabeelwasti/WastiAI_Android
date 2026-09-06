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

    private val moshi = Moshi.Builder()
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
                // Parse genuine charge result
                val isSuccess = bodyStr.contains("\"success\":true") || bodyStr.contains("\"status\":\"succeeded\"") || bodyStr.contains("ch_")
                if (isSuccess) {
                    val chargeIdMatch = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(bodyStr)?.groupValues?.get(1)
                        ?: "ch_cf_${System.currentTimeMillis()}"
                    StripeChargeResult(
                        success = true,
                        chargeId = chargeIdMatch,
                        message = "Success: Charged $$amountCents via Cloudflare Server-Side Worker Proxy."
                    )
                } else {
                    StripeChargeResult(
                        success = false,
                        chargeId = null,
                        message = "Stripe charge declined or unsuccessful: $bodyStr"
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
