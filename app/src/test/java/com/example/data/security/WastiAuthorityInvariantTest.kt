package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WastiAuthorityInvariantTest {
    private fun source(path: String): String = File("src/main/$path").readText()

    /**
     * Strip Kotlin comments and string literals before checking source-level authority
     * invariants. Generated code fixtures legitimately contain the words COMPLETED and
     * isVerified=true as data, but those strings must not be mistaken for executable code.
     */
    private fun executableSource(text: String): String {
        var result = text
            .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("//[^\\n]*"), " ")
        result = result.replace(
            Regex("\\\"\\\"\\\".*?\\\"\\\"\\\"", setOf(RegexOption.DOT_MATCHES_ALL)),
            " "
        )
        result = result.replace(
            Regex("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", setOf(RegexOption.DOT_MATCHES_ALL)),
            " "
        )
        return result
    }

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
            val executable = executableSource(f.readText())
            // Detect a direct executable authority assignment, not unrelated data strings.
            Regex("\\bCOMPLETED\\b[\\s\\S]{0,160}\\bisVerified\\s*=\\s*true\\b")
                .containsMatchIn(executable)
        }
        assertTrue("COMPLETED must never directly assign verification: ${offenders.map { it.path }}", offenders.isEmpty())
    }
}
