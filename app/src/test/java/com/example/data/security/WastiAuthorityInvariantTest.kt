package com.example.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WastiAuthorityInvariantTest {
    private fun source(path: String): String = File("src/main/$path").readText()

    /**
     * Remove comments and quoted literals with a linear state machine. The previous
     * regex-based implementation could recurse through large Kotlin files and throw
     * StackOverflowError, making the invariant test itself the CI failure.
     */
    private fun executableSource(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inChar = false
        var escaped = false

        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'

            when {
                inLineComment -> {
                    if (c == '\n') {
                        inLineComment = false
                        out.append('\n')
                    } else out.append(' ')
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        out.append("  ")
                        i++
                    } else out.append(if (c == '\n') '\n' else ' ')
                }
                inString -> {
                    if (escaped) {
                        escaped = false
                        out.append(' ')
                    } else if (c == '\\') {
                        escaped = true
                        out.append(' ')
                    } else if (c == '"') {
                        inString = false
                        out.append(' ')
                    } else out.append(if (c == '\n') '\n' else ' ')
                }
                inChar -> {
                    if (escaped) {
                        escaped = false
                        out.append(' ')
                    } else if (c == '\\') {
                        escaped = true
                        out.append(' ')
                    } else if (c == '\'') {
                        inChar = false
                        out.append(' ')
                    } else out.append(if (c == '\n') '\n' else ' ')
                }
                c == '/' && next == '/' -> {
                    inLineComment = true
                    out.append("  ")
                    i++
                }
                c == '/' && next == '*' -> {
                    inBlockComment = true
                    out.append("  ")
                    i++
                }
                c == '"' -> {
                    inString = true
                    out.append(' ')
                }
                c == '\'' -> {
                    inChar = true
                    out.append(' ')
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
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
        val offenders = files.filter { file ->
            val lines = executableSource(file.readText()).lines()
            lines.indices.any { index ->
                if (!lines[index].contains("COMPLETED")) return@any false
                val end = minOf(index + 3, lines.lastIndex)
                (index..end).any { lineIndex ->
                    val candidate = lines[lineIndex]
                    candidate.contains("isVerified") && candidate.contains("=") && candidate.contains("true")
                }
            }
        }
        assertTrue("COMPLETED must never directly assign verification: ${offenders.map { it.path }}", offenders.isEmpty())
    }
}
