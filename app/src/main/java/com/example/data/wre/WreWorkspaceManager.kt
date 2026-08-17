package com.example.data.wre

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 9A: WRE Workspace Manager
 *
 * Dedicated, structured virtual workspace layout inside Android storage:
 * WastiWorkspace/
 * ├── home/wasti/
 * ├── projects/
 * ├── scripts/
 * ├── data/
 * ├── downloads/
 * ├── outputs/
 * ├── temp/
 * ├── logs/
 * ├── cache/
 * └── config/
 */
class WreWorkspaceManager(context: Context) {

    private val rootDir: File = File(context.filesDir, "WastiWorkspace").canonicalFile

    val standardFolders = listOf(
        "home/wasti",
        "projects",
        "scripts",
        "data",
        "downloads",
        "outputs",
        "temp",
        "logs",
        "cache",
        "config"
    )

    init {
        ensureWorkspaceHierarchy()
    }

    fun ensureWorkspaceHierarchy() {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        for (folder in standardFolders) {
            val dir = File(rootDir, folder)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    fun getRootPath(): String = rootDir.canonicalPath

    fun getHomeDirectory(): File = File(rootDir, "home/wasti").canonicalFile

    /**
     * Resolves a virtual workspace relative path (or absolute path inside workspace).
     * Prevents path traversal, sibling collision, and symlink escape attacks.
     */
    fun resolve(pathStr: String): Result<File> {
        return try {
            val trimmed = pathStr.trim()
            val file = if (trimmed.startsWith("/") || (trimmed.length > 2 && trimmed[1] == ':')) {
                // If absolute path, check if inside workspace
                val absoluteCandidate = File(trimmed).canonicalFile
                if (isWithinRoot(absoluteCandidate)) {
                    absoluteCandidate
                } else if (trimmed.startsWith("/workspace") || trimmed.startsWith("/home")) {
                    // Strip leading virtual slash
                    File(rootDir, trimmed.removePrefix("/")).canonicalFile
                } else {
                    return Result.failure(SecurityException("Access denied: path '$pathStr' escapes Wasti Workspace"))
                }
            } else if (trimmed.startsWith("~")) {
                val rel = trimmed.removePrefix("~").removePrefix("/")
                File(getHomeDirectory(), rel).canonicalFile
            } else {
                File(rootDir, trimmed).canonicalFile
            }

            if (!isWithinRoot(file)) {
                Result.failure(SecurityException("Access denied: path '$pathStr' escapes Wasti Workspace"))
            } else {
                Result.success(file)
            }
        } catch (e: Exception) {
            Result.failure(SecurityException("Failed to resolve workspace path: ${e.message}", e))
        }
    }

    private fun isWithinRoot(candidate: File): Boolean {
        var curr: File? = candidate.canonicalFile
        val canonicalRoot = rootDir.canonicalFile
        while (curr != null) {
            if (curr == canonicalRoot) return true
            curr = curr.parentFile
        }
        return false
    }

    fun getVirtualPath(file: File): String {
        val canonical = file.canonicalFile
        val rootPath = rootDir.canonicalPath
        return if (canonical.canonicalPath.startsWith(rootPath)) {
            val relative = canonical.canonicalPath.removePrefix(rootPath).removePrefix("/")
            if (relative.isEmpty()) "/" else "/$relative"
        } else {
            canonical.name
        }
    }

    fun cleanupTemp() {
        val tempDir = File(rootDir, "temp")
        if (tempDir.exists() && tempDir.isDirectory) {
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}
