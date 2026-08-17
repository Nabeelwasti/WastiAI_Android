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
}
