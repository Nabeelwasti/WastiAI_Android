package com.example.data.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WastiCoreIntentAndRoutingTest {

    @Test
    fun testFastLaneClassification() {
        val tierHi = WastiCore.classifyIntentTier("hi")
        assertEquals(RoutingTier.FAST_LANE, tierHi)

        val tierTime = WastiCore.classifyIntentTier("what is the time?")
        assertEquals(RoutingTier.FAST_LANE, tierTime)

        val tierClear = WastiCore.classifyIntentTier("clear chat")
        assertEquals(RoutingTier.FAST_LANE, tierClear)
    }

    @Test
    fun testDeepLaneClassification() {
        val tierResearch = WastiCore.classifyIntentTier("Please do a deep search and comprehensive analysis of competitor AI models")
        assertEquals(RoutingTier.DEEP_LANE, tierResearch)

        val tierXray = WastiCore.classifyIntentTier("b2b xray client search in Austin Texas")
        assertEquals(RoutingTier.DEEP_LANE, tierXray)

        val tierCompare = WastiCore.classifyIntentTier("Compare and contrast Kotlin coroutines with RxJava")
        assertEquals(RoutingTier.DEEP_LANE, tierCompare)
    }

    @Test
    fun testStandardLaneClassification() {
        val tierCode = WastiCore.classifyIntentTier("Write a Jetpack Compose button with a rounded corner of 16dp and custom ripple")
        assertEquals(RoutingTier.STANDARD_LANE, tierCode)
    }

    @Test
    fun testDraftManagement() {
        val emailDraft = EmailDraft(to = "test@example.com", subject = "Proposal", body = "Hello Wasti")
        WastiCore.setPendingEmailDraft(emailDraft)
        assertEquals(emailDraft, WastiCore.pendingEmailDraft.value)

        WastiCore.clearPendingEmailDraft()
        assertEquals(null, WastiCore.pendingEmailDraft.value)

        val linkedInDraft = LinkedInDraft(content = "AI innovation update!")
        WastiCore.setPendingLinkedInDraft(linkedInDraft)
        assertEquals(linkedInDraft, WastiCore.pendingLinkedInDraft.value)

        WastiCore.clearPendingLinkedInDraft()
        assertEquals(null, WastiCore.pendingLinkedInDraft.value)
    }

    @Test
    fun testToolProgressState() {
        WastiCore.updateProgress(ProgressStage.SCRAPING, "Scraping web results...")
        val state = WastiCore.toolProgressState.value
        assertEquals(ProgressStage.SCRAPING, state.stage)
        assertEquals("Scraping web results...", state.statusMessage)
        assertEquals(true, state.isActive)

        WastiCore.updateProgress(ProgressStage.COMPLETED, "Done")
        assertEquals(false, WastiCore.toolProgressState.value.isActive)
    }
}
