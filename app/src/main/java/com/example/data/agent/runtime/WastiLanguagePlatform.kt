package com.example.data.agent.runtime

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 7: Wasti Multi-Language Development Platform & Sandbox Manager.
 * Defines the complete multi-language capability matrix, runtime detection, project templates,
 * and execution support for Wasti OS.
 *
 * Distinguishes:
 * - Code Generation
 * - Code Editing / Refactoring
 * - Code Analysis / AST Inspection
 * - Runtime Installation State
 * - Compiler Availability
 * - Execution Capability
 * - Reality State
 */

enum class LanguageCapabilityStatus {
    SUPPORTED,
    AVAILABLE,
    NOT_INSTALLED,
    COMPILER_UNAVAILABLE,
    RUNTIME_UNAVAILABLE,
    EXECUTION_UNAVAILABLE,
    IMPLEMENTED_NOT_LIVE_VERIFIED,
    LIVE_VERIFIED
}

data class LanguageSupportProfile(
    val languageId: String,
    val displayName: String,
    val fileExtensions: List<String>,
    val codeGenerationSupported: Boolean = true,
    val codeEditingSupported: Boolean = true,
    val codeAnalysisSupported: Boolean = true,
    val runtimeState: RuntimeCapabilityState = RuntimeCapabilityState.NOT_INSTALLED,
    val compilerState: LanguageCapabilityStatus = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
    val executionState: LanguageCapabilityStatus = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
    val realityState: CapabilityRealityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
    val defaultCompilerOrInterpreter: String? = null,
    val detectedBinaryPath: String? = null,
    val detectedVersion: String? = null,
    val projectTemplates: List<String> = emptyList(),
    val limitations: List<String> = emptyList()
)

data class ProjectCreationRequest(
    val projectName: String,
    val language: String,
    val template: String = "default",
    val description: String = "",
    val dependencies: List<String> = emptyList()
)

data class ProjectCreationResult(
    val projectName: String,
    val rootPath: String,
    val createdFiles: List<String>,
    val language: String,
    val isSuccess: Boolean,
    val message: String
)

