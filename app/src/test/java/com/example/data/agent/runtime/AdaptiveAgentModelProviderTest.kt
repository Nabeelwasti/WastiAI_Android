package com.example.data.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import com.example.data.core.TestCategory
import com.example.data.core.TestTier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Robolectric test of AdaptiveAgentModelProvider fallback and neural response parsing"
)
class AdaptiveAgentModelProviderTest {

    private val provider = AdaptiveAgentModelProvider()

    @Test
    fun testGeneratePlan_fallsBackGracefullyWhenOffline() = runBlocking {
        val plan = provider.generatePlan("read file test.txt", listOf("FILES", "CODING"))
        assertNotNull(plan)
        assertTrue(plan.isValid)
        assertTrue(plan.steps.isNotEmpty())
        assertEquals("read_file", plan.steps.first().toolName)
        // Truthful Provenance: Offline fallback must be explicitly non-neural
        assertFalse(plan.isNeuralModel)
        assertEquals("RULE_BASED_FALLBACK", plan.providerSource)
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
        assertFalse(writePlan.isNeuralModel)
        assertEquals("RULE_BASED_FALLBACK", writePlan.providerSource)

        val execPlan = provider.generatePlan("run code script", listOf("CODING"))
        assertTrue(execPlan.steps.any { it.toolName == "execute_code" })
        assertFalse(execPlan.isNeuralModel)

        val listPlan = provider.generatePlan("show all directory contents", listOf("FILES"))
        assertTrue(listPlan.steps.any { it.toolName == "list_files" })
        assertFalse(listPlan.isNeuralModel)
    }

    @Test
    fun testNeuralPlanResponseProvenanceAndStructure() {
        val jsonPayload = """
            {
              "rawReasoning": "Read source file to inspect dependencies",
              "steps": [
                {
                  "stepId": 1,
                  "toolName": "read_file",
                  "arguments": {"path": "src/main.kt"},
                  "description": "Read main source file"
                }
              ]
            }
        """.trimIndent()

        val parsedPlan = provider.parsePlanResponse(jsonPayload, "Inspect dependencies", "GEMINI_1_5_FLASH")
        assertNotNull(parsedPlan)
        assertTrue(parsedPlan!!.isValid)
        assertTrue(parsedPlan.isNeuralModel)
        assertEquals("GEMINI_1_5_FLASH", parsedPlan.providerSource)
        assertEquals(1, parsedPlan.steps.size)
        assertEquals("read_file", parsedPlan.steps[0].toolName)
        assertEquals("src/main.kt", parsedPlan.steps[0].arguments["path"])
    }
}
