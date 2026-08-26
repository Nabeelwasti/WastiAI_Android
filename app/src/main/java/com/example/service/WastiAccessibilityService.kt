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
import com.example.data.agent.runtime.StructuredUiObservation
import com.example.data.agent.runtime.TargetMatchRank
import com.example.data.agent.runtime.TargetSelectionResult
import com.example.data.agent.runtime.TargetSelectionStatus
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.persistence.DraftPersistenceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    // Observation & Correlation Pipeline State
    var latestUiObservation: StructuredUiObservation? = null
        private set

    private var lastEventTimestamp: Long = 0L
    private var activeCorrelationId: String? = null

    fun setCorrelationId(id: String?) {
        activeCorrelationId = id
    }

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
        if (event == null) return
        val now = System.currentTimeMillis()

        // Throttling/debouncing: ignore non-window-state events within 50ms window
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && (now - lastEventTimestamp < 50L)) {
            return
        }
        lastEventTimestamp = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val pkgName = event.packageName?.toString()
                val clsName = event.className?.toString()
                val textList = event.text.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
                val eventText = if (textList.isNotEmpty()) textList.joinToString(" ") else null
                val contentDesc = event.contentDescription?.toString()
                val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) event.source else null

                val obs = StructuredUiObservation(
                    packageName = pkgName,
                    className = clsName,
                    text = eventText,
                    contentDescription = contentDesc,
                    resourceId = record?.viewIdResourceName,
                    clickable = record?.isClickable ?: false,
                    enabled = record?.isEnabled ?: true,
                    editable = record?.isEditable ?: false,
                    scrollable = record?.isScrollable ?: false,
                    eventType = event.eventType,
                    timestamp = now,
                    correlationId = activeCorrelationId
                )
                latestUiObservation = obs
            }
        }
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

    private fun traverseAndScrapeNode(
        node: AccessibilityNodeInfo?,
        jsonArray: JSONArray,
        depth: Int = 0,
        nodeCounter: IntArray = intArrayOf(0),
        visitedHashes: MutableSet<Int> = mutableSetOf()
    ) {
        if (node == null || depth > 25 || nodeCounter[0] >= 500) return
        val nodeHash = System.identityHashCode(node)
        if (!visitedHashes.add(nodeHash)) return
        nodeCounter[0]++

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
            traverseAndScrapeNode(child, jsonArray, depth + 1, nodeCounter, visitedHashes)
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
        val nodeCount = traverseNode(rootNode, textCollector, depth = 0, nodeCounter = intArrayOf(0), visitedHashes = mutableSetOf())

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

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int,
        nodeCounter: IntArray,
        visitedHashes: MutableSet<Int>
    ): Int {
        if (node == null || depth > 25 || nodeCounter[0] >= 500) return 0
        val nodeHash = System.identityHashCode(node)
        if (!visitedHashes.add(nodeHash)) return 0
        nodeCounter[0]++

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
            count += traverseNode(child, sb, depth + 1, nodeCounter, visitedHashes)
        }

        return count
    }

    /**
     * Alias for clickElement to support tapElement calls.
     */
    fun tapElement(targetTextOrId: String): Boolean = clickElement(targetTextOrId)

    /**
     * Phase 8 — Ranked Target Selection & Ambiguity Protection
     */
    data class NodeWithRank(
        val node: AccessibilityNodeInfo,
        val rank: TargetMatchRank
    )

    fun findTargetNodeRanked(
        target: String,
        root: AccessibilityNodeInfo? = rootInActiveWindow
    ): TargetSelectionResult {
        val rootNode = root ?: return TargetSelectionResult(
            status = TargetSelectionStatus.NOT_FOUND,
            details = "rootInActiveWindow is null"
        )
        val cleanTarget = target.trim()
        if (cleanTarget.isBlank()) return TargetSelectionResult(
            status = TargetSelectionStatus.NOT_FOUND,
            details = "Target is blank"
        )

        val targetLower = cleanTarget.lowercase()
        val matches = mutableListOf<NodeWithRank>()
        val visitedHashes = mutableSetOf<Int>()

        fun collectRanked(node: AccessibilityNodeInfo?, depth: Int, nodeCount: IntArray) {
            if (node == null || depth > 25 || nodeCount[0] >= 500) return
            val hash = System.identityHashCode(node)
            if (!visitedHashes.add(hash)) return
            nodeCount[0]++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.trim() ?: ""

            val textLower = text.lowercase()
            val descLower = desc.lowercase()
            val idLower = id.lowercase()

            val rank = when {
                id.equals(cleanTarget, ignoreCase = true) || id.endsWith("/$cleanTarget", ignoreCase = true) ->
                    TargetMatchRank.EXACT_RESOURCE_ID
                text.equals(cleanTarget, ignoreCase = true) ->
                    TargetMatchRank.EXACT_NORMALIZED_TEXT
                desc.equals(cleanTarget, ignoreCase = true) ->
                    TargetMatchRank.EXACT_CONTENT_DESCRIPTION
                textLower.replace("\\s+".toRegex(), "") == targetLower.replace("\\s+".toRegex(), "") ->
                    TargetMatchRank.NORMALIZED_EXACT_MATCH
                textLower.contains(targetLower) || descLower.contains(targetLower) || idLower.contains(targetLower) ->
                    TargetMatchRank.PARTIAL_MATCH
                else -> TargetMatchRank.NO_MATCH
            }

            if (rank != TargetMatchRank.NO_MATCH) {
                matches.add(NodeWithRank(node, rank))
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                collectRanked(child, depth + 1, nodeCount)
            }
        }

        collectRanked(rootNode, 0, intArrayOf(0))

        if (matches.isEmpty()) {
            return TargetSelectionResult(
                status = TargetSelectionStatus.NOT_FOUND,
                details = "No nodes matched target '$cleanTarget'"
            )
        }

        val bestRank = matches.minByOrNull { it.rank.ordinal }!!.rank
        val bestMatches = matches.filter { it.rank == bestRank }

        if (bestMatches.size > 1 && bestRank != TargetMatchRank.EXACT_RESOURCE_ID) {
            return TargetSelectionResult(
                status = TargetSelectionStatus.AMBIGUOUS,
                matchedRank = bestRank,
                candidateCount = bestMatches.size,
                details = "Ambiguous target selection: ${bestMatches.size} candidates matched '$cleanTarget' with rank $bestRank"
            )
        }

        return TargetSelectionResult(
            status = TargetSelectionStatus.MATCHED,
            matchedRank = bestRank,
            candidateCount = 1,
            details = "Matched node with rank $bestRank"
        )
    }

    /**
     * Performs tap on elements matching target text, view ID, or direct coordinates.
     */
    fun clickElement(targetTextOrId: String): Boolean {
        val cleanTarget = targetTextOrId.trim()
        if (cleanTarget.isBlank()) return false

        // Check if direct coordinate tap
        val coordPattern = Regex("""^(?:x\s*=\s*)?(\d+(?:\.\d+)?)\s*,\s*(?:y\s*=\s*)?(\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
        val match = coordPattern.find(cleanTarget)
        if (match != null) {
            val x = match.groupValues[1].toFloatOrNull()
            val y = match.groupValues[2].toFloatOrNull()
            if (x != null && y != null) {
                return performTapAt(x, y)
            }
        }

        val rootNode = rootInActiveWindow ?: return false

        // Phase 8: Ranked Target Check & Ambiguity Protection
        val rankingResult = findTargetNodeRanked(cleanTarget, rootNode)
        if (rankingResult.status == TargetSelectionStatus.AMBIGUOUS) {
            Log.w(TAG, "Click element rejected due to target ambiguity: ${rankingResult.details}")
            return false
        }

        val targetLower = cleanTarget.lowercase()

        if (searchAndClick(rootNode, targetLower)) {
            return true
        }

        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        val textMatches = rootNode.findAccessibilityNodeInfosByText(cleanTarget)
        if (!textMatches.isNullOrEmpty()) {
            matchingNodes.addAll(textMatches)
        }
        collectMatchingNodes(rootNode, targetLower, matchingNodes)

        for (node in matchingNodes.distinct()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val tapped = performTapAt(rect.exactCenterX(), rect.exactCenterY())
                if (tapped) return true
            }
        }

        return false
    }

    private fun searchAndClick(
        node: AccessibilityNodeInfo?,
        targetLower: String,
        depth: Int = 0,
        nodeCount: IntArray = intArrayOf(0),
        visitedHashes: MutableSet<Int> = mutableSetOf()
    ): Boolean {
        if (node == null || depth > 25 || nodeCount[0] >= 500) return false
        val hash = System.identityHashCode(node)
        if (!visitedHashes.add(hash)) return false
        nodeCount[0]++

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
            if (searchAndClick(child, targetLower, depth + 1, nodeCount, visitedHashes)) {
                return true
            }
        }

        return false
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        targetLower: String,
        outList: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
        nodeCount: IntArray = intArrayOf(0),
        visitedHashes: MutableSet<Int> = mutableSetOf()
    ) {
        if (node == null || depth > 25 || nodeCount[0] >= 500) return
        val hash = System.identityHashCode(node)
        if (!visitedHashes.add(hash)) return
        nodeCount[0]++

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        if (text.contains(targetLower) || desc.contains(targetLower) || id.contains(targetLower)) {
            outList.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectMatchingNodes(child, targetLower, outList, depth + 1, nodeCount, visitedHashes)
        }
    }

    /**
     * Phase 4 — Async Tap Execution with Callback Correlation & Timeout Protection
     */
    suspend fun performTapAtAsync(
        x: Float,
        y: Float,
        timeoutMs: Long = 3000L
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Async tap gesture completed at ($x, $y)")
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Async tap gesture cancelled at ($x, $y)")
                deferred.complete(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) return false

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false
    }

    /**
     * Phase 4 — Async Swipe Execution with Callback Correlation & Timeout Protection
     */
    suspend fun performSwipeAsync(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L,
        timeoutMs: Long = 3000L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.i(TAG, "Async swipe gesture completed from ($startX, $startY) to ($endX, $endY)")
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Async swipe gesture cancelled from ($startX, $startY) to ($endX, $endY)")
                deferred.complete(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) return false

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false
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

    /**
     * Executes global navigation actions: Back, Home, Recents, Notifications, QuickSettings.
     */
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /**
     * Types text into a specific target element or currently focused editable node.
     */
    fun typeText(text: String, targetElement: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        
        if (!targetElement.isNullOrBlank()) {
            val targetLower = targetElement.lowercase().trim()
            val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
            collectMatchingNodes(rootNode, targetLower, matchingNodes)
            for (node in matchingNodes) {
                if (node.isEditable) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        return true
                    }
                }
            }
        }

        // Search for any focused or editable node
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        // Search active tree for editable node
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(rootNode, editableNodes)
        val firstEditable = editableNodes.firstOrNull()
        if (firstEditable != null) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return firstEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        return false
    }

    private fun collectEditableNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
        visited: MutableSet<Int> = mutableSetOf()
    ) {
        if (node == null || depth > 25 || outList.size >= 10) return
        val hash = System.identityHashCode(node)
        if (!visited.add(hash)) return

        if (node.isEditable) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditableNodes(node.getChild(i), outList, depth + 1, visited)
        }
    }

    /**
     * Executes scroll forward/down or backward/up on scrollable containers.
     */
    fun performScroll(direction: String = "DOWN"): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val action = if (direction.uppercase() == "UP" || direction.uppercase() == "BACKWARD") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        val scrollableNodes = mutableListOf<AccessibilityNodeInfo>()
        collectScrollableNodes(rootNode, scrollableNodes)
        for (scrollNode in scrollableNodes) {
            if (scrollNode.performAction(action)) {
                return true
            }
        }
        return false
    }

    private fun collectScrollableNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
        visited: MutableSet<Int> = mutableSetOf()
    ) {
        if (node == null || depth > 25 || outList.size >= 10) return
        val hash = System.identityHashCode(node)
        if (!visited.add(hash)) return

        if (node.isScrollable) {
            outList.add(node)
        }
        for (i in 0 until node.childCount) {
            collectScrollableNodes(node.getChild(i), outList, depth + 1, visited)
        }
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

