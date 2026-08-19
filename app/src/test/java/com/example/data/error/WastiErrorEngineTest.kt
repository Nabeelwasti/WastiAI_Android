package com.example.data.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WastiErrorEngineTest {

    @Test
    fun testSecurityPolicyExceptionClassification() {
        val ex = SecurityPolicyException("Access denied outside boundary", "/data/system", "sandbox_boundary")
        val analysis = WastiErrorEngine.analyze(ex, "SecurityTest")

        assertEquals("SECURITY_POLICY_VIOLATION", analysis.errorCode)
        assertEquals(false, analysis.isRecoverable)
        assertTrue(analysis.technicalDetails.contains("/data/system"))
        assertTrue(analysis.suggestedSelfCorrectionPrompt.contains("sandbox"))
    }

    @Test
    fun testAuthRequiredExceptionClassification() {
        val ex = AuthRequiredException("gemini", "API key missing")
        val analysis = WastiErrorEngine.analyze(ex, "AuthTest")

        assertEquals("AUTH_REQUIRED", analysis.errorCode)
        assertEquals(true, analysis.isRecoverable)
        assertTrue(analysis.userFriendlyMessage.contains("gemini"))
        assertTrue(analysis.suggestedSelfCorrectionPrompt.contains("Developer Settings"))
    }

    @Test
    fun testCapabilityUnavailableExceptionClassification() {
        val ex = CapabilityUnavailableException("termux_bridge")
        val analysis = WastiErrorEngine.analyze(ex, "CapabilityTest")

        assertEquals("CAPABILITY_UNAVAILABLE", analysis.errorCode)
        assertEquals(false, analysis.isRecoverable)
        assertTrue(analysis.userFriendlyMessage.contains("termux_bridge"))
    }

    @Test
    fun testNetworkExceptionClassification() {
        val ex = NetworkException("Connection refused", httpCode = 503)
        val analysis = WastiErrorEngine.analyze(ex, "NetworkTest")

        assertEquals("NETWORK_ERROR", analysis.errorCode)
        assertEquals(true, analysis.isRecoverable)
        assertTrue(analysis.technicalDetails.contains("503"))
    }

    @Test
    fun testSyntaxExceptionClassification() {
        val ex = SyntaxException("Unexpected token '{'", language = "Kotlin", line = 42)
        val analysis = WastiErrorEngine.analyze(ex, "SyntaxTest")

        assertEquals("SYNTAX_ERROR", analysis.errorCode)
        assertEquals(true, analysis.isRecoverable)
        assertTrue(analysis.technicalDetails.contains("42"))
    }

    @Test
    fun testDeviceControlExceptionClassification() {
        val ex = DeviceControlException("com.whatsapp", "ActivityNotFoundException")
        val analysis = WastiErrorEngine.analyze(ex, "DeviceTest")

        assertEquals("DEVICE_CONTROL_ERROR", analysis.errorCode)
        assertEquals(true, analysis.isRecoverable)
        assertTrue(analysis.userFriendlyMessage.contains("com.whatsapp"))
    }
}
