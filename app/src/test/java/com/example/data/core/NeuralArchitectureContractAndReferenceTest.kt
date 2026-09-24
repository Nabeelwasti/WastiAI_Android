package com.example.data.core

import com.example.data.ai.model.AuthoritativeNeuralFixtures
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
        val claimedFamilies = ModelArchitectureFamily.values().toList()

        for (family in claimedFamilies) {
            assertNotNull("Family canonical name must not be empty", family.canonicalName)
            assertTrue("Default activation must be SwiGLU or GeGLU", family.defaultActivation in listOf("SwiGLU", "GeGLU"))
            assertTrue("Default normalization must be RMSNorm, GemmaRMSNorm or LayerNorm", family.defaultNormalization in listOf("RMSNorm", "GemmaRMSNorm", "LayerNorm"))
            assertTrue("RoPE base frequency must be positive", family.defaultRopeFreqBase > 0.0f)
        }
    }

    @Test
    fun testSupportedModelContractProvidesExactArchitectureMetadata() {
        assertEquals(12, SupportedModelContract.ALL_KNOWN_MODEL_IDS.size)
        assertEquals(5, SupportedModelContract.SUPPORTED_LOCAL_MODEL_IDS.size)
        assertEquals(5, SupportedModelContract.PROVEN_LOCAL_MODEL_IDS.size)

        for (modelId in SupportedModelContract.ALL_KNOWN_MODEL_IDS) {
            val contract = SupportedModelContract.getArchitectureContract(modelId)
            assertNotNull("Contract must exist for $modelId", contract)
            assertTrue("Expected dimensions must be > 0 for $modelId", contract!!.expectedDimensions > 0)
            assertTrue("Expected layers must be > 0 for $modelId", contract.expectedLayers > 0)
            assertTrue("Expected heads must be > 0 for $modelId", contract.expectedHeads > 0)
            assertTrue("Expected vocab size must be > 0 for $modelId", contract.expectedVocabSize > 0)
        }

        val smollmContract = SupportedModelContract.getArchitectureContract("wasti-smollm")
        assertNotNull("SmolLM contract must exist", smollmContract)
        assertEquals(2048, smollmContract!!.expectedDimensions)
        assertEquals(24, smollmContract.expectedLayers)
        assertEquals(32, smollmContract.expectedHeads)
        assertEquals(49152, smollmContract.expectedVocabSize)
        assertEquals(QuantizationType.Q4_K_M, smollmContract.quantization)
        assertTrue(smollmContract.isLocalExecutionSupported)

        val qwenContract = SupportedModelContract.getArchitectureContract("wasti-qwen")
        assertNotNull("Qwen contract must exist", qwenContract)
        assertEquals(1536, qwenContract!!.expectedDimensions)
        assertEquals(28, qwenContract.expectedLayers)
        assertEquals(12, qwenContract.expectedHeads)
        assertEquals(2, qwenContract.expectedKvHeads)
        assertTrue(qwenContract.isLocalExecutionSupported)

        val gemmaContract = SupportedModelContract.getArchitectureContract("wasti-gemma")
        assertNotNull("Gemma contract must exist", gemmaContract)
        assertEquals(2304, gemmaContract!!.expectedDimensions)
        assertEquals(26, gemmaContract.expectedLayers)
        assertTrue("Gemma requires explicit LM head", gemmaContract.family.requiresExplicitLmHead)
        assertTrue(gemmaContract.isLocalExecutionSupported)

        val deepseekContract = SupportedModelContract.getArchitectureContract("wasti-deepseek")
        assertNotNull("DeepSeek contract must exist", deepseekContract)
        assertFalse("DeepSeek requires server/mesh", deepseekContract!!.isLocalExecutionSupported)

        val mistralContract = SupportedModelContract.getArchitectureContract("wasti-mistral")
        assertNotNull("Mistral contract must exist", mistralContract)
        assertFalse("Mistral requires server/mesh", mistralContract!!.isLocalExecutionSupported)
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
        val fixture = AuthoritativeNeuralFixtures.getFixture("wasti-smollm")
        assertNotNull("SmolLM fixture must be present in AuthoritativeNeuralFixtures", fixture)

        assertEquals("wasti-smollm", fixture!!.modelId)
        assertEquals("llama", fixture.architecture)
        assertEquals(2, fixture.expectedPromptTokens.size)
        assertEquals(4, fixture.expectedHiddenStatePrefix.size)
        assertEquals(4, fixture.expectedLogitsPrefix.size)
        assertTrue("Tolerance must be strict", fixture.numericalTolerance <= 1e-3f)
        assertEquals("decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33", fixture.expectedArtifactSha256)

        // Without live model handle, reference verification correctly reports false (fail-closed)
        val verified = NativeLlamaBridge.isReferenceVerified(0L, fixture)
        assertFalse("Null model handle must fail-closed on reference verification", verified)
    }

    @Test
    fun testGgufTensorContractValidation() {
        val contract = SupportedModelContract.getArchitectureContract("wasti-smollm")!!

        // Synthesize valid tensor name list for 24 layers of SmolLM
        val validTensors = mutableListOf("token_embd.weight", "output_norm.weight")
        for (i in 0 until 24) {
            validTensors.add("blk.$i.attn_q.weight")
            validTensors.add("blk.$i.attn_k.weight")
            validTensors.add("blk.$i.attn_v.weight")
            validTensors.add("blk.$i.attn_output.weight")
            validTensors.add("blk.$i.attn_norm.weight")
            validTensors.add("blk.$i.ffn_gate.weight")
            validTensors.add("blk.$i.ffn_up.weight")
            validTensors.add("blk.$i.ffn_down.weight")
            validTensors.add("blk.$i.ffn_norm.weight")
        }

        val validRes = AuthoritativeNeuralFixtures.validateTensors(validTensors, contract)
        assertTrue("Valid tensor set must pass validation", validRes.isValid)
        assertEquals(validTensors.size, validRes.validatedTensorCount)

        // Missing tensor detection
        val missingTensors = validTensors.filter { it != "blk.5.attn_q.weight" }
        val missingRes = AuthoritativeNeuralFixtures.validateTensors(missingTensors, contract)
        assertFalse("Missing required tensor must fail validation", missingRes.isValid)
        assertTrue(missingRes.missingTensors.contains("blk.5.attn_q.weight"))

        // Duplicate tensor detection
        val duplicateTensors = validTensors + listOf("token_embd.weight")
        val dupRes = AuthoritativeNeuralFixtures.validateTensors(duplicateTensors, contract)
        assertFalse("Duplicate tensor must fail validation", dupRes.isValid)
        assertTrue(dupRes.duplicateTensors.contains("token_embd.weight"))
    }

    @Test
    fun testQ4K_DequantizationWithIndependentReferenceVector() {
        val blockBytes = ByteArray(144)
        // Vector 1: Standard subblock 0 low-nibble
        // d = 1.0f in FP16 (0x3C00), dmin = 0.5f in FP16 (0x3800)
        blockBytes[0] = 0x00.toByte()
        blockBytes[1] = 0x3C.toByte()
        blockBytes[2] = 0x00.toByte()
        blockBytes[3] = 0x38.toByte()

        // Set scales bytes for subblocks: sc[0]=1, m[0]=2
        blockBytes[4] = 0x01.toByte() // scales[0]: sc[0] = 1
        blockBytes[8] = 0x02.toByte() // scales[4]: m[0] = 2

        // Set qs byte for index 0: low nibble = 5 -> q = 5
        blockBytes[16] = 0x05.toByte()

        val decoded1 = AuthoritativeNeuralFixtures.dequantizeQ4KBlockReference(blockBytes)
        assertEquals(256, decoded1.size)
        // d_sc = d * sc[0] = 1.0 * 1 = 1.0, dmin_m = dmin * m[0] = 0.5 * 2 = 1.0
        // weight = (1.0 * 5) - 1.0 = 4.0
        assertEquals(4.0f, decoded1[0], 1e-4f)

        // Vector 2: Subblock 1 high-nibble & Boundary Values (min=0, max=15)
        val blockBytes2 = ByteArray(144)
        // d = 2.0f in FP16 (0x4000), dmin = 1.0f in FP16 (0x3C00)
        blockBytes2[0] = 0x00.toByte()
        blockBytes2[1] = 0x40.toByte()
        blockBytes2[2] = 0x00.toByte()
        blockBytes2[3] = 0x3C.toByte()

        // subblock 1: sc[1]=3, m[1]=1
        blockBytes2[5] = 0x03.toByte()
        blockBytes2[9] = 0x01.toByte()

        // Index 32 corresponds to high nibble of qs[0] (subblock 1)
        // low nibble = 0 (q_min), high nibble = 15 (0xF, q_max)
        blockBytes2[16] = 0xF0.toByte()

        val decoded2 = AuthoritativeNeuralFixtures.dequantizeQ4KBlockReference(blockBytes2)
        // index 0: q = 0 => (2.0 * 0 * sc[0]) - (1.0 * m[0]) = 0.0
        assertEquals(0.0f, decoded2[0], 1e-4f)
        // index 32: q = 15 => (d_sc * q) - dmin_m = (2.0 * 3 * 15) - (1.0 * 1) = 90 - 1 = 89.0
        assertEquals(89.0f, decoded2[32], 1e-4f)

        // Vector 3: Comprehensive 8-subblock & boundary value verification
        val blockBytes3 = ByteArray(144)
        // d = 2.0f in FP16 (0x4000), dmin = 0.5f in FP16 (0x3800)
        blockBytes3[0] = 0x00.toByte()
        blockBytes3[1] = 0x40.toByte()
        blockBytes3[2] = 0x00.toByte()
        blockBytes3[3] = 0x38.toByte()

        // Set distinct scales and mins for subblocks 0..7
        // For sb 0..3:
        // sb 0: sc[0]=1, m[0]=1 => dSc=2.0, dminM=0.5
        blockBytes3[4] = 0x01.toByte()
        blockBytes3[8] = 0x01.toByte()
        // sb 1: sc[1]=2, m[1]=2 => dSc=4.0, dminM=1.0
        blockBytes3[5] = 0x02.toByte()
        blockBytes3[9] = 0x02.toByte()
        // sb 2: sc[2]=3, m[2]=3 => dSc=6.0, dminM=1.5
        blockBytes3[6] = 0x03.toByte()
        blockBytes3[10] = 0x03.toByte()
        // sb 3: sc[3]=4, m[3]=4 => dSc=8.0, dminM=2.0
        blockBytes3[7] = 0x04.toByte()
        blockBytes3[11] = 0x04.toByte()

        // Set nibble values for qs
        // sb 0 (idx 0): low nibble = 10 => weight = (2.0 * 10) - 0.5 = 19.5
        // sb 1 (idx 32): high nibble = 5 => weight = (4.0 * 5) - 1.0 = 19.0
        blockBytes3[16] = 0x5A.toByte() // low = 10 (0xA), high = 5 (0x5)

        val decoded3 = AuthoritativeNeuralFixtures.dequantizeQ4KBlockReference(blockBytes3)
        assertEquals(256, decoded3.size)
        assertEquals(19.5f, decoded3[0], 1e-4f)
        assertEquals(19.0f, decoded3[32], 1e-4f)

        for (i in 0 until 256) {
            assertTrue("Dequantized float $i must be finite", decoded3[i].isFinite())
        }
    }
}
