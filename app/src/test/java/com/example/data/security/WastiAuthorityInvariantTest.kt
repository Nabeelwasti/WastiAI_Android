package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WastiAuthorityInvariantTest {
    private fun source(path: String): String = File("src/main/$path").readText()

    @Test fun noHardcodedSovereignSigningPassword() {
        val files = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.all { !it.readText().contains("WastiSovereign2026!") })
    }

    @Test fun officialFingerprintCannotBeOverriddenByEnvironment() {
        val text = source("java/com/example/data/core/WastiProductionSigningEngine.kt")
        assertFalse(text.contains("AUTHORITATIVE_PRODUCTION_FINGERPRINT") && text.contains("?: PINNED_OFFICIAL_PRODUCTION_FINGERPRINT"))
        assertTrue(text.contains("PINNED_OFFICIAL_PRODUCTION_FINGERPRINT"))
    }

    @Test fun completedIsNotVerificationAuthority() {
        val files = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = files.filter { f ->
            val t = f.readText()
            t.contains("COMPLETED") && t.contains("isVerified = true")
        }
        assertTrue("COMPLETED must never directly assign verification", offenders.isEmpty())
    }
}
