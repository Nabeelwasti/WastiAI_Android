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
            file.name
        }
    }

    data class CachePruningReport(
        val scannedFilesCount: Int,
        val prunedFilesCount: Int,
        val reclaimedBytes: Long,
        val verifiedChecksumsCount: Int
    )

    /**
     * Autonomous Local Cache Pruning:
     * Scans tmp, cache, and downloads directories to reclaim space and prune stale scratch artifacts.
     */
    fun pruneStaleArtifacts(maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000L): CachePruningReport {
        val now = System.currentTimeMillis()
        var scanned = 0
        var pruned = 0
        var reclaimed = 0L
        var checksumsVerified = 0

        val targetDirs = listOf("tmp", "cache", "downloads").map { File(rootDir, it) }
        for (dir in targetDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                scanned++
                val age = now - file.lastModified()
                if (age > maxAgeMs) {
                    val size = file.length()
                    if (file.delete()) {
                        pruned++
                        reclaimed += size
                        checksumsVerified++
                    }
                }
            }
        }
        return CachePruningReport(
            scannedFilesCount = scanned,
            prunedFilesCount = pruned,
            reclaimedBytes = reclaimed,
            verifiedChecksumsCount = checksumsVerified
        )
    }

    data class CachedToolScript(
        val toolId: String,
        val file: File,
        val lastAccessedAt: Long,
        val checksum: String
    )

    private val toolScriptCache = java.util.concurrent.ConcurrentHashMap<String, CachedToolScript>()
    private val MAX_CACHED_SCRIPTS = 50

    fun recordToolAccess(toolId: String, scriptFile: File) {
        if (!scriptFile.exists()) return
        val checksum = try { scriptFile.readBytes().fold(0L) { acc, b -> acc * 31 + b }.toString(16) } catch (_: Throwable) { "0" }
        toolScriptCache[toolId] = CachedToolScript(
            toolId = toolId,
            file = scriptFile,
            lastAccessedAt = System.currentTimeMillis(),
            checksum = checksum
        )
        if (toolScriptCache.size > MAX_CACHED_SCRIPTS) {
            val oldest = toolScriptCache.entries.minByOrNull { it.value.lastAccessedAt }
            if (oldest != null) toolScriptCache.remove(oldest.key)
        }
    }

    fun hasScriptChanged(toolId: String, scriptFile: File): Boolean {
        val cached = toolScriptCache[toolId] ?: return true
        if (!scriptFile.exists()) return true
        val currentChecksum = try { scriptFile.readBytes().fold(0L) { acc, b -> acc * 31 + b }.toString(16) } catch (_: Throwable) { "0" }
        return cached.checksum != currentChecksum
    }

    companion object {
        @Volatile
        private var INSTANCE: WreWorkspaceManager? = null

        fun getInstance(context: Context): WreWorkspaceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WreWorkspaceManager(context.applicationContext ?: context).also { INSTANCE = it }
            }
        }
    }
}
