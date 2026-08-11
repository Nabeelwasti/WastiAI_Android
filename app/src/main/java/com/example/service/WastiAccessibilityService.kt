package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.persistence.DraftPersistenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class WastiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WastiAccessibilityService"
        const val ACTION_EXECUTE_GESTURE = "com.wasti.os.ACTION_EXECUTE_GESTURE"
        const val ACTION_SCREEN_SCRAPED = "com.wasti.os.ACTION_SCREEN_SCRAPED"

        var instance: WastiAccessibilityService? = null
            private set

        val isServiceActive: Boolean
            get() = instance != null
    }

    private var commandReceiver: WastiCommandReceiver? = null

    /**
     * Task 38A: Dynamic BroadcastReceiver IPC Bridge for incoming gesture and scrape commands.
     */
    inner class WastiCommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == ACTION_EXECUTE_GESTURE) {
                val actionType = intent.getStringExtra("actionType") ?: "TAP"
                Log.i(TAG, "WastiCommandReceiver received IPC gesture command: actionType=$actionType")

                when (actionType.uppercase()) {
                    "TAP", "CLICK_COORD" -> {
                        val x = intent.getFloatExtra("x", -1f)
                        val y = intent.getFloatExtra("y", -1f)
                        if (x >= 0f && y >= 0f) {
                            performTapAt(x, y)
                        } else {
                            val target = intent.getStringExtra("targetText") ?: intent.getStringExtra("targetElement") ?: ""
                            if (target.isNotBlank()) {
                                clickElement(target)
                            }
                        }
                    }
                    "CLICK_TEXT", "TAP_TEXT" -> {
                        val target = intent.getStringExtra("targetText") ?: intent.getStringExtra("targetElement") ?: ""
                        if (target.isNotBlank()) {
                            clickElement(target)
                        }
                    }
                    "SWIPE" -> {
                        val startX = intent.getFloatExtra("startX", 0f)
                        val startY = intent.getFloatExtra("startY", 0f)
                        val endX = intent.getFloatExtra("endX", 0f)
                        val endY = intent.getFloatExtra("endY", 0f)
                        val duration = intent.getLongExtra("duration", 300L)
                        performSwipe(startX, startY, endX, endY, duration)
                    }
                    "SCRAPE", "SCREEN_SCRAPE" -> {
                        scrapeActiveScreen()
                    }
                    else -> {
                        Log.w(TAG, "Unknown actionType '$actionType' received in WastiCommandReceiver")
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerCommandReceiver()
        Log.i(TAG, "Wasti Accessibility Service connected successfully.")
    }

    private fun registerCommandReceiver() {
        if (commandReceiver == null) {
            commandReceiver = WastiCommandReceiver()
            val filter = IntentFilter(ACTION_EXECUTE_GESTURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(commandReceiver, filter)
            }
            Log.i(TAG, "WastiCommandReceiver IPC bridge registered for $ACTION_EXECUTE_GESTURE")
        }
    }

    private fun unregisterCommandReceiver() {
        commandReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.i(TAG, "WastiCommandReceiver IPC bridge unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering WastiCommandReceiver", e)
            }
        }
        commandReceiver = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterCommandReceiver()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCommandReceiver()
        if (instance == this) {
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing for active UI monitoring
    }

    override fun onInterrupt() {
        // Interrupted
    }

    /**
     * Task 38B: The UI Tree Scraper (The "Eyes")
     * Recursively scans rootInActiveWindow accessibility nodes and formats all visible text,
     * content descriptions, view IDs, and bounding coordinates into a structured JSON array.
     * Persists output to DraftPersistenceManager and broadcasts ACTION_SCREEN_SCRAPED to WastiCore.
     */
    fun scrapeActiveScreen(): String {
        val rootNode = rootInActiveWindow ?: run {
            val emptyResult = "[]"
            DraftPersistenceManager.saveScrapedScreenData(this, emptyResult)
            return emptyResult
        }

        val nodesArray = JSONArray()
        traverseAndScrapeNode(rootNode, nodesArray)
        val jsonString = nodesArray.toString(2)

        // Save output to DraftPersistenceManager
        DraftPersistenceManager.saveScrapedScreenData(this, jsonString)

        // Broadcast screen scraped event back to WastiCore / system listeners
        val intent = Intent(ACTION_SCREEN_SCRAPED).apply {
            putExtra("screen_json", jsonString)
            putExtra("package_name", rootNode.packageName?.toString() ?: "")
            setPackage(packageName)
        }
        sendBroadcast(intent)

        return jsonString
    }

    private fun traverseAndScrapeNode(node: AccessibilityNodeInfo?, jsonArray: JSONArray) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val contentDescription = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName
        val isClickable = node.isClickable
        val isEnabled = node.isEnabled
        val isVisibleToUser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) node.isVisibleToUser else true

        if (isVisibleToUser && (!text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !viewId.isNullOrBlank())) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val nodeObj = JSONObject().apply {
                if (!text.isNullOrBlank()) put("text", text)
                if (!contentDescription.isNullOrBlank()) put("contentDescription", contentDescription)
                if (!viewId.isNullOrBlank()) put("viewId", viewId)
                put("className", node.className?.toString() ?: "")
                put("isClickable", isClickable)
                put("isEnabled", isEnabled)
                put("bounds", JSONObject().apply {
                    put("left", rect.left)
                    put("top", rect.top)
                    put("right", rect.right)
                    put("bottom", rect.bottom)
                    put("centerX", rect.centerX())
                    put("centerY", rect.centerY())
                })
            }
            jsonArray.put(nodeObj)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseAndScrapeNode(child, jsonArray)
        }
    }

    /**
     * Captures active window's AccessibilityNodeInfo and recursively reads text on screen.
     */
    fun dumpScreenContent(): String {
        val jsonScrape = scrapeActiveScreen()
        if (jsonScrape.isNotBlank() && jsonScrape != "[]") {
            return jsonScrape
        }

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
     * Dispatches swipe gesture from (startX, startY) to (endX, endY) over durationMs.
     */
    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Swipe gesture completed from ($startX, $startY) to ($endX, $endY)")
                logGestureResultToDb(x = endX, y = endY, success = true, reason = "Swipe completed via Accessibility API")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Swipe gesture cancelled from ($startX, $startY) to ($endX, $endY)")
                logGestureResultToDb(x = endX, y = endY, success = false, reason = "Swipe gesture cancelled by system")
            }
        }

        return dispatchGesture(gesture, callback, null)
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

