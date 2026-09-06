package com.example.assistant.backend

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    private var baseUrl: String = ""

    private var lastRecordedPath: String? = null
    private var lastRecordedAuthHeader: String? = null
    private var lastRecordedTokenHeader: String? = null
    private var lastRecordedApprovalHeader: String? = null
    private var lastRecordedBody: String? = null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        baseUrl = "http://127.0.0.1:$port"

        server.createContext("/") { exchange ->
            lastRecordedPath = exchange.requestURI.path
            lastRecordedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastRecordedTokenHeader = exchange.requestHeaders.getFirst("x-wasti-auth-token")
            lastRecordedApprovalHeader = exchange.requestHeaders.getFirst("x-approval-token")
            lastRecordedBody = exchange.requestBody.bufferedReader().use { it.readText() }

            val responseBody = when (exchange.requestURI.path) {
                "/health" -> "{\"status\":\"ok\",\"timestamp\":1725660000000,\"githubConfigured\":true,\"brevoConfigured\":false,\"stripeConfigured\":true,\"firebaseConfigured\":false,\"authEnforced\":true}"
                "/llm" -> "{\"provider\":\"openai\",\"result\":\"mocked LLM answer\"}"
                "/dev/patch" -> "{\"prUrl\":\"https://github.com/test/pull/1\",\"branch\":\"wasti/patch-1\"}"
                "/wakeword" -> "{\"status\":\"accepted\"}"
                "/email/send" -> "{\"status\":\"sent\"}"
                else -> "{\"error\":\"not found\"}"
            }

            val statusCode = if (exchange.requestURI.path.contains("error")) 500 else 200
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun testCallLLM_withAuthToken() = runBlocking {
        val result = BackendClient.callLLM(
            baseUrl = baseUrl,
            provider = "openai",
            payloadJson = "{\"prompt\":\"Hello Wasti\"}",
            authToken = "secret-token-xyz"
        )

        assertNotNull(result)
        assertTrue(result!!.contains("mocked LLM answer"))
        assertEquals("/llm", lastRecordedPath)
        assertEquals("Bearer secret-token-xyz", lastRecordedAuthHeader)
        assertEquals("secret-token-xyz", lastRecordedTokenHeader)
        assertEquals("{\"prompt\":\"Hello Wasti\"}", lastRecordedBody)
    }

    @Test
    fun testCreateDevPatch_withAuthToken() = runBlocking {
        val result = BackendClient.createDevPatch(
            baseUrl = baseUrl,
            owner = "Nabeelwasti",
            repo = "WastiAI_Android",
            title = "Fix feature",
            bodyJson = "\"Automated fix description\"",
            changesJson = "[{\"path\":\"README.md\",\"content\":\"new content\"}]",
            authToken = "secret-token-xyz"
        )

        assertNotNull(result)
        assertTrue(result!!.contains("https://github.com/test/pull/1"))
        assertEquals("/dev/patch", lastRecordedPath)
        assertEquals("secret-token-xyz", lastRecordedTokenHeader)
        assertTrue(lastRecordedBody!!.contains("\"owner\":\"Nabeelwasti\""))
    }

    @Test
    fun testSendWakeword_withAuthToken() = runBlocking {
        val result = BackendClient.sendWakeword(
            baseUrl = baseUrl,
            eventPayloadJson = "{\"wakeWord\":\"hey wasti\"}",
            token = "fcm-device-token-123",
            authToken = "secret-token-xyz"
        )

        assertNotNull(result)
        assertTrue(result!!.contains("accepted"))
        assertEquals("/wakeword", lastRecordedPath)
        assertEquals("secret-token-xyz", lastRecordedTokenHeader)
        assertTrue(lastRecordedBody!!.contains("\"token\":\"fcm-device-token-123\""))
    }

    @Test
    fun testSendEmail_withApprovalAndAuthToken() = runBlocking {
        val result = BackendClient.sendEmail(
            baseUrl = baseUrl,
            to = "recipient@example.com",
            subject = "System Notification",
            html = "<p>Hello</p>",
            approvalToken = "approval-token-456",
            authToken = "secret-token-xyz"
        )

        assertNotNull(result)
        assertTrue(result!!.contains("sent"))
        assertEquals("/email/send", lastRecordedPath)
        assertEquals("approval-token-456", lastRecordedApprovalHeader)
        assertEquals("Bearer secret-token-xyz", lastRecordedAuthHeader)
        assertTrue(lastRecordedBody!!.contains("recipient@example.com"))
    }

    @Test
    fun testErrorHandling_returnsNullOnFailure() = runBlocking {
        val result = BackendClient.callLLM(
            baseUrl = "$baseUrl/error",
            provider = "openai",
            payloadJson = "{}"
        )
        assertNull(result)
    }

    @Test
    fun testCheckHealth_returnsReachableAndSubsystemStatus() = runBlocking {
        val health = BackendClient.checkHealth(baseUrl, authToken = "secret-token-xyz")
        assertTrue("Backend should be marked reachable", health.isReachable)
        assertEquals(200, health.httpCode)
        assertEquals("ok", health.status)
        assertTrue(health.latencyMs >= 0)
        assertTrue(health.githubConfigured)
        assertFalse(health.brevoConfigured)
        assertTrue(health.stripeConfigured)
        assertTrue(health.authEnforced)
        assertEquals("/health", lastRecordedPath)
        assertEquals("secret-token-xyz", lastRecordedTokenHeader)
    }

    @Test
    fun testCheckHealth_invalidUrl_failsClosed() = runBlocking {
        val health = BackendClient.checkHealth("invalid_not_a_url")
        assertFalse("Invalid URL must fail closed as unreachable", health.isReachable)
        assertNotNull(health.errorMessage)
        assertTrue(health.errorMessage!!.contains("Invalid base URL"))
    }

    @Test
    fun testCheckHealth_unreachableHost_failsClosed() = runBlocking {
        val health = BackendClient.checkHealth("http://127.0.0.1:1", timeoutMs = 500)
        assertFalse("Unreachable server must fail closed", health.isReachable)
        assertNotNull(health.errorMessage)
    }

    @Test
    fun testIsEndpointReachable() = runBlocking {
        assertTrue(BackendClient.isEndpointReachable("$baseUrl/health"))
        assertFalse(BackendClient.isEndpointReachable("http://127.0.0.1:1", timeoutMs = 500))
        assertFalse(BackendClient.isEndpointReachable("ftp://invalid"))
    }

    @Test
    fun testCallLLM_invalidUrl_failsClosedImmediately() = runBlocking {
        val result = BackendClient.callLLM(
            baseUrl = "htp://bad-scheme",
            provider = "openai",
            payloadJson = "{}"
        )
        assertNull("Invalid baseUrl must return null without crashing", result)
    }
}
