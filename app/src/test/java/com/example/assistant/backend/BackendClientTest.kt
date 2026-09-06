package com.example.assistant.backend

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

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
                "/llm" -> "{\"provider\":\"openai\",\"result\":\"mocked LLM answer\"}"
                "/dev/patch" -> "{\"prUrl\":\"https://github.com/test/pull/1\",\"branch\":\"wasti/patch-1\"}"
                "/wakeword" -> "{\"status\":\"accepted\"}"
                "/email/send" -> "{\"status\":\"sent\"}"
                else -> "{\"error\":\"not found\"}"
            }

            val statusCode = if (exchange.requestURI.path == "/error") 500 else 200
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
}
