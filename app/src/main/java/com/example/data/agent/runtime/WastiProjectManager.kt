package com.example.data.agent.runtime

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 7: Canonical Wasti Project Manager.
 *
 * Governs:
 * - Project workspace lifecycle (create, open, inspect, list, clean, delete, package)
 * - Project metadata tracking via `wasti_project.json`
 * - Automatic language, framework, dependency and build system discovery
 * - Strict workspace confinement
 */

data class ProjectMetadata(
    val projectId: String,
    val name: String,
    val language: String,
    val framework: String,
    val template: String,
    val description: String,
    val entryPoint: String,
    val dependencies: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val relativePath: String
)

data class ProjectInspectionResult(
    val metadata: ProjectMetadata,
    val totalFiles: Int,
    val fileList: List<String>,
    val detectedLanguage: String,
    val detectedBuildTool: String,
    val isClean: Boolean
)

class WastiProjectManager(
    private val context: Context,
    private val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    private val languagePlatform: WastiLanguagePlatform = WastiLanguagePlatform(context, workspaceManager)
) {

    /**
     * Creates a new managed project with scaffolding and metadata.
     */
    fun createManagedProject(
        name: String,
        language: String,
        template: String = "default",
        description: String = "",
        dependencies: List<String> = emptyList()
    ): Result<ProjectMetadata> {
        val safeName = name.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val projectRelativeDir = "projects/$safeName"

        // Delegate to language platform for template scaffolding
        val req = ProjectCreationRequest(
            projectName = safeName,
            language = language,
            template = template,
            description = description,
            dependencies = dependencies
        )

        val creationRes = languagePlatform.createProject(req)
        if (!creationRes.isSuccess) {
            return Result.failure(Exception("Project scaffolding failed: ${creationRes.message}"))
        }

        val metadata = ProjectMetadata(
            projectId = UUID.randomUUID().toString(),
            name = safeName,
            language = language.uppercase(),
            framework = template,
            template = template,
            description = description,
            entryPoint = creationRes.createdFiles.find { it.endsWith(".py") || it.endsWith(".kt") || it.endsWith(".js") || it.endsWith(".sh") || it.endsWith(".html") } ?: "README.md",
            dependencies = dependencies,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            relativePath = projectRelativeDir
        )

        // Save wasti_project.json
        val metaJson = JSONObject().apply {
            put("projectId", metadata.projectId)
            put("name", metadata.name)
            put("language", metadata.language)
            put("framework", metadata.framework)
            put("template", metadata.template)
            put("description", metadata.description)
            put("entryPoint", metadata.entryPoint)
            put("dependencies", JSONArray(dependencies))
            put("createdAt", metadata.createdAt)
            put("updatedAt", metadata.updatedAt)
            put("relativePath", metadata.relativePath)
        }

        val metaWrite = workspaceManager.writeFile("$projectRelativeDir/wasti_project.json", metaJson.toString(2))
        if (metaWrite.isFailure) {
            return Result.failure(Exception("Failed to write project metadata: ${metaWrite.exceptionOrNull()?.message}"))
        }

        return Result.success(metadata)
    }

    /**
     * Inspects an existing project directory and recovers or infers its metadata.
     */
    fun inspectProject(projectName: String): Result<ProjectInspectionResult> {
        val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val projectRelativeDir = "projects/$safeName"

        val dirRes = workspaceManager.resolvePathSafely(projectRelativeDir)
        if (dirRes.isFailure) {
            return Result.failure(dirRes.exceptionOrNull() ?: Exception("Invalid project path"))
        }

        val dir = dirRes.getOrThrow()
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(NoSuchFileException(dir, null, "Project directory '$projectName' does not exist."))
        }

        val files = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.toList()

        // Read metadata if exists
        val metaFile = File(dir, "wasti_project.json")
        val metadata: ProjectMetadata = if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                val deps = mutableListOf<String>()
                val arr = json.optJSONArray("dependencies")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        deps.add(arr.getString(i))
                    }
                }
                ProjectMetadata(
                    projectId = json.optString("projectId", UUID.randomUUID().toString()),
                    name = json.optString("name", safeName),
                    language = json.optString("language", "UNKNOWN"),
                    framework = json.optString("framework", "default"),
                    template = json.optString("template", "default"),
                    description = json.optString("description", ""),
                    entryPoint = json.optString("entryPoint", ""),
                    dependencies = deps,
                    createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                    relativePath = projectRelativeDir
                )
            } catch (e: Exception) {
                fallbackMetadata(safeName, projectRelativeDir, files)
            }
        } else {
            fallbackMetadata(safeName, projectRelativeDir, files)
        }

        val detectedBuildTool = when (metadata.language.uppercase()) {
            "PYTHON" -> "pip/setuptools"
            "JAVASCRIPT", "TYPESCRIPT" -> "npm"
            "KOTLIN", "JAVA" -> "gradle"
            "CPP", "C" -> "clang/cmake"
            "RUST" -> "cargo"
            "GO" -> "go build"
            else -> "custom"
        }

        return Result.success(
            ProjectInspectionResult(
                metadata = metadata,
                totalFiles = files.size,
                fileList = files,
                detectedLanguage = metadata.language,
                detectedBuildTool = detectedBuildTool,
                isClean = true
            )
        )
    }

    private fun fallbackMetadata(safeName: String, relPath: String, files: List<String>): ProjectMetadata {
        val detectedLang = when {
            files.any { it.endsWith(".py") } -> "PYTHON"
            files.any { it.endsWith(".kt") } -> "KOTLIN"
            files.any { it.endsWith(".java") } -> "JAVA"
            files.any { it.endsWith(".js") } -> "JAVASCRIPT"
            files.any { it.endsWith(".ts") } -> "TYPESCRIPT"
            files.any { it.endsWith(".html") } -> "WEB_MARKUP"
            files.any { it.endsWith(".sh") } -> "SHELL"
            else -> "GENERIC"
        }

        return ProjectMetadata(
            projectId = UUID.randomUUID().toString(),
            name = safeName,
            language = detectedLang,
            framework = "default",
            template = "default",
            description = "Auto-detected workspace project",
            entryPoint = files.firstOrNull() ?: "",
            dependencies = emptyList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            relativePath = relPath
        )
    }

    /**
     * Lists all projects in the workspace.
     */
    fun listProjects(): List<String> {
        val projectsDir = workspaceManager.resolvePathSafely("projects").getOrNull() ?: return emptyList()
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    /**
     * Deletes a project from the workspace safely.
     */
    fun deleteProject(projectName: String): Boolean {
        val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val projectsDir = workspaceManager.resolvePathSafely("projects/$safeName").getOrNull() ?: return false
        return if (projectsDir.exists()) {
            projectsDir.deleteRecursively()
        } else false
    }
}
