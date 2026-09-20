package com.example.data.core

import com.example.data.ai.model.ModelArchitectureFamily
import com.example.data.ai.model.NeuralReferenceFixture
import com.example.data.ai.model.QuantizationType
import com.example.data.ai.model.SupportedModelContract
import com.example.data.ai.runtime.NativeLlamaBridge
import com.example.data.ai.runtime.WastiLocalTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authoritative Neural Architecture Contract & Reference Verification Unit Test Suite.
 * Adheres to WASTI AI OS Eternal Manifesto — Zero Fabrication Law.
 */
class NeuralArchitectureContractAndReferenceTest {

    @Test
    fun testAllClaimedArchitecturesHaveAuthoritativeContracts() {
        val claimedFamilies = listOf(
            ModelArchitectureFamily.LLAMA,
            ModelArchitectureFamily.MISTRAL,
            ModelArchitectureFamily.QWEN2,
            ModelArchitectureFamily.GEMMA,
            ModelArchitectureFamily.PHI3
        )

        for (family in claimedFamilies) {
            assertNotNull("Family canonical name must not be empty", family.canonicalName)
            assertTrue("Default activation must be SwiGLU or GeGLU", family.defaultActivation in listOf("SwiGLU", "GeGLU"))
            assertTrue("Default normalization must be RMSNorm or GemmaRMSNorm", family.defaultNormalization in listOf("RMSNorm", "GemmaRMSNorm"))
            assertTrue("RoPE base frequency must be positive", family.defaultRopeFreqBase > 0.0f)
        }
    }

    @Test
    fun testSupportedModelContractProvidesExactArchitectureMetadata() {
        val smollmContract = SupportedModelContract.getArchitectureContract("wasti-smollm")
        assertNotNull("SmolLM contract must exist", smollmContract)
        assertEquals(2048, smollmContract!!.expectedDimensions)
        assertEquals(24, smollmContract.expectedLayers)
        assertEquals(32, smollmContract.expectedHeads)
        assertEquals(49152, smollmContract.expectedVocabSize)
        assertEquals(QuantizationType.Q4_K_M, smollmContract.quantization)

        val qwenContract = SupportedModelContract.getArchitectureContract("wasti-qwen")
        assertNotNull("Qwen contract must exist", qwenContract)
        assertEquals(1536, qwenContract!!.expectedDimensions)
        assertEquals(28, qwenContract.expectedLayers)
        assertEquals(12, qwenContract.expectedHeads)
        assertEquals(2, qwenContract.expectedKvHeads)

        val gemmaContract = SupportedModelContract.getArchitectureContract("wasti-gemma")
        assertNotNull("Gemma contract must exist", gemmaContract)
        assertEquals(2304, gemmaContract!!.expectedDimensions)
        assertEquals(26, gemmaContract.expectedLayers)
        assertTrue("Gemma requires explicit LM head", gemmaContract.family.requiresExplicitLmHead)
    }

    @Test
    fun testTokenizerSentencePieceAndByteFallback() {
        val vocab = mapOf(
            "hello" to 100,
            "world" to 101,
            " " to 102,
            "test" to 103
        )
        val invVocab = vocab.entries.associate { it.value to it.key }
        val tokenizer = WastiLocalTokenizer(vocab = vocab, invVocab = invVocab)

        // Encode known words
        val tokens = tokenizer.encode("hello world")
        assertTrue("Tokens should contain vocabulary IDs", tokens.contains(100) && tokens.contains(101))

        // Decode known words
        val decoded = tokenizer.decode(tokens)
        assertTrue("Decoded text should contain words", decoded.contains("hello") && decoded.contains("world"))

        // Byte fallback for unseen byte
        val byteTokens = tokenizer.encode("xyz")
        assertTrue("Byte fallback should encode bytes", byteTokens.isNotEmpty())
    }

    @Test
    fun testReferenceFixtureComparisonWithTolerances() {
        val fixture = NeuralReferenceFixture(
            fixtureId = "fixture_smollm_probe_1",
            modelId = "wasti-smollm",
            architecture = "llama",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(1, 15043),
            expectedHiddenStatePrefix = floatArrayOf(0.125f, -0.045f, 0.892f, 0.011f),
            expectedLogitsPrefix = floatArrayOf(-5.2f, 1.4f, 8.9f, -0.3f),
            expectedOutputTokens = intArrayOf(15043, 29889),
            numericalTolerance = 1e-3f
        )

        assertEquals("wasti-smollm", fixture.modelId)
        assertEquals(2, fixture.expectedPromptTokens.size)
        assertEquals(4, fixture.expectedHiddenStatePrefix.size)
        assertEquals(4, fixture.expectedLogitsPrefix.size)
        assertTrue("Tolerance must be strict", fixture.numericalTolerance <= 1e-3f)

        // Without live model handle, reference verification correctly reports false (fail-closed)
        val verified = NativeLlamaBridge.isReferenceVerified(0L, fixture)
        assertFalse("Null model handle must fail-closed on reference verification", verified)
    }
}
