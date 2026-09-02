package com.example.data.core

import com.example.data.credential.CredentialRegistry
import com.example.data.gmail.GmailOAuthService
import com.example.data.linkedin.LinkedInOAuthService
import com.example.data.memory.MemoryItem
import com.example.data.memory.MemoryManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Stage18AuditRemediationTest {

    @Test
    fun testIntentTierClassification() {
        assertEquals(RoutingTier.FAST_LANE, WastiCore.classifyIntentTier("hi"))
        assertEquals(RoutingTier.FAST_LANE, WastiCore.classifyIntentTier("what is the time"))
        assertEquals(RoutingTier.DEEP_LANE, WastiCore.classifyIntentTier("deep search market analysis for competitors"))
        assertEquals(RoutingTier.OFFLINE_LANE, WastiCore.classifyIntentTier("offline query local memory"))
        assertEquals(RoutingTier.STANDARD_LANE, WastiCore.classifyIntentTier("write an email draft to John"))
    }

    @Test
    fun testLeadScraperEvaluation() {
        val matrix = SkillMatrix(
            services = listOf("Graphic Design", "Video Editing", "AI Automation")
        )
        val result = LeadScraperEngine.evaluateLeadMatch(
            jobPostText = "Looking for an expert to create youtube video editing and graphic design banners for social media",
            skillMatrix = matrix
        )
        assertTrue(result.matchScore >= 80)
        assertTrue(result.matchedSkills.contains("Video Editing") || result.matchedSkills.contains("Graphic Design"))
        assertTrue(result.draftedPitch.contains("Respected Hiring Client"))
    }

    @Test
    fun testLeadScraperXmlCleanHtml() {
        val html = "<p>Urgent: Need <b>logo design</b> &amp; banner</p>"
        val cleaned = LeadScraperEngine.evaluateLeadMatch(html)
        assertNotNull(cleaned)
    }

    @Test
    fun testOAuthServiceInitialization() {
        assertNotNull(GmailOAuthService)
        assertNotNull(LinkedInOAuthService)
    }
}
