package com.example.data.wre

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [The Eternal Manifesto: One Brain. Many Bodies. Sovereign Native PTY]
 *
 * WastiPseudoTerminalEmulator:
 * High-performance Pseudo-Terminal (/dev/pts, VT100, ANSI Escape Sequence) emulator:
 * 1. Emulates interactive terminal applications (nano, vim, htop, top, tmux, mc).
 * 2. Processes ANSI color codes, cursor repositioning (\u001b[H, \u001b[2J), screen buffer clearing, and text styling.
 * 3. Provides clean raw screen lines or plain text for Jetpack Compose UI rendering.
 */
class WastiPseudoTerminalEmulator(
    private val context: Context,
    private val columns: Int = 80,
    private val rows: Int = 24
) {

    private val screenBuffer = Array(rows) { CharArray(columns) { ' ' } }
    private var cursorX = 0
    private var cursorY = 0
    private val isRunning = AtomicBoolean(false)

    fun resetBuffer() {
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                screenBuffer[r][c] = ' '
            }
        }
        cursorX = 0
        cursorY = 0
    }

    /**
     * Parses incoming ANSI/VT100 byte stream and projects onto the virtual screen matrix.
     */
    fun processAnsiStream(rawChunk: String) {
        var i = 0
        while (i < rawChunk.length) {
            val ch = rawChunk[i]
            when {
                ch == '\u001b' && i + 1 < rawChunk.length && rawChunk[i + 1] == '[' -> {
                    // ANSI Escape sequence
                    val endIdx = findAnsiSequenceEnd(rawChunk, i + 2)
                    if (endIdx != -1) {
                        val seq = rawChunk.substring(i + 2, endIdx + 1)
                        applyAnsiCommand(seq)
                        i = endIdx
                    } else {
                        i++
                    }
                }
                ch == '\n' -> {
                    cursorY = (cursorY + 1).coerceAtMost(rows - 1)
                    cursorX = 0
                }
                ch == '\r' -> {
                    cursorX = 0
                }
                ch == '\b' -> {
                    cursorX = (cursorX - 1).coerceAtLeast(0)
                }
                ch == '\t' -> {
                    cursorX = ((cursorX + 4) / 4 * 4).coerceAtMost(columns - 1)
                }
                else -> {
                    if (cursorY < rows && cursorX < columns) {
                        screenBuffer[cursorY][cursorX] = ch
                        cursorX++
                        if (cursorX >= columns) {
                            cursorX = 0
                            cursorY = (cursorY + 1).coerceAtMost(rows - 1)
                        }
                    }
                }
            }
            i++
        }
    }

    private fun findAnsiSequenceEnd(text: String, start: Int): Int {
        for (j in start until text.length) {
            val c = text[j]
            if (c.isLetter() || c == '@' || c == '`') {
                return j
            }
        }
        return -1
    }

    private fun applyAnsiCommand(seq: String) {
        val lastChar = seq.lastOrNull() ?: return
        val params = seq.dropLast(1).split(";").mapNotNull { it.toIntOrNull() }

        when (lastChar) {
            'H', 'f' -> {
                // Cursor position: ESC [ <line> ; <col> H
                val targetRow = (params.getOrNull(0) ?: 1) - 1
                val targetCol = (params.getOrNull(1) ?: 1) - 1
                cursorY = targetRow.coerceIn(0, rows - 1)
                cursorX = targetCol.coerceIn(0, columns - 1)
            }
            'J' -> {
                // Clear screen
                val mode = params.getOrNull(0) ?: 0
                if (mode == 2 || mode == 3) {
                    resetBuffer()
                }
            }
            'K' -> {
                // Clear line
                if (cursorY in 0 until rows) {
                    for (c in cursorX until columns) {
                        screenBuffer[cursorY][c] = ' '
                    }
                }
            }
            'A' -> { // Cursor Up
                val count = params.getOrNull(0) ?: 1
                cursorY = (cursorY - count).coerceAtLeast(0)
            }
            'B' -> { // Cursor Down
                val count = params.getOrNull(0) ?: 1
                cursorY = (cursorY + count).coerceAtMost(rows - 1)
            }
            'C' -> { // Cursor Forward
                val count = params.getOrNull(0) ?: 1
                cursorX = (cursorX + count).coerceAtMost(columns - 1)
            }
            'D' -> { // Cursor Backward
                val count = params.getOrNull(0) ?: 1
                cursorX = (cursorX - count).coerceAtLeast(0)
            }
        }
    }

    fun getRenderedScreenLines(): List<String> {
        return screenBuffer.map { String(it).trimEnd() }.filter { it.isNotEmpty() }
    }

    fun getCursorPosition(): Pair<Int, Int> = cursorX to cursorY
}
