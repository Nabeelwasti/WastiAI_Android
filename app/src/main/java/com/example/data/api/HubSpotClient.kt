package com.example.data.api

import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HubSpotClient {
    private const val BASE_URL = "https://api.hubapi.com/crm/v3/objects"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun syncStripeQuoteToHubSpotDeal(
        quoteId: String,
        amountDollars: Double,
        clientEmail: String
    ): Boolean = withContext(Dispatchers.IO) {
        val connId = CredentialRegistry.getRawValue("HUBSPOT_CONNECTION_ID")
        if (connId.isNullOrBlank() || CredentialRegistry.isPlaceholder(connId)) return@withContext false

        val dealJson = JSONObject().apply {
            put("properties", JSONObject().apply {
                put("dealname", "Wasti OS Quote $quoteId ($clientEmail)")
                put("amount", amountDollars.toString())
                put("pipeline", "default")
                put("dealstage", "contractsent")
            })
        }.toString()

        val request = Request.Builder()
            .url("$BASE_URL/deals")
            .addHeader("Authorization", "Bearer $connId")
            .addHeader("Content-Type", "application/json")
            .post(dealJson.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}
