package com.example.data.wre

import android.content.Context
import java.io.File

/**
 * Stage 9A & 9C: Wasti Sandboxed Virtual Workspace Manager
 * 
 * Maps virtual POSIX paths (/home/wasti/...) to sandboxed app-internal storage.
 * Prevents path traversal and guarantees safe execution boundaries.
 */
class WreWorkspaceManager(context: Context) {

    val rootDir: File = File(context.filesDir, "WastiWorkspace").apply {
        if (!exists()) mkdirs()
    }

    val standardFolders = listOf(
        "home/wasti",
        "home/wasti/bin",
        "home/wasti/src",
        "home/wasti/data",
        "home/wasti/logs",
        "tmp",
        "etc",
        "projects",
        "scripts",
        "downloads",
        "outputs",
        "cache",
        "config"
    )

    init {
        // Initialize standard virtual filesystem hierarchy
        for (folder in standardFolders) {
            File(rootDir, folder).mkdirs()
        }
    }

    fun getRootPath(): String = rootDir.canonicalPath

    fun getRootDirectory(): File = rootDir

    fun getHomeDirectory(): File = File(rootDir, "home/wasti").canonicalFile

    fun getDirectory(subPath: String): Result<File> {
        val dir = File(rootDir, subPath.removePrefix("/"))
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return Result.success(dir)
    }

    /**
     * Resolves a virtual path safely inside the sandbox.
     * Rejects path traversal attacks (e.g. "../../../etc/shadow")
     */
    fun resolve(virtualPath: String): Result<File> {
        return try {
            val sanitized = virtualPath.trim()
                .removePrefix("/")
                .replace("\\", "/")
            val target = File(rootDir, sanitized).canonicalFile
            val rootCanonical = rootDir.canonicalPath
            if (target.canonicalPath.startsWith(rootCanonical)) {
                Result.success(target)
            } else {
                Result.failure(SecurityException("Access Denied: Path traversal outside sandbox detected ($virtualPath)"))
            }
        } catch (e: Exception) {
            Result.failure(SecurityException("Failed to resolve workspace path: ${e.message}", e))
        }
    }

    fun getVirtualPath(file: File): String {
        val rootCanonical = rootDir.canonicalPath
        val fileCanonical = file.canonicalPath
        return if (fileCanonical.startsWith(rootCanonical)) {
            val relative = fileCanonical.removePrefix(rootCanonical).removePrefix("/")
            if (relative.isEmpty()) "/" else "/$relative"
        } else {
            fileCanonical.name
        }
    }
}
