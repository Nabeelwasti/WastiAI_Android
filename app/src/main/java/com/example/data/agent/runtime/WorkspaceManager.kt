package com.example.data.agent.runtime

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class WorkspaceSnapshotMetadata(
    val snapshotId: String,
    val timestamp: Long,
    val sha256TreeHash: String,
    val fileCount: Int,
    val fileList: List<String>
)

/**
 * Stage 1: Software Filesystem Boundary.
 * All agentic workspace operations MUST occur strictly within:
 * context.filesDir/wasti_workspace/
 *
 * SECURITY NOTICE:
 * WorkspaceManager provides a software-level path boundary.
 * It canonicalizes all paths and rejects directory traversal or external path escapes.
 * It is NOT an OS-level kernel sandbox.
 */
class WorkspaceManager(context: Context) {

    private val workspaceRoot: File = File(context.filesDir, "wasti_workspace").canonicalFile

    init {
        if (!workspaceRoot.exists()) {
            workspaceRoot.mkdirs()
        }
    }

    fun getWorkspaceRootPath(): String = workspaceRoot.canonicalPath

    /**
     * Resolves and validates a relative or absolute path against the canonical workspace root.
     * Uses canonical path resolution and parent-chain verification to prevent:
     * - Directory traversal escaping workspace
     * - Absolute path escaping workspace
     * - Sibling path prefix attacks (e.g. workspace_evil)
     * - Symlink escaping workspace
     */
    fun resolvePathSafely(relativePath: String): Result<File> {
        return try {
            val targetFile = if (File(relativePath).isAbsolute) {
                File(relativePath).canonicalFile
            } else {
                File(workspaceRoot, relativePath).canonicalFile
            }

            if (!isWithinWorkspace(targetFile)) {
                Result.failure(SecurityException("Access denied: path '${targetFile.canonicalPath}' escapes workspace boundary '${workspaceRoot.canonicalPath}'"))
            } else {
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Result.failure(SecurityException("Invalid workspace path: ${e.message}", e))
        }
    }

    private fun isWithinWorkspace(targetFile: File): Boolean {
        var current: File? = targetFile.canonicalFile
        val canonicalRoot = workspaceRoot.canonicalFile
        while (current != null) {
            if (current == canonicalRoot) {
                return true
            }
            current = current.parentFile
        }
        return false
    }

    fun fileExists(relativePath: String): Boolean {
        val resolved = resolvePathSafely(relativePath).getOrNull() ?: return false
        return resolved.exists()
    }

    fun readFile(relativePath: String): Result<String> {
        return resolvePathSafely(relativePath).mapCatching { file ->
            if (!file.exists() || !file.isFile) {
                throw NoSuchFileException(file, null, "File does not exist or is not a regular file")
            }
            file.readText(StandardCharsets.UTF_8)
        }
    }

    fun writeFile(relativePath: String, content: String): Result<Unit> {
        return resolvePathSafely(relativePath).mapCatching { file ->
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText(content, StandardCharsets.UTF_8)
        }
    }

    fun createDirectory(relativePath: String): Result<Unit> {
        return resolvePathSafely(relativePath).mapCatching { dir ->
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    throw IllegalStateException("Failed to create directory at ${dir.canonicalPath}")
                }
            }
        }
    }

    fun listDirectory(relativePath: String): Result<List<String>> {
        return resolvePathSafely(relativePath).mapCatching { dir ->
            if (!dir.exists() || !dir.isDirectory) {
                throw NoSuchFileException(dir, null, "Directory does not exist or is not a directory")
            }
            dir.list()?.toList() ?: emptyList()
        }
    }

    fun deleteFile(relativePath: String): Result<Boolean> {
        return resolvePathSafely(relativePath).mapCatching { file ->
            if (!file.exists()) {
                false
            } else if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }

    fun appendFile(relativePath: String, content: String): Result<Unit> {
        return resolvePathSafely(relativePath).mapCatching { file ->
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.appendText(content, StandardCharsets.UTF_8)
        }
    }

    fun moveFile(sourcePath: String, destPath: String): Result<Unit> {
        return try {
            val src = resolvePathSafely(sourcePath).getOrThrow()
            val dst = resolvePathSafely(destPath).getOrThrow()
            if (!src.exists()) {
                throw NoSuchFileException(src, null, "Source file '$sourcePath' does not exist")
            }
            val parent = dst.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
                src.deleteRecursively()
            } else {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun copyFile(sourcePath: String, destPath: String): Result<Unit> {
        return try {
            val src = resolvePathSafely(sourcePath).getOrThrow()
            val dst = resolvePathSafely(destPath).getOrThrow()
            if (!src.exists()) {
                throw NoSuchFileException(src, null, "Source file '$sourcePath' does not exist")
            }
            val parent = dst.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            if (src.isDirectory) {
                src.copyRecursively(dst, overwrite = true)
            } else {
                src.copyTo(dst, overwrite = true)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun renameFile(sourcePath: String, newName: String): Result<Unit> {
        return try {
            val src = resolvePathSafely(sourcePath).getOrThrow()
            if (!src.exists()) {
                throw NoSuchFileException(src, null, "Source file '$sourcePath' does not exist")
            }
            val parent = src.parentFile ?: workspaceRoot
            val dst = File(parent, newName).canonicalFile
            if (!isWithinWorkspace(dst)) {
                throw SecurityException("Renaming escapes workspace root")
            }
            val success = src.renameTo(dst)
            if (!success) {
                if (src.isDirectory) {
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                } else {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun searchFiles(query: String, startPath: String = ""): Result<List<String>> {
        return resolvePathSafely(startPath).mapCatching { startDir ->
            if (!startDir.exists() || !startDir.isDirectory) {
                return@mapCatching emptyList<String>()
            }
            val matchedPaths = mutableListOf<String>()
            startDir.walkTopDown().filter { it.isFile && it.name != ".snapshots" && !it.path.contains(".snapshots") }.forEach { file ->
                val relPath = file.relativeTo(workspaceRoot).path
                if (file.name.contains(query, ignoreCase = true)) {
                    matchedPaths.add(relPath)
                } else {
                    try {
                        if (file.length() < 1_000_000) { // Limit inspection to files under 1MB
                            val text = file.readText(StandardCharsets.UTF_8)
                            if (text.contains(query, ignoreCase = true)) {
                                matchedPaths.add(relPath)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            matchedPaths
        }
    }

    fun inspectMetadata(relativePath: String): Result<Map<String, Any>> {
        return resolvePathSafely(relativePath).mapCatching { file ->
            if (!file.exists()) {
                throw NoSuchFileException(file, null, "File does not exist")
            }
            mapOf(
                "name" to file.name,
                "path" to file.relativeTo(workspaceRoot).path,
                "canonicalPath" to file.canonicalPath,
                "isDirectory" to file.isDirectory,
                "isFile" to file.isFile,
                "length" to file.length(),
                "lastModified" to file.lastModified(),
                "canRead" to file.canRead(),
                "canWrite" to file.canWrite()
            )
        }
    }

    /**
     * Stage 3 Task 5: Autonomous Workspace Project Creation
     */
    fun createProjectStructure(projectName: String): Result<Unit> {
        val root = "projects/$projectName"
        return try {
            createDirectory("$root/src/main/kotlin")
            createDirectory("$root/src/test/kotlin")
            createDirectory("$root/docs")
            writeFile("$root/README.md", "# $projectName\nAutonomous project generated by Wasti AI OS.")
            writeFile(
                "$root/build.gradle.kts",
                "plugins {\n    kotlin(\"jvm\") version \"2.0.21\"\n}\n"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Stage 3 Task 5: Workspace Snapshot Creation with Deterministic SHA-256 Tree Hash
     */
    fun createSnapshot(snapshotId: String): Result<String> {
        val snapshotsDir = File(workspaceRoot, ".snapshots/$snapshotId")
        return try {
            if (snapshotsDir.exists()) {
                snapshotsDir.deleteRecursively()
            }
            snapshotsDir.mkdirs()

            // Copy workspace files excluding .snapshots
            copyDirectoryExcludingSnapshots(workspaceRoot, snapshotsDir)

            // Compute file list & SHA-256 tree digest
            val fileList = mutableListOf<String>()
            val digest = MessageDigest.getInstance("SHA-256")
            snapshotsDir.walkTopDown().filter { it.isFile && it.name != "snapshot_metadata.json" }.forEach { file ->
                val rel = file.relativeTo(snapshotsDir).path
                fileList.add(rel)
                digest.update(rel.toByteArray(StandardCharsets.UTF_8))
                digest.update(file.readBytes())
            }
            val treeHash = digest.digest().joinToString("") { "%02x".format(it) }

            val metadata = WorkspaceSnapshotMetadata(
                snapshotId = snapshotId,
                timestamp = System.currentTimeMillis(),
                sha256TreeHash = treeHash,
                fileCount = fileList.size,
                fileList = fileList
            )

            val metaJson = org.json.JSONObject().apply {
                put("snapshotId", metadata.snapshotId)
                put("timestamp", metadata.timestamp)
                put("sha256TreeHash", metadata.sha256TreeHash)
                put("fileCount", metadata.fileCount)
                put("fileList", org.json.JSONArray(metadata.fileList))
            }
            File(snapshotsDir, "snapshot_metadata.json").writeText(metaJson.toString(), StandardCharsets.UTF_8)

            Result.success(snapshotId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun restoreSnapshot(snapshotId: String): Result<Unit> {
        val snapshotsDir = File(workspaceRoot, ".snapshots/$snapshotId")
        return try {
            if (!snapshotsDir.exists()) {
                throw NoSuchFileException(snapshotsDir, null, "Snapshot $snapshotId does not exist")
            }

            // Clear current workspace files excluding .snapshots
            workspaceRoot.listFiles()?.forEach { file ->
                if (file.name != ".snapshots") {
                    file.deleteRecursively()
                }
            }

            // Restore from snapshot excluding metadata file
            snapshotsDir.listFiles()?.forEach { file ->
                if (file.name != "snapshot_metadata.json") {
                    val target = File(workspaceRoot, file.name)
                    if (file.isDirectory) {
                        file.copyRecursively(target, overwrite = true)
                    } else {
                        file.copyTo(target, overwrite = true)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getSnapshotMetadata(snapshotId: String): WorkspaceSnapshotMetadata? {
        val metaFile = File(workspaceRoot, ".snapshots/$snapshotId/snapshot_metadata.json")
        if (!metaFile.exists()) return null
        return try {
            val json = org.json.JSONObject(metaFile.readText(StandardCharsets.UTF_8))
            val arr = json.optJSONArray("fileList") ?: org.json.JSONArray()
            val files = (0 until arr.length()).map { arr.getString(it) }
            WorkspaceSnapshotMetadata(
                snapshotId = json.getString("snapshotId"),
                timestamp = json.getLong("timestamp"),
                sha256TreeHash = json.getString("sha256TreeHash"),
                fileCount = json.getInt("fileCount"),
                fileList = files
            )
        } catch (e: Exception) {
            null
        }
    }

    fun listSnapshots(): List<String> {
        val snapshotsDir = File(workspaceRoot, ".snapshots")
        if (!snapshotsDir.exists()) return emptyList()
        return snapshotsDir.list()?.toList() ?: emptyList()
    }

    private fun copyDirectoryExcludingSnapshots(source: File, target: File) {
        source.listFiles()?.forEach { file ->
            if (file.name != ".snapshots") {
                val dest = File(target, file.name)
                if (file.isDirectory) {
                    dest.mkdirs()
                    copyDirectoryExcludingSnapshots(file, dest)
                } else {
                    file.copyTo(dest, overwrite = true)
                }
            }
        }
    }
}