class WastiLanguagePlatform(
    private val context: Context,
    private val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    private val nativeProvider: WastiNativeExecutionProvider = WastiNativeExecutionProvider(context, workspaceManager)
) {

    private val profiles = ConcurrentHashMap<String, LanguageSupportProfile>()

    init {
        scanAndRegisterLanguages()
    }

    /**
     * Dynamically scans the system environment and populates the multi-language support matrix.
     */
    fun scanAndRegisterLanguages() {
        val runtimes = nativeProvider.detectRuntimes()

        // 1. Shell Scripting (Bash / Sh)
        val shRuntime = runtimes["SHELL"]
        val isShAvail = shRuntime?.state == RuntimeCapabilityState.AVAILABLE
        registerProfile(
            LanguageSupportProfile(
                languageId = "SHELL",
                displayName = "POSIX Shell / Bash",
                fileExtensions = listOf(".sh", ".bash"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = if (isShAvail) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.UNAVAILABLE,
                compilerState = LanguageCapabilityStatus.SUPPORTED,
                executionState = if (isShAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = if (isShAvail) CapabilityRealityState.NATIVE else CapabilityRealityState.UNAVAILABLE,
                defaultCompilerOrInterpreter = shRuntime?.binaryPath ?: "/system/bin/sh",
                detectedBinaryPath = shRuntime?.binaryPath,
                detectedVersion = "POSIX Standard Shell",
                projectTemplates = listOf("script", "automation"),
                limitations = listOf("Executes in sandboxed workspace directory")
            )
        )

        // 2. Java / Dalvik / ART
        val dalvikRuntime = runtimes["JAVA_DALVIK"]
        val isDalvikAvail = dalvikRuntime?.state == RuntimeCapabilityState.AVAILABLE
        registerProfile(
            LanguageSupportProfile(
                languageId = "JAVA",
                displayName = "Java",
                fileExtensions = listOf(".java", ".jar", ".dex"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = if (isDalvikAvail) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.UNAVAILABLE,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = if (isDalvikAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = if (isDalvikAvail) CapabilityRealityState.NATIVE else CapabilityRealityState.UNAVAILABLE,
                defaultCompilerOrInterpreter = "dalvikvm",
                detectedBinaryPath = dalvikRuntime?.binaryPath,
                detectedVersion = "Android ART/Dalvik VM",
                projectTemplates = listOf("console", "library"),
                limitations = listOf("Direct javac compiler requires SDK toolchain or remote build")
            )
        )

        // 3. Kotlin
        registerProfile(
            LanguageSupportProfile(
                languageId = "KOTLIN",
                displayName = "Kotlin",
                fileExtensions = listOf(".kt", ".kts"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.IMPLEMENTED_NOT_LIVE_VERIFIED,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "kotlinc",
                detectedVersion = "Kotlin 2.0+ (Wasti Core Runtime)",
                projectTemplates = listOf("console", "script", "android_module"),
                limitations = listOf("KTS scripting supported within host process context")
            )
        )

        // 4. Python
        val pyRuntime = runtimes["PYTHON_RUNTIME"]
        val isPyAvail = pyRuntime?.state == RuntimeCapabilityState.AVAILABLE
        registerProfile(
            LanguageSupportProfile(
                languageId = "PYTHON",
                displayName = "Python 3",
                fileExtensions = listOf(".py", ".pyw", ".ipynb"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = if (isPyAvail) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.SUPPORTED,
                executionState = if (isPyAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.NOT_INSTALLED,
                realityState = if (isPyAvail) CapabilityRealityState.LIVE_CONNECTED else CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "python3",
                detectedBinaryPath = pyRuntime?.binaryPath,
                projectTemplates = listOf("script", "package", "cli_app"),
                limitations = if (isPyAvail) emptyList() else listOf("Python3 binary is not installed on stock Android system path")
            )
        )

        // 5. JavaScript / Node.js
        val nodeRuntime = runtimes["NODE_RUNTIME"]
        val isNodeAvail = nodeRuntime?.state == RuntimeCapabilityState.AVAILABLE
        registerProfile(
            LanguageSupportProfile(
                languageId = "JAVASCRIPT",
                displayName = "JavaScript (Node.js / V8)",
                fileExtensions = listOf(".js", ".mjs", ".cjs"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = if (isNodeAvail) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.SUPPORTED,
                executionState = if (isNodeAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.NOT_INSTALLED,
                realityState = if (isNodeAvail) CapabilityRealityState.LIVE_CONNECTED else CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "node",
                detectedBinaryPath = nodeRuntime?.binaryPath,
                projectTemplates = listOf("node_cli", "express_api", "web_app"),
                limitations = if (isNodeAvail) emptyList() else listOf("Node.js binary is not installed on stock Android system path")
            )
        )

        // 6. TypeScript
        registerProfile(
            LanguageSupportProfile(
                languageId = "TYPESCRIPT",
                displayName = "TypeScript",
                fileExtensions = listOf(".ts", ".tsx"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "tsc",
                projectTemplates = listOf("node_ts", "react_ts"),
                limitations = listOf("TypeScript compiler (tsc) requires Node/npm environment or cloud sandbox")
            )
        )

        // 7. C / C++
        val clangPath = listOf("/system/bin/clang", "/system/bin/gcc").find { File(it).exists() }
        val isClangAvail = clangPath != null
        registerProfile(
            LanguageSupportProfile(
                languageId = "CPP",
                displayName = "C / C++",
                fileExtensions = listOf(".cpp", ".c", ".h", ".hpp", ".cc"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = if (isClangAvail) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = if (isClangAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = if (isClangAvail) LanguageCapabilityStatus.AVAILABLE else LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = if (isClangAvail) CapabilityRealityState.NATIVE else CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "clang",
                detectedBinaryPath = clangPath,
                projectTemplates = listOf("native_cli", "cmake_project"),
                limitations = listOf("Requires NDK/Clang toolchain for on-device native binary compilation")
            )
        )

        // 8. C#
        registerProfile(
            LanguageSupportProfile(
                languageId = "CSHARP",
                displayName = "C# (.NET)",
                fileExtensions = listOf(".cs", ".csx"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "dotnet",
                projectTemplates = listOf("console_app", "class_lib"),
                limitations = listOf(".NET SDK toolchain not installed on host device")
            )
        )

        // 9. Go
        registerProfile(
            LanguageSupportProfile(
                languageId = "GO",
                displayName = "Go (Golang)",
                fileExtensions = listOf(".go"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "go",
                projectTemplates = listOf("go_module", "cli_app"),
                limitations = listOf("Go compiler not installed on host device")
            )
        )

        // 10. Rust
        registerProfile(
            LanguageSupportProfile(
                languageId = "RUST",
                displayName = "Rust",
                fileExtensions = listOf(".rs"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "rustc",
                projectTemplates = listOf("cargo_bin", "cargo_lib"),
                limitations = listOf("rustc/cargo toolchain not installed on host device")
            )
        )

        // 11. PHP
        registerProfile(
            LanguageSupportProfile(
                languageId = "PHP",
                displayName = "PHP",
                fileExtensions = listOf(".php"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.SUPPORTED,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "php",
                projectTemplates = listOf("script", "api"),
                limitations = listOf("PHP interpreter CLI not installed on host device")
            )
        )

        // 12. Ruby
        registerProfile(
            LanguageSupportProfile(
                languageId = "RUBY",
                displayName = "Ruby",
                fileExtensions = listOf(".rb"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.SUPPORTED,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "ruby",
                projectTemplates = listOf("script", "gem"),
                limitations = listOf("Ruby runtime not installed on host device")
            )
        )

        // 13. Swift
        registerProfile(
            LanguageSupportProfile(
                languageId = "SWIFT",
                displayName = "Swift",
                fileExtensions = listOf(".swift"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "swift",
                projectTemplates = listOf("package"),
                limitations = listOf("Swift toolchain requires Darwin/Linux cross-compiler or remote sandbox")
            )
        )

        // 14. Dart
        registerProfile(
            LanguageSupportProfile(
                languageId = "DART",
                displayName = "Dart",
                fileExtensions = listOf(".dart"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.NOT_INSTALLED,
                compilerState = LanguageCapabilityStatus.COMPILER_UNAVAILABLE,
                executionState = LanguageCapabilityStatus.EXECUTION_UNAVAILABLE,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                defaultCompilerOrInterpreter = "dart",
                projectTemplates = listOf("dart_cli", "flutter_widget"),
                limitations = listOf("Dart SDK not installed on host device")
            )
        )

        // 15. SQL
        registerProfile(
            LanguageSupportProfile(
                languageId = "SQL",
                displayName = "SQL (SQLite)",
                fileExtensions = listOf(".sql"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "sqlite3",
                detectedVersion = "SQLite 3.x (Android SQLite Native Support)",
                projectTemplates = listOf("migration", "schema"),
                limitations = listOf("Executes through Room Database or Android SQLite driver")
            )
        )

        // 16. HTML / CSS
        registerProfile(
            LanguageSupportProfile(
                languageId = "WEB_MARKUP",
                displayName = "HTML / CSS",
                fileExtensions = listOf(".html", ".css", ".svg"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "Android WebView",
                detectedVersion = "Chromium / WebKit System Engine",
                projectTemplates = listOf("static_page", "dashboard_ui"),
                limitations = listOf("Renderable via Android WebView or UI components")
            )
        )

        // 17. JSON
        registerProfile(
            LanguageSupportProfile(
                languageId = "JSON",
                displayName = "JSON Data Interchange",
                fileExtensions = listOf(".json", ".json5"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "Android JSON Engine",
                detectedVersion = "Native org.json",
                projectTemplates = listOf("config", "schema"),
                limitations = listOf("Validated and formatted via Android native JSON parser")
            )
        )

        // 18. YAML
        registerProfile(
            LanguageSupportProfile(
                languageId = "YAML",
                displayName = "YAML Configuration",
                fileExtensions = listOf(".yaml", ".yml"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "Wasti Configuration Engine",
                projectTemplates = listOf("ci_workflow", "config"),
                limitations = listOf("Validated and formatted via Wasti workspace parser")
            )
        )

        // 19. XML
        registerProfile(
            LanguageSupportProfile(
                languageId = "XML",
                displayName = "XML Markup & Android Manifest",
                fileExtensions = listOf(".xml", ".xsd"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "Android XmlPullParser",
                detectedVersion = "System XML Parser",
                projectTemplates = listOf("layout", "manifest", "values"),
                limitations = listOf("Validated via Android XML parser")
            )
        )

        // 20. Markdown
        registerProfile(
            LanguageSupportProfile(
                languageId = "MARKDOWN",
                displayName = "Markdown Documentation",
                fileExtensions = listOf(".md", ".markdown"),
                codeGenerationSupported = true,
                codeEditingSupported = true,
                codeAnalysisSupported = true,
                runtimeState = RuntimeCapabilityState.AVAILABLE,
                compilerState = LanguageCapabilityStatus.AVAILABLE,
                executionState = LanguageCapabilityStatus.AVAILABLE,
                realityState = CapabilityRealityState.NATIVE,
                defaultCompilerOrInterpreter = "Wasti Markdown Renderer",
                projectTemplates = listOf("doc", "spec"),
                limitations = listOf("Rendered in Compose Markdown components")
            )
        )
    }

    fun registerProfile(profile: LanguageSupportProfile) {
        profiles[profile.languageId.uppercase()] = profile
    }

    fun getProfile(languageId: String): LanguageSupportProfile? {
        val norm = languageId.trim().uppercase()
        return profiles[norm] ?: profiles.values.find {
            it.displayName.contains(norm, ignoreCase = true) ||
            it.fileExtensions.any { ext -> ext.equals(".$languageId", ignoreCase = true) || ext.equals(languageId, ignoreCase = true) }
        }
    }

    fun getAllProfiles(): List<LanguageSupportProfile> {
        return profiles.values.toList()
    }

    /**
     * Creates a structured development project inside the sandboxed workspace.
     */
    fun createProject(request: ProjectCreationRequest): ProjectCreationResult {
        val safeName = request.projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val projectRelativeDir = "projects/$safeName"
        val langProfile = getProfile(request.language)
            ?: return ProjectCreationResult(
                projectName = request.projectName,
                rootPath = "",
                createdFiles = emptyList(),
                language = request.language,
                isSuccess = false,
                message = "Language '${request.language}' is not recognized in Wasti Language Platform."
            )

        val created = mutableListOf<String>()

        // 1. Initialize project structure
        val initRes = workspaceManager.createProjectStructure(safeName)
        if (initRes.isFailure) {
            return ProjectCreationResult(
                projectName = request.projectName,
                rootPath = "",
                createdFiles = emptyList(),
                language = langProfile.displayName,
                isSuccess = false,
                message = "Failed to create directory structure: ${initRes.exceptionOrNull()?.message}"
            )
        }

        // 2. Generate Scaffolding based on language and template
        val readmePath = "$projectRelativeDir/README.md"
        val readmeContent = "# ${request.projectName}\n\nLanguage: ${langProfile.displayName}\nTemplate: ${request.template}\n\n${request.description}\n\nGenerated by Wasti AI OS Native Development Environment."
        workspaceManager.writeFile(readmePath, readmeContent)
        created.add(readmePath)

        when (langProfile.languageId) {
            "PYTHON" -> {
                val mainPy = "$projectRelativeDir/main.py"
                val pyContent = "#!/usr/bin/env python3\n\"\"\"\n${request.projectName} - Created by Wasti AI OS\n\"\"\"\n\ndef main():\n    print(\"Hello from ${request.projectName}!\")\n\nif __name__ == \"__main__\":\n    main()\n"
                workspaceManager.writeFile(mainPy, pyContent)
                created.add(mainPy)

                val reqTxt = "$projectRelativeDir/requirements.txt"
                val reqContent = request.dependencies.joinToString("\n")
                workspaceManager.writeFile(reqTxt, reqContent)
                created.add(reqTxt)

                val testPy = "$projectRelativeDir/test_main.py"
                val testContent = "import unittest\n\nclass TestMain(unittest.TestCase):\n    def test_main(self):\n        self.assertTrue(True)\n\nif __name__ == '__main__':\n    unittest.main()\n"
                workspaceManager.writeFile(testPy, testContent)
                created.add(testPy)
            }
            "KOTLIN" -> {
                val mainKt = "$projectRelativeDir/Main.kt"
                val ktContent = "package $safeName\n\nfun main() {\n    println(\"Hello from ${request.projectName}!\")\n}\n"
                workspaceManager.writeFile(mainKt, ktContent)
                created.add(mainKt)
            }
            "JAVA" -> {
                val mainJava = "$projectRelativeDir/Main.java"
                val javaContent = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from ${request.projectName}!\");\n    }\n}\n"
                workspaceManager.writeFile(mainJava, javaContent)
                created.add(mainJava)
            }
            "JAVASCRIPT" -> {
                val indexJs = "$projectRelativeDir/index.js"
                val jsContent = "console.log('Hello from ${request.projectName}!');\n"
                workspaceManager.writeFile(indexJs, jsContent)
                created.add(indexJs)

                val pkgJson = "$projectRelativeDir/package.json"
                val pkgContent = "{\n  \"name\": \"${safeName.lowercase()}\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\"\n}\n"
                workspaceManager.writeFile(pkgJson, pkgContent)
                created.add(pkgJson)
            }
            "SHELL" -> {
                val scriptSh = "$projectRelativeDir/run.sh"
                val shContent = "#!/bin/sh\necho \"Running ${request.projectName}...\"\n"
                workspaceManager.writeFile(scriptSh, shContent)
                created.add(scriptSh)
            }
            "WEB_MARKUP" -> {
                val indexHtml = "$projectRelativeDir/index.html"
                val htmlContent = "<!DOCTYPE html>\n<html>\n<head>\n  <title>${request.projectName}</title>\n</head>\n<body>\n  <h1>${request.projectName}</h1>\n</body>\n</html>\n"
                workspaceManager.writeFile(indexHtml, htmlContent)
                created.add(indexHtml)
            }
            else -> {
                val srcFile = "$projectRelativeDir/source${langProfile.fileExtensions.firstOrNull() ?: ".txt"}"
                workspaceManager.writeFile(srcFile, "// ${request.projectName} source file\n")
                created.add(srcFile)
            }
        }

        return ProjectCreationResult(
            projectName = request.projectName,
            rootPath = projectRelativeDir,
            createdFiles = created,
            language = langProfile.displayName,
            isSuccess = true,
            message = "Project '${request.projectName}' successfully created with ${created.size} files in $projectRelativeDir."
        )
    }
}
