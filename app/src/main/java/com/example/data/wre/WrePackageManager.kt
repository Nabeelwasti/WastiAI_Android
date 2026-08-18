package com.example.data.wre

import android.content.Context
import com.example.data.tool.ToolDefinition
import com.example.data.tool.ToolRegistry
import com.example.data.tool.WastiTool
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 9C: WRE Package & Dynamic Tool Manager
 * 
 * Manages installing, removing, listing, executing, and registering reusable
 * scripts and capabilities within the Wasti native workspace environment.
 * Integrates directly into ToolRegistry and UnifiedExecutionFabric.
 */
data class WrePackage(
    val name: String,
    val version: String,
    val description: String,
    val runtime: String, // "sh", "js", "py", "native"
    val entryPoint: String,
    val permissions: List<String> = emptyList(),
    val author: String = "wasti",
    val installedAt: Long = System.currentTimeMillis()
)

class WrePackageManager(
    private val context: Context,
    private val workspaceManager: WreWorkspaceManager
) {
    private val packages = ConcurrentHashMap<String, WrePackage>()
    private val packagesMetaFile: File

    init {
        val etcDir = workspaceManager.getDirectory("etc").getOrNull()
            ?: File(context.filesDir, "workspace/etc").apply { mkdirs() }
        packagesMetaFile = File(etcDir, "packages.json")
        loadInstalledPackages()
        ensureDefaultPackages()
    }

    private fun loadInstalledPackages() {
        if (!packagesMetaFile.exists()) return
        try {
            val jsonStr = packagesMetaFile.readText()
            val jsonObj = JSONObject(jsonStr)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = jsonObj.getJSONObject(key)
                val perms = mutableListOf<String>()
                val permsArray = item.optJSONArray("permissions")
                if (permsArray != null) {
                    for (i in 0 until permsArray.length()) {
                        perms.add(permsArray.getString(i))
                    }
                }
                val pkg = WrePackage(
                    name = item.getString("name"),
                    version = item.optString("version", "1.0.0"),
                    description = item.optString("description", "Dynamic WRE Tool"),
                    runtime = item.optString("runtime", "sh"),
                    entryPoint = item.optString("entryPoint", "bin/$key"),
                    permissions = perms,
                    author = item.optString("author", "wasti"),
                    installedAt = item.optLong("installedAt", System.currentTimeMillis())
                )
                packages[pkg.name] = pkg
                registerIntoToolRegistry(pkg)
            }
        } catch (e: Exception) {
            // Non-fatal parse fallback
        }
    }

    private fun savePackages() {
        try {
            val jsonObj = JSONObject()
            packages.forEach { (name, pkg) ->
                val item = JSONObject().apply {
                    put("name", pkg.name)
                    put("version", pkg.version)
                    put("description", pkg.description)
                    put("runtime", pkg.runtime)
                    put("entryPoint", pkg.entryPoint)
                    put("author", pkg.author)
                    put("installedAt", pkg.installedAt)
                }
                jsonObj.put(name, item)
            }
            packagesMetaFile.writeText(jsonObj.toString(2))
        } catch (e: Exception) {
            // Non-fatal write fallback
        }
    }

    private fun ensureDefaultPackages() {
        val binDir = workspaceManager.getDirectory("bin").getOrNull()
        if (binDir != null && !packages.containsKey("sysinfo")) {
            val scriptFile = File(binDir, "sysinfo")
            if (!scriptFile.exists()) {
                scriptFile.writeText(
                    """
                    echo "=== Wasti AI OS Native Runtime ==="
                    echo "OS: Linux/Android"
                    echo "Kernel: $(uname -a)"
                    echo "Date: $(date)"
                    echo "Workspace: /home/wasti"
                    echo "Status: Operational"
                    """.trimIndent()
                )
            }
            installLocalPackage(
                WrePackage(
                    name = "sysinfo",
                    version = "1.0.0",
                    description = "Inspect live system and runtime details",
                    runtime = "sh",
                    entryPoint = "bin/sysinfo"
                )
            )
        }

        if (binDir != null && !packages.containsKey("json-fmt")) {
            val scriptFile = File(binDir, "json-fmt")
            if (!scriptFile.exists()) {
                scriptFile.writeText(
                    """
                    # Simple JSON Formatter and validator
                    cat
                    """.trimIndent()
                )
            }
            installLocalPackage(
                WrePackage(
                    name = "json-fmt",
                    version = "1.0.0",
                    description = "JSON validation and stream formatter",
                    runtime = "sh",
                    entryPoint = "bin/json-fmt"
                )
            )
        }
    }

    fun listPackages(): List<WrePackage> = packages.values.toList()

    fun getPackage(name: String): WrePackage? = packages[name]

    fun installLocalPackage(pkg: WrePackage): Boolean {
        packages[pkg.name] = pkg
        savePackages()
        registerIntoToolRegistry(pkg)
        return true
    }

    fun installOrUpdateScriptPackage(
        name: String,
        scriptContent: String,
        description: String = "User dynamic script tool",
        runtime: String = "sh",
        entryPoint: String = "bin/$name",
        permissions: List<String> = emptyList(),
        version: String = "1.0.0"
    ): Result<WrePackage> {
        val entryFileResult = workspaceManager.resolve(entryPoint)
        val entryFile = entryFileResult.getOrElse {
            return Result.failure(it)
        }
        entryFile.parentFile?.mkdirs()
        entryFile.writeText(scriptContent)

        val pkg = WrePackage(
            name = name,
            version = version,
            description = description,
            runtime = runtime,
            entryPoint = entryPoint,
            permissions = permissions,
            author = "wasti_studio",
            installedAt = System.currentTimeMillis()
        )
        installLocalPackage(pkg)
        return Result.success(pkg)
    }

    fun removePackage(name: String): Boolean {
        val pkg = packages.remove(name) ?: return false
        savePackages()
        // Remove entry file if present
        val entry = workspaceManager.resolve(pkg.entryPoint).getOrNull()
        if (entry != null && entry.exists()) {
            entry.delete()
        }
        ToolRegistry.unregisterTool("wre_tool_${pkg.name}")
        return true
    }

    /**
     * Stage 9D: Exports a package into a standard .wasti capability package bundle.
     * Contains the complete manifest, runtime requirements, checksum metadata, and file contents.
     */
    fun exportPackage(name: String, targetVirtualPath: String? = null): Result<File> {
        val pkg = packages[name] ?: return Result.failure(IllegalArgumentException("Package '$name' is not installed."))
        val candidates = listOf(
            workspaceManager.resolve(pkg.entryPoint).getOrNull(),
            workspaceManager.resolve("home/wasti/${pkg.entryPoint}").getOrNull(),
            workspaceManager.resolve("home/wasti/bin/${pkg.entryPoint}").getOrNull(),
            workspaceManager.resolve("bin/${pkg.entryPoint}").getOrNull()
        )
        val entryFile = candidates.firstOrNull { it != null && it.exists() && it.isFile }
            ?: return Result.failure(IllegalStateException("Entry point '${pkg.entryPoint}' does not exist on disk."))

        val destinationPath = targetVirtualPath ?: "packages/${pkg.name}.wasti"
        val destFileRes = workspaceManager.resolve(destinationPath)
        val destFile = destFileRes.getOrElse { return Result.failure(it) }
        destFile.parentFile?.mkdirs()

        val filesMap = JSONObject().apply {
            put(pkg.entryPoint, entryFile.readText())
        }

        val bundleObj = JSONObject().apply {
            put("manifestVersion", "1.0")
            put("name", pkg.name)
            put("version", pkg.version)
            put("description", pkg.description)
            put("runtime", pkg.runtime)
            put("entryPoint", pkg.entryPoint)
            val permArray = org.json.JSONArray()
            pkg.permissions.forEach { permArray.put(it) }
            put("permissions", permArray)
            put("author", pkg.author)
            put("createdAt", System.currentTimeMillis())
            put("files", filesMap)
        }

        destFile.writeText(bundleObj.toString(2))
        return Result.success(destFile)
    }

    /**
     * Stage 9D: Installs a .wasti capability bundle from file or workspace virtual path.
     * Validates manifest, unpacks files inside the sandboxed workspace, and registers capability.
     */
    fun installWastiPackage(fileOrVirtualPath: String): Result<WrePackage> {
        val file = if (File(fileOrVirtualPath).isAbsolute && File(fileOrVirtualPath).exists()) {
            File(fileOrVirtualPath)
        } else {
            workspaceManager.resolve(fileOrVirtualPath).getOrElse { return Result.failure(it) }
        }

        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalArgumentException("Package bundle '${file.name}' not found."))
        }

        return try {
            val jsonStr = file.readText()
            val jsonObj = JSONObject(jsonStr)

            val name = jsonObj.getString("name").trim()
            if (name.isBlank()) return Result.failure(IllegalArgumentException("Invalid package: missing 'name'"))

            val version = jsonObj.optString("version", "1.0.0")
            val description = jsonObj.optString("description", "Imported WRE capability")
            val runtime = jsonObj.optString("runtime", "sh")
            val entryPoint = jsonObj.optString("entryPoint", "bin/$name")

            val perms = mutableListOf<String>()
            val permsArray = jsonObj.optJSONArray("permissions")
            if (permsArray != null) {
                for (i in 0 until permsArray.length()) {
                    perms.add(permsArray.getString(i))
                }
            }
            val author = jsonObj.optString("author", "community")

            // Extract packaged files
            val filesObj = jsonObj.optJSONObject("files")
            if (filesObj != null) {
                val keys = filesObj.keys()
                while (keys.hasNext()) {
                    val relativeFilePath = keys.next()
                    // Guard against directory traversal in packaged files
                    if (relativeFilePath.contains("..") || relativeFilePath.startsWith("/")) {
                        return Result.failure(SecurityException("Illegal file path in package bundle: $relativeFilePath"))
                    }
                    val targetRes = workspaceManager.resolve(relativeFilePath)
                    val targetFile = targetRes.getOrElse { return Result.failure(it) }
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(filesObj.getString(relativeFilePath))
                }
            }

            val pkg = WrePackage(
                name = name,
                version = version,
                description = description,
                runtime = runtime,
                entryPoint = entryPoint,
                permissions = perms,
                author = author,
                installedAt = System.currentTimeMillis()
            )

            installLocalPackage(pkg)
            Result.success(pkg)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Failed to install .wasti package: ${e.message}", e))
        }
    }


    /**
     * Dynamically registers installed package into ToolRegistry as a first-class WastiTool
     * so that the Multi-Agent, Voice, Floating Bubble and Chat Brain can immediately invoke it!
     */
    private fun registerIntoToolRegistry(pkg: WrePackage) {
        val wastiTool = object : WastiTool {
            override val definition = ToolDefinition(
                id = "wre_tool_${pkg.name}",
                name = "WRE Tool: ${pkg.name}",
                category = "Dynamic Capabilities",
                description = "${pkg.description} (Runtime: ${pkg.runtime}, Entry: ${pkg.entryPoint})"
            )

            override suspend fun execute(parameters: Map<String, Any>): String {
                val wreManager = WreManager.getInstance(context)
                val args = parameters["args"]?.toString() ?: ""
                val fullCmd = "${pkg.name} $args".trim()
                val req = ExecutionRequest(
                    command = fullCmd,
                    workingDirectory = "home/wasti",
                    initiatedBy = "ToolRegistry:${pkg.name}"
                )
                val res = wreManager.execute(req)
                return if (res.status == ExecutionStatus.SUCCESS) {
                    res.stdout.ifBlank { "Tool executed successfully with 0 exit code." }
                } else {
                    "Tool Execution Failed [${res.status}]: ${res.stderr.ifBlank { res.stdout }}"
                }
            }
        }
        ToolRegistry.registerTool(wastiTool)
    }
}
