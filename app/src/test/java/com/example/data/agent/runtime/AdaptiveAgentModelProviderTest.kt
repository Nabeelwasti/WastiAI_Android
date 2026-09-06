package com.example.data.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveAgentModelProviderTest {

    private val provider = AdaptiveAgentModelProvider()

    @Test
    fun testGeneratePlan_fallsBackGracefullyWhenOffline() = runBlocking {
        val plan = provider.generatePlan("read file test.txt", listOf("FILES", "CODING"))
        assertNotNull(plan)
        assertTrue(plan.isValid)
        assertTrue(plan.steps.isNotEmpty())
        assertEquals("read_file", plan.steps.first().toolName)
    }

    @Test
    fun testAnalyzeError_fallsBackGracefullyWhenOffline() = runBlocking {
        val diagnostic = provider.analyzeError("e: file:///test.kt: Unresolved reference", "Tool: execute_code")
        assertNotNull(diagnostic)
        assertEquals(ExecutionErrorType.COMPILATION, diagnostic.category)
        assertTrue(diagnostic.summary.isNotBlank())
    }

    @Test
    fun testProposeCorrection_fallsBackGracefullyWhenOffline() = runBlocking {
        val diag = ErrorDiagnostic(
            category = ExecutionErrorType.COMPILATION,
            summary = "Compilation error",
            evidence = "Unresolved reference: foo",
            probableCause = "Missing import",
            suggestedCorrection = "Add import"
        )
        val correction = provider.proposeCorrection(diag, "Context text")
        assertNotNull(correction)
        assertTrue(correction.explanation.isNotBlank())
    }

    @Test
    fun testPlanningWithVariousGoals() = runBlocking {
        val writePlan = provider.generatePlan("write file output.txt with data", listOf("FILES"))
        assertTrue(writePlan.steps.any { it.toolName == "write_file" })

        val execPlan = provider.generatePlan("run code script", listOf("CODING"))
        assertTrue(execPlan.steps.any { it.toolName == "execute_code" })

        val listPlan = provider.generatePlan("show all directory contents", listOf("FILES"))
        assertTrue(listPlan.steps.any { it.toolName == "list_files" })
    }
}
