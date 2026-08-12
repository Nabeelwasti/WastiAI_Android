package com.example.data.agent.runtime

class ReadFileTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "read_file"
    override val description: String = "Reads utf-8 text file content safely within the workspace boundary."
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'path'")

        val result = workspaceManager.readFile(path)
        return if (result.isSuccess) {
            mapOf("success" to true, "content" to result.getOrNull().orEmpty())
        } else {
            mapOf("success" to false, "error" to (result.exceptionOrNull()?.message ?: "Read error"))
        }
    }
}

class WriteFileTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "write_file"
    override val description: String = "Writes utf-8 text content to a file safely within the workspace boundary."
    override val permissionLevel: PermissionLevel = PermissionLevel.CONTROLLED

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'path'")
        val content = input["content"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'content'")

        val result = workspaceManager.writeFile(path, content)
        return if (result.isSuccess) {
            mapOf("success" to true, "message" to "File written successfully")
        } else {
            mapOf("success" to false, "error" to (result.exceptionOrNull()?.message ?: "Write error"))
        }
    }
}

class ListFilesTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "list_files"
    override val description: String = "Lists files and subdirectories in a workspace path safely."
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String ?: "."
        val result = workspaceManager.listDirectory(path)
        return if (result.isSuccess) {
            mapOf("success" to true, "files" to result.getOrNull().orEmpty())
        } else {
            mapOf("success" to false, "error" to (result.exceptionOrNull()?.message ?: "List directory error"))
        }
    }
}

class FileExistsTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "file_exists"
    override val description: String = "Checks if a file or directory exists within the workspace boundary."
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'path'")

        val exists = workspaceManager.fileExists(path)
        return mapOf("success" to true, "exists" to exists)
    }
}

class CreateDirectoryTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "create_directory"
    override val description: String = "Creates a new directory structure within the workspace boundary."
    override val permissionLevel: PermissionLevel = PermissionLevel.SAFE

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'path'")

        val result = workspaceManager.createDirectory(path)
        return if (result.isSuccess) {
            mapOf("success" to true, "message" to "Directory created successfully")
        } else {
            mapOf("success" to false, "error" to (result.exceptionOrNull()?.message ?: "Directory creation error"))
        }
    }
}

class PatchFileTool(
    private val workspaceManager: WorkspaceManager
) : AgentTool {
    override val name: String = "patch_file"
    override val description: String = "Patches existing file content safely within the workspace boundary."
    override val permissionLevel: PermissionLevel = PermissionLevel.CONTROLLED

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val path = input["path"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'path'")
        val targetContent = input["targetContent"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'targetContent'")
        val replacementContent = input["replacementContent"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'replacementContent'")

        val readResult = workspaceManager.readFile(path)
        if (readResult.isFailure) {
            return mapOf("success" to false, "error" to (readResult.exceptionOrNull()?.message ?: "File read error"))
        }

        val originalText = readResult.getOrNull().orEmpty()
        if (!originalText.contains(targetContent)) {
            return mapOf("success" to false, "error" to "Target content not found in file")
        }

        val updatedText = originalText.replace(targetContent, replacementContent)
        val writeResult = workspaceManager.writeFile(path, updatedText)

        return if (writeResult.isSuccess) {
            mapOf("success" to true, "message" to "File patched successfully")
        } else {
            mapOf("success" to false, "error" to (writeResult.exceptionOrNull()?.message ?: "Write error"))
        }
    }
}

/**
 * Non-operational placeholder command execution tool.
 * Strictly returns NOT_ENABLED in Stage 1 & Stage 2.
 */
class ExecuteLocalCommandToolStub : AgentTool {
    override val name: String = "execute_command"
    override val description: String = "Placeholder command tool. Disabled in Stage 1 & Stage 2."
    override val permissionLevel: PermissionLevel = PermissionLevel.PRIVILEGED

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "success" to false,
            "error" to "NOT_ENABLED: Local shell/command execution is disabled in Stage 1 & Stage 2."
        )
    }
}
