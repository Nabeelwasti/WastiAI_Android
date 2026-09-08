package com.example.data.wre

import java.io.File

/**
 * Stage 9C: Live WRE Autocompletion Engine
 * 
 * Provides dynamic suggestions for:
 * 1. Built-in and installed binary commands (`python`, `node`, `git`, `pkg`, `apt`, `sqlite3`, `ssh`, `tmux`, `gcc`...)
 * 2. Live virtual filesystem paths (directories and files in the current working directory)
 * 3. Package management subcommands (`pkg`, `apt`, `pip`, `npm`, `git`, `wre`)
 */
data class AutocompleteSuggestion(
    val text: String,
    val displayText: String,
    val isDirectory: Boolean = false,
    val isCommand: Boolean = false
)

class WreAutocompleteEngine(
    private val workspaceManager: WreWorkspaceManager,
    private val packageManager: WrePackageManager? = null
) {
    private val builtinCommands = listOf(
        "pwd", "cd", "ls", "mkdir", "touch", "cat", "echo", "rm", "cp", "mv",
        "grep", "find", "env", "which", "date", "whoami", "uname", "status",
        "ps", "jobs", "kill", "help", "clear", "wre",
        "python", "python3", "pip", "pip3",
        "node", "nodejs", "npm", "npx",
        "git", "pkg", "apt", "apt-get",
        "sqlite3", "sql",
        "gcc", "clang", "g++", "clang++", "make",
        "ssh", "ssh-keygen", "tmux",
        "neofetch", "htop", "top", "tree", "curl", "wget", "tar", "zip", "unzip", "base64", "sha256sum", "md5sum",
        "sysinfo", "hardware", "keystore", "tunnel", "search", "speak", "cognitive", "sensory", "face"
    )

    fun getSuggestions(currentInput: String, workingDirVirtual: String = "home/wasti"): List<AutocompleteSuggestion> {
        val trimmed = currentInput.trimStart()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = WreCommandParser.tokenize(currentInput)
        val isTrailingSpace = currentInput.endsWith(" ")

        // Case 1: First token - Command Completion
        if (tokens.size == 1 && !isTrailingSpace) {
            val prefix = tokens[0].lowercase()
            val commandList = mutableListOf<String>().apply {
                addAll(builtinCommands)
                packageManager?.listPackages()?.map { it.name }?.let { addAll(it) }
            }
            return commandList
                .filter { it.startsWith(prefix) }
                .distinct()
                .sorted()
                .map { cmd ->
                    AutocompleteSuggestion(
                        text = cmd,
                        displayText = cmd,
                        isCommand = true
                    )
                }
        }

        // Case 2: Git subcommands
        if (tokens.isNotEmpty() && tokens[0] == "git") {
            val gitSubcmds = listOf("status", "add", "commit", "log", "diff", "branch", "checkout", "clone", "remote", "init", "config", "push", "pull")
            if (tokens.size == 2 && !isTrailingSpace) {
                val subPrefix = tokens[1].lowercase()
                return gitSubcmds.filter { it.startsWith(subPrefix) }.map { AutocompleteSuggestion(it, "git $it") }
            }
            if (tokens.size == 1 && isTrailingSpace) {
                return gitSubcmds.map { AutocompleteSuggestion(it, "git $it") }
            }
        }

        // Case 3: Pkg / Apt subcommands
        if (tokens.isNotEmpty() && (tokens[0] == "pkg" || tokens[0] == "apt" || tokens[0] == "apt-get")) {
            val pkgSubcmds = listOf("install", "uninstall", "update", "upgrade", "search", "list-installed", "list-all", "show")
            if (tokens.size == 2 && !isTrailingSpace) {
                val subPrefix = tokens[1].lowercase()
                return pkgSubcmds.filter { it.startsWith(subPrefix) }.map { AutocompleteSuggestion(it, "${tokens[0]} $it") }
            }
            if (tokens.size == 1 && isTrailingSpace) {
                return pkgSubcmds.map { AutocompleteSuggestion(it, "${tokens[0]} $it") }
            }
        }

        // Case 4: Pip subcommands
        if (tokens.isNotEmpty() && (tokens[0] == "pip" || tokens[0] == "pip3")) {
            val pipSubcmds = listOf("install", "uninstall", "list", "show", "freeze", "check", "search")
            if (tokens.size == 2 && !isTrailingSpace) {
                val subPrefix = tokens[1].lowercase()
                return pipSubcmds.filter { it.startsWith(subPrefix) }.map { AutocompleteSuggestion(it, "${tokens[0]} $it") }
            }
            if (tokens.size == 1 && isTrailingSpace) {
                return pipSubcmds.map { AutocompleteSuggestion(it, "${tokens[0]} $it") }
            }
        }

        // Case 5: Npm subcommands
        if (tokens.isNotEmpty() && tokens[0] == "npm") {
            val npmSubcmds = listOf("install", "init", "run", "test", "list", "start")
            if (tokens.size == 2 && !isTrailingSpace) {
                val subPrefix = tokens[1].lowercase()
                return npmSubcmds.filter { it.startsWith(subPrefix) }.map { AutocompleteSuggestion(it, "npm $it") }
            }
            if (tokens.size == 1 && isTrailingSpace) {
                return npmSubcmds.map { AutocompleteSuggestion(it, "npm $it") }
            }
        }

        // Case 6: WRE package subcommands
        if (tokens.isNotEmpty() && tokens[0] == "wre") {
            if (tokens.size == 2 && !isTrailingSpace) {
                val subPrefix = tokens[1].lowercase()
                return listOf("pkg", "status", "env", "help")
                    .filter { it.startsWith(subPrefix) }
                    .map { AutocompleteSuggestion(it, "wre $it") }
            }
            if (tokens.size == 2 && isTrailingSpace && tokens[1] == "pkg") {
                return listOf("list", "install", "remove", "export", "info")
                    .map { AutocompleteSuggestion(it, "wre pkg $it") }
            }
            if (tokens.size == 3 && !isTrailingSpace && tokens[1] == "pkg") {
                val actionPrefix = tokens[2].lowercase()
                return listOf("list", "install", "remove", "export", "info")
                    .filter { it.startsWith(actionPrefix) }
                    .map { AutocompleteSuggestion(it, "wre pkg $it") }
            }
            if (tokens.size >= 3 && tokens[1] == "pkg" && tokens[2] in listOf("remove", "export", "info")) {
                val filterPrefix = if (tokens.size == 4 && !isTrailingSpace) tokens[3].lowercase() else ""
                return packageManager?.listPackages()
                    ?.map { it.name }
                    ?.filter { it.startsWith(filterPrefix) }
                    ?.map { AutocompleteSuggestion(it, it) }
                    ?: emptyList()
            }
        }

        // Case 7: Filesystem path completion
        val pathArg = if (isTrailingSpace) "" else tokens.last()
        return completePath(pathArg, workingDirVirtual)
    }

    private fun completePath(partialPath: String, workingDirVirtual: String): List<AutocompleteSuggestion> {
        val lastSlashIndex = partialPath.lastIndexOf('/')
        val dirPart = if (lastSlashIndex != -1) partialPath.substring(0, lastSlashIndex + 1) else ""
        val filePrefix = if (lastSlashIndex != -1) partialPath.substring(lastSlashIndex + 1) else partialPath

        val targetVirtual = when {
            dirPart.startsWith("/") -> dirPart.removePrefix("/")
            dirPart.isNotEmpty() -> "$workingDirVirtual/$dirPart"
            else -> workingDirVirtual
        }

        val targetDir = workspaceManager.resolve(targetVirtual).getOrNull()
        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
            return emptyList()
        }

        val children = targetDir.listFiles() ?: return emptyList()
        return children
            .filter { it.name.startsWith(filePrefix, ignoreCase = true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            .map { file ->
                val suggestionText = if (dirPart.isNotEmpty()) "$dirPart${file.name}" else file.name
                val fullWithSlash = if (file.isDirectory) "$suggestionText/" else suggestionText
                AutocompleteSuggestion(
                    text = fullWithSlash,
                    displayText = if (file.isDirectory) "${file.name}/" else file.name,
                    isDirectory = file.isDirectory
                )
            }
    }
}
