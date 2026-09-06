package com.example.data.wre

/**
 * Stage 9C: WRE Command Parser & Pipeline Evaluator
 * 
 * Supports:
 * - Single commands with quoted arguments ("hello world", 'path/to file')
 * - Command chaining (&&, ||, ;)
 * - Standard UNIX piping (|) between commands in the sandboxed workspace
 * - File redirection (>, >>)
 */
sealed class CommandNode {
    data class Simple(val executable: String, val args: List<String>, val raw: String) : CommandNode()
    data class Pipeline(val stages: List<Simple>) : CommandNode()
    data class Chained(val left: CommandNode, val operator: ChainOperator, val right: CommandNode) : CommandNode()
}

enum class ChainOperator {
    AND, // &&
    OR,  // ||
    SEQ  // ;
}

object WreCommandParser {

    /**
     * Splits command string respecting single and double quotes.
     */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaping = false

        for (ch in input.trim()) {
            if (escaping) {
                sb.append(ch)
                escaping = false
                continue
            }
            if (ch == '\\' && !inSingle) {
                escaping = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (ch.isWhitespace() && !inSingle && !inDouble) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }

    /**
     * Parses a command string into pipeline stages if pipes '|' are present.
     */
    fun parsePipeline(input: String): List<CommandNode.Simple> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false

        for (ch in input) {
            if (ch == '\'' && !inDouble) inSingle = !inSingle
            if (ch == '"' && !inSingle) inDouble = !inDouble

            if (ch == '|' && !inSingle && !inDouble) {
                if (sb.isNotBlank()) {
                    parts.add(sb.toString().trim())
                    sb.clear()
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotBlank()) {
            parts.add(sb.toString().trim())
        }

        return parts.map { part ->
            val tokens = tokenize(part)
            val exe = tokens.firstOrNull() ?: ""
            val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()
            CommandNode.Simple(executable = exe, args = args, raw = part)
        }
    }

    /**
     * Parses a command string into chained stages if operators '&&', '||', or ';' are present.
     * Respects single and double quotes so operators inside quotes are treated as literal text.
     */
    fun parseChained(input: String): List<Pair<String, ChainOperator?>> {
        val stages = mutableListOf<Pair<String, ChainOperator?>>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaping = false

        var i = 0
        val len = input.length
        while (i < len) {
            val ch = input[i]
            if (escaping) {
                sb.append(ch)
                escaping = false
                i++
                continue
            }
            if (ch == '\\' && !inSingle) {
                escaping = true
                i++
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                sb.append(ch)
                i++
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                sb.append(ch)
                i++
                continue
            }

            if (!inSingle && !inDouble) {
                if (ch == '&' && i + 1 < len && input[i + 1] == '&') {
                    val raw = sb.toString().trim()
                    if (raw.isNotEmpty()) {
                        stages.add(Pair(raw, ChainOperator.AND))
                    }
                    sb.clear()
                    i += 2
                    continue
                } else if (ch == '|' && i + 1 < len && input[i + 1] == '|') {
                    val raw = sb.toString().trim()
                    if (raw.isNotEmpty()) {
                        stages.add(Pair(raw, ChainOperator.OR))
                    }
                    sb.clear()
                    i += 2
                    continue
                } else if (ch == ';') {
                    val raw = sb.toString().trim()
                    if (raw.isNotEmpty()) {
                        stages.add(Pair(raw, ChainOperator.SEQ))
                    }
                    sb.clear()
                    i++
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty()) {
            stages.add(Pair(remaining, null))
        }
        return stages
    }

    /**
     * Parses a command string into a structured CommandNode tree (Chained, Pipeline, or Simple).
     */
    fun parseCommandTree(input: String): CommandNode {
        val chainedStages = parseChained(input)
        if (chainedStages.size <= 1) {
            return parsePipelineOrSimple(input)
        }

        var root: CommandNode? = null
        var lastOp: ChainOperator? = null

        for ((cmdStr, nextOp) in chainedStages) {
            val node = parsePipelineOrSimple(cmdStr)
            if (root == null) {
                root = node
            } else if (lastOp != null) {
                root = CommandNode.Chained(root, lastOp, node)
            }
            lastOp = nextOp
        }
        return root ?: CommandNode.Simple("", emptyList(), "")
    }

    fun parsePipelineOrSimple(input: String): CommandNode {
        val trimmed = input.trim()
        if (trimmed.contains("|")) {
            val stages = parsePipeline(trimmed)
            return if (stages.size == 1) stages[0] else CommandNode.Pipeline(stages)
        }
        val tokens = tokenize(trimmed)
        val exe = tokens.firstOrNull() ?: ""
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()
        return CommandNode.Simple(executable = exe, args = args, raw = trimmed)
    }
}
