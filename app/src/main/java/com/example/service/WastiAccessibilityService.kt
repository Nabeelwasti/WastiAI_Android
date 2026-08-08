package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WastiAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WastiAccessibilityService? = null
            private set

        val isServiceActive: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing for active UI monitoring
    }

    override fun onInterrupt() {
        // Interrupted
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Captures active window's AccessibilityNodeInfo and recursively reads text on screen.
     */
    fun dumpScreenContent(): String {
        val rootNode = rootInActiveWindow ?: return "[Wasti Accessibility Service Active] • Active window node unavailable or screen locked."

        val textCollector = StringBuilder()
        val nodeCount = traverseNode(rootNode, textCollector, depth = 0)

        return if (textCollector.isNotBlank()) {
            """
                [Wasti Live Screen Reader Active - Accessibility API]
                • Package: ${rootNode.packageName ?: "Unknown"}
                • Total Screen Nodes Scanned: $nodeCount
                • Extracted Screen Text:
                $textCollector
            """.trimIndent()
        } else {
            "[Wasti Live Screen Reader Active] • Window scanned ($nodeCount nodes) - No text labels detected."
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int): Int {
        if (node == null) return 0
        var count = 1

        val text = node.text?.toString()?.trim()
        val contentDescription = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName

        if (!text.isNullOrBlank() || !contentDescription.isNullOrBlank()) {
            val indent = "  ".repeat(depth.coerceAtMost(5))
            val label = text ?: contentDescription
            val extraInfo = if (!viewId.isNullOrBlank()) " ($viewId)" else ""
            sb.append("$indent• $label$extraInfo\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            count += traverseNode(child, sb, depth + 1)
        }

        return count
    }

    /**
     * Performs AccessibilityNodeInfo.ACTION_CLICK on elements matching text or view ID.
     */
    fun clickElement(targetTextOrId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return searchAndClick(rootNode, targetTextOrId.lowercase().trim())
    }

    private fun searchAndClick(node: AccessibilityNodeInfo?, targetLower: String): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        val isMatch = text.contains(targetLower) || desc.contains(targetLower) || id.contains(targetLower)

        if (isMatch && node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        if (isMatch) {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (searchAndClick(child, targetLower)) {
                return true
            }
        }

        return false
    }

    /**
     * Dispatches tap gesture at specific (x, y) screen coordinates.
     */
    fun performTapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
