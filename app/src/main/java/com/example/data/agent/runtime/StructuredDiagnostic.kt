package com.example.data.agent.runtime

import java.util.UUID

/**
 * Stage 19: Truthful, Structured Diagnostic Framework for Autonomous Self-Development.
 */
enum class DiagnosticCategory {
    SYNTAX,
    TYPE_MISMATCH,
    UNRESOLVED_SYMBOL,
    LINK_ERROR,
    RUNTIME_EXCEPTION,
    DEPENDENCY_MISSING,
    TOOLCHAIN_MISSING,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    TEST_FAILURE,
    SECURITY_POLICY_VIOLATION,
    UNKNOWN
}

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO
}

data class StructuredDiagnostic(
    val diagnosticId: String = UUID.randomUUID().toString(),
    val language: String,
    val framework: String = "GENERIC",
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val symbol: String? = null,
    val errorCode: String? = null,
    val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val compiler: String? = null,
    val category: DiagnosticCategory = DiagnosticCategory.UNKNOWN,
    val probableCause: String? = null,
    val suggestedFix: String? = null,
    val rawOutput: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class StructuredDiagnosticReport(
    val reportId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val language: String,
    val hasErrors: Boolean,
    val totalErrors: Int,
    val totalWarnings: Int,
    val diagnostics: List<StructuredDiagnostic>,
    val rootCauseSummary: String,
    val actionableSuggestions: List<String> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

/**
 * Parser for compiler/interpreter/test runner logs into StructuredDiagnostic objects.
 */
object DiagnosticParser {

    // Kotlin: e: file:///app/src/main/File.kt:124:37 Unresolved reference 'xyz'
    private val KOTLIN_ERROR_REGEX = Regex("""e:\s+(?:file://)?([^:]+):(\d+):(\d+)\s+(.+)""")
    // Kotlin warning: w: file:///app/...:12:34 message
    private val KOTLIN_WARN_REGEX = Regex("""w:\s+(?:file://)?([^:]+):(\d+):(\d+)\s+(.+)""")

    // Python Traceback: File "/workspace/script.py", line 42, in <module>
    private val PYTHON_TRACEBACK_REGEX = Regex("""File\s+"([^"]+)",\s+line\s+(\d+)(?:,\s+in\s+([^\n]+))?""")
    // Python error header: SyntaxError: invalid syntax or NameError: name 'x' is not defined
    private val PYTHON_ERROR_REGEX = Regex("""([A-Za-z]+Error|Exception):\s+(.+)""")

    // C/C++/Clang/GCC: /path/file.cpp:14:5: error: unknown type name 'Foo'
    private val GCC_CLANG_REGEX = Regex("""([^:\s]+):(\d+):(\d+):\s+(error|warning|fatal error):\s+(.+)""")

    // JS/TS: /path/file.ts:12:5 - error TS2304: Cannot find name 'foo'.
    private val TS_REGEX = Regex("""([^:\s]+):(\d+):(\d+)\s+-\s+(error|warning)\s+([A-Z0-9]+):\s+(.+)""")

    fun parseLogs(
        projectId: String,
        language: String,
        rawLogs: String,
        framework: String = "GENERIC"
    ): StructuredDiagnosticReport {
        val diagnostics = mutableListOf<StructuredDiagnostic>()
        val lines = rawLogs.lines()

        val normLang = language.trim().uppercase()

        when (normLang) {
            "KOTLIN", "JAVA" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    val errorMatch = KOTLIN_ERROR_REGEX.find(trimmed)
                    if (errorMatch != null) {
                        val file = errorMatch.groupValues[1]
                        val lineNum = errorMatch.groupValues[2].toIntOrNull()
                        val colNum = errorMatch.groupValues[3].toIntOrNull()
                        val msg = errorMatch.groupValues[4]

                        val category = when {
                            msg.contains("Unresolved reference", ignoreCase = true) -> DiagnosticCategory.UNRESOLVED_SYMBOL
                            msg.contains("Type mismatch", ignoreCase = true) -> DiagnosticCategory.TYPE_MISMATCH
                            msg.contains("Syntax error", ignoreCase = true) || msg.contains("Expecting", ignoreCase = true) -> DiagnosticCategory.SYNTAX
                            else -> DiagnosticCategory.SYNTAX
                        }

                        val symbolMatch = Regex("'(.*?)'").find(msg)?.groupValues?.get(1)

                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "KOTLIN",
                                framework = framework,
                                file = file,
                                line = lineNum,
                                column = colNum,
                                symbol = symbolMatch,
                                errorCode = null,
                                severity = DiagnosticSeverity.ERROR,
                                compiler = "kotlinc",
                                category = category,
                                probableCause = "Compiler reported error: $msg",
                                suggestedFix = if (symbolMatch != null) "Verify declaration or import for symbol '$symbolMatch'." else "Check syntax at line $lineNum.",
                                rawOutput = trimmed
                            )
                        )
                    } else {
                        val warnMatch = KOTLIN_WARN_REGEX.find(trimmed)
                        if (warnMatch != null) {
                            diagnostics.add(
                                StructuredDiagnostic(
                                    language = "KOTLIN",
                                    framework = framework,
                                    file = warnMatch.groupValues[1],
                                    line = warnMatch.groupValues[2].toIntOrNull(),
                                    column = warnMatch.groupValues[3].toIntOrNull(),
                                    severity = DiagnosticSeverity.WARNING,
                                    compiler = "kotlinc",
                                    category = DiagnosticCategory.UNKNOWN,
                                    probableCause = warnMatch.groupValues[4],
                                    suggestedFix = "Review compiler warning.",
                                    rawOutput = trimmed
                                )
                            )
                        }
                    }
                }
            }

            "PYTHON" -> {
                var currentFile: String? = null
                var currentLine: Int? = null

                for (line in lines) {
                    val trimmed = line.trim()
                    val tbMatch = PYTHON_TRACEBACK_REGEX.find(trimmed)
                    if (tbMatch != null) {
                        currentFile = tbMatch.groupValues[1]
                        currentLine = tbMatch.groupValues[2].toIntOrNull()
                    }

                    val errMatch = PYTHON_ERROR_REGEX.find(trimmed)
                    if (errMatch != null) {
                        val errType = errMatch.groupValues[1]
                        val errMsg = errMatch.groupValues[2]
                        val category = when (errType) {
                            "SyntaxError", "IndentationError" -> DiagnosticCategory.SYNTAX
                            "NameError" -> DiagnosticCategory.UNRESOLVED_SYMBOL
                            "TypeError" -> DiagnosticCategory.TYPE_MISMATCH
                            "ModuleNotFoundError", "ImportError" -> DiagnosticCategory.DEPENDENCY_MISSING
                            else -> DiagnosticCategory.RUNTIME_EXCEPTION
                        }

                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "PYTHON",
                                framework = framework,
                                file = currentFile,
                                line = currentLine,
                                errorCode = errType,
                                severity = DiagnosticSeverity.ERROR,
                                compiler = "python3",
                                category = category,
                                probableCause = "$errType: $errMsg",
                                suggestedFix = when (category) {
                                    DiagnosticCategory.DEPENDENCY_MISSING -> "Install missing module or check import statement."
                                    DiagnosticCategory.UNRESOLVED_SYMBOL -> "Check variable declaration or scope."
                                    DiagnosticCategory.SYNTAX -> "Fix indentation or syntax near line $currentLine."
                                    else -> "Check exception trace."
                                },
                                rawOutput = trimmed
                            )
                        )
                    }
                }
            }

            "CPP", "C", "RUST" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    val match = GCC_CLANG_REGEX.find(trimmed)
                    if (match != null) {
                        val file = match.groupValues[1]
                        val lineNum = match.groupValues[2].toIntOrNull()
                        val colNum = match.groupValues[3].toIntOrNull()
                        val type = match.groupValues[4]
                        val msg = match.groupValues[5]

                        val isErr = type.contains("error", ignoreCase = true)
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = normLang,
                                framework = framework,
                                file = file,
                                line = lineNum,
                                column = colNum,
                                severity = if (isErr) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                                compiler = "gcc/clang",
                                category = if (isErr) DiagnosticCategory.SYNTAX else DiagnosticCategory.UNKNOWN,
                                probableCause = msg,
                                suggestedFix = "Inspect source at $file:$lineNum:$colNum",
                                rawOutput = trimmed
                            )
                        )
                    }
                }
            }

            "TYPESCRIPT", "JAVASCRIPT" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    val match = TS_REGEX.find(trimmed)
                    if (match != null) {
                        val file = match.groupValues[1]
                        val lineNum = match.groupValues[2].toIntOrNull()
                        val colNum = match.groupValues[3].toIntOrNull()
                        val type = match.groupValues[4]
                        val code = match.groupValues[5]
                        val msg = match.groupValues[6]

                        diagnostics.add(
                            StructuredDiagnostic(
                                language = normLang,
                                framework = framework,
                                file = file,
                                line = lineNum,
                                column = colNum,
                                errorCode = code,
                                severity = if (type == "error") DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                                compiler = "tsc",
                                category = DiagnosticCategory.TYPE_MISMATCH,
                                probableCause = msg,
                                suggestedFix = "Fix TypeScript diagnostic [$code] at $file:$lineNum",
                                rawOutput = trimmed
                            )
                        )
                    }
                }
            }

            else -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    val pyMatch = PYTHON_ERROR_REGEX.find(trimmed)
                    val ktMatch = KOTLIN_ERROR_REGEX.find(trimmed)
                    val gccMatch = GCC_CLANG_REGEX.find(trimmed)
                    val tsMatch = TS_REGEX.find(trimmed)

                    if (pyMatch != null) {
                        val errType = pyMatch.groupValues[1]
                        val errMsg = pyMatch.groupValues[2]
                        val locMatch = Regex("""at\s+([^\s:]+)(?:\s+line\s+(\d+)|:(\d+))?""").find(errMsg)
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "PYTHON",
                                framework = framework,
                                file = locMatch?.groupValues?.get(1),
                                line = locMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: locMatch?.groupValues?.getOrNull(3)?.toIntOrNull(),
                                errorCode = errType,
                                severity = DiagnosticSeverity.ERROR,
                                compiler = "python3",
                                category = if (errType.contains("Syntax")) DiagnosticCategory.SYNTAX else DiagnosticCategory.RUNTIME_EXCEPTION,
                                probableCause = "$errType: $errMsg",
                                suggestedFix = "Fix $errType in ${locMatch?.groupValues?.get(1) ?: "source file"}.",
                                rawOutput = trimmed
                            )
                        )
                    } else if (ktMatch != null) {
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "KOTLIN",
                                framework = framework,
                                file = ktMatch.groupValues[1],
                                line = ktMatch.groupValues[2].toIntOrNull(),
                                column = ktMatch.groupValues[3].toIntOrNull(),
                                severity = DiagnosticSeverity.ERROR,
                                compiler = "kotlinc",
                                category = DiagnosticCategory.SYNTAX,
                                probableCause = ktMatch.groupValues[4],
                                suggestedFix = "Fix Kotlin error at line ${ktMatch.groupValues[2]}.",
                                rawOutput = trimmed
                            )
                        )
                    } else if (gccMatch != null) {
                        val type = gccMatch.groupValues[4].lowercase()
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "CPP",
                                framework = framework,
                                file = gccMatch.groupValues[1],
                                line = gccMatch.groupValues[2].toIntOrNull(),
                                column = gccMatch.groupValues[3].toIntOrNull(),
                                severity = if (type.contains("error")) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                                compiler = "clang",
                                category = DiagnosticCategory.SYNTAX,
                                probableCause = gccMatch.groupValues[5],
                                suggestedFix = "Fix native issue at line ${gccMatch.groupValues[2]}.",
                                rawOutput = trimmed
                            )
                        )
                    } else if (tsMatch != null) {
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = "TYPESCRIPT",
                                framework = framework,
                                file = tsMatch.groupValues[1],
                                line = tsMatch.groupValues[2].toIntOrNull(),
                                column = tsMatch.groupValues[3].toIntOrNull(),
                                errorCode = tsMatch.groupValues[5],
                                severity = if (tsMatch.groupValues[4].lowercase() == "error") DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                                compiler = "tsc",
                                category = DiagnosticCategory.TYPE_MISMATCH,
                                probableCause = tsMatch.groupValues[6],
                                suggestedFix = "Fix TypeScript diagnostic at line ${tsMatch.groupValues[2]}.",
                                rawOutput = trimmed
                            )
                        )
                    } else if (trimmed.startsWith("Warning:", ignoreCase = true) || trimmed.startsWith("WARN:", ignoreCase = true)) {
                        val warnMsg = trimmed.substringAfter(":").trim()
                        val locMatch = Regex("""at\s+([^\s:]+)(?:\s+line\s+(\d+)|:(\d+))?""").find(warnMsg)
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = language,
                                framework = framework,
                                file = locMatch?.groupValues?.get(1),
                                line = locMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: locMatch?.groupValues?.getOrNull(3)?.toIntOrNull(),
                                errorCode = "Warning",
                                severity = DiagnosticSeverity.WARNING,
                                category = DiagnosticCategory.UNKNOWN,
                                probableCause = warnMsg,
                                suggestedFix = "Review warning output.",
                                rawOutput = trimmed
                            )
                        )
                    } else if (trimmed.contains("error", ignoreCase = true) || trimmed.contains("fatal", ignoreCase = true)) {
                        diagnostics.add(
                            StructuredDiagnostic(
                                language = language,
                                framework = framework,
                                severity = DiagnosticSeverity.ERROR,
                                category = DiagnosticCategory.UNKNOWN,
                                probableCause = trimmed,
                                suggestedFix = "Review raw error output.",
                                rawOutput = trimmed
                            )
                        )
                    }
                }
            }
        }

        val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val warnings = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

        val suggestions = diagnostics.mapNotNull { it.suggestedFix }.distinct()

        return StructuredDiagnosticReport(
            projectId = projectId,
            language = language,
            hasErrors = errors > 0,
            totalErrors = errors,
            totalWarnings = warnings,
            diagnostics = diagnostics,
            rootCauseSummary = if (errors > 0) "Identified $errors structured compilation/runtime error(s)." else "No structured errors found.",
            actionableSuggestions = suggestions
        )
    }
}
