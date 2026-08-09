package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WastiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WastiAccessibilityService"

        var instance: WastiAccessibilityService? = null
            private set

        val isServiceActive: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Wasti Accessibility Service connected successfully.")
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
     * Alias for clickElement to support tapElement calls.
     */
    fun tapElement(targetTextOrId: String): Boolean = clickElement(targetTextOrId)

    /**
     * Performs tap on elements matching target text, view ID, or direct coordinates.
     * Strategy:
     * 1. Check if target specifies raw numeric coordinates "X,Y".
     * 2. Try standard ACTION_CLICK on clickable nodes.
     * 3. Fallback to findAccessibilityNodeInfosByText / node bounds matching -> Coordinate-based Gesture Dispatch.
     */
    fun clickElement(targetTextOrId: String): Boolean {
        val cleanTarget = targetTextOrId.trim()
        if (cleanTarget.isBlank()) return false

        // 1. Direct Coordinate Tap Check (e.g. "500,1000" or "x=300, y=400")
        val coordPattern = Regex("""^(?:x\s*=\s*)?(\d+(?:\.\d+)?)\s*,\s*(?:y\s*=\s*)?(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
        val match = coordPattern.find(cleanTarget)
        if (match != null) {
            val x = match.groupValues[1].toFloatOrNull()
            val y = match.groupValues[2].toFloatOrNull()
            if (x != null && y != null) {
                Log.i(TAG, "Executing direct coordinate tap at ($x, $y)")
                return performTapAt(x, y)
            }
        }

        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "Cannot click element '$cleanTarget': rootInActiveWindow is null.")
            return false
        }

        val targetLower = cleanTarget.lowercase()

        // 2. First attempt: Standard AccessibilityNodeInfo.ACTION_CLICK
        if (searchAndClick(rootNode, targetLower)) {
            Log.i(TAG, "Standard ACTION_CLICK succeeded for target '$cleanTarget'")
            return true
        }

        Log.i(TAG, "Standard ACTION_CLICK failed/ignored for '$cleanTarget'. Initiating Coordinate-Based Gesture Dispatch fallback...")

        // 3. Fallback: Find nodes by text / content description / view ID -> extract screen bounds -> dispatch raw gesture
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()

        // Use native Android findAccessibilityNodeInfosByText
        val textMatches = rootNode.findAccessibilityNodeInfosByText(cleanTarget)
        if (!textMatches.isNullOrEmpty()) {
            matchingNodes.addAll(textMatches)
        }

        // Also do recursive search to cover content descriptions, view IDs, or partial case-insensitive matches
        collectMatchingNodes(rootNode, targetLower, matchingNodes)

        // Iterate through matching nodes and dispatch gesture to center coordinates of bounding box
        for (node in matchingNodes.distinct()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (rect.width() > 0 && rect.height() > 0) {
                val centerX = rect.exactCenterX()
                val centerY = rect.exactCenterY()

                Log.i(TAG, "Target node found! Bounding box: $rect. Dispatching raw tap gesture at center ($centerX, $centerY)")
                val tapped = performTapAt(centerX, centerY)
                if (tapped) {
                    return true
                }
            }
        }

        Log.w(TAG, "Coordinate-Based Gesture Dispatch failed: No valid screen bounds found for '$cleanTarget'")
        return false
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

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        targetLower: String,
        outList: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        if (text.contains(targetLower) || desc.contains(targetLower) || id.contains(targetLower)) {
            outList.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectMatchingNodes(child, targetLower, outList)
        }
    }

    /**
     * Dispatches tap gesture at specific (x, y) screen coordinates using GestureDescription.
     * Implements GestureResultCallback to log exact X/Y coordinates and success/failure status
     * to the SystemLogEntity database for debugging missed taps.
     */
    fun performTapAt(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Gesture completed successfully at coordinates (X: $x, Y: $y)")
                logGestureResultToDb(x = x, y = y, success = true, reason = "Gesture completed via Android Accessibility Dispatcher")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Gesture cancelled/failed at coordinates (X: $x, Y: $y)")
                logGestureResultToDb(x = x, y = y, success = false, reason = "Gesture cancelled by Android System window framework")
            }
        }

        return dispatchGesture(gesture, callback, null)
    }

    private fun logGestureResultToDb(x: Float, y: Float, success: Boolean, reason: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val statusTag = if (success) "SUCCESS" else "FAILED"
                val level = if (success) "INFO" else "WARN"
                val messageText = "Coordinate Tap Gesture [$statusTag] at (X: $x, Y: $y)"

                val db = WastiDatabase.getDatabase(applicationContext)
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = level,
                        source = "WastiAccessibilityService",
                        message = messageText,
                        details = reason,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert gesture log into SystemLogEntity database", e)
            }
        }
    }
}

