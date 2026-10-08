package dev.krinry.jarvis.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.view.KeyEvent
import android.util.Log
import dev.krinry.jarvis.service.AutoAgentService
import org.json.JSONObject

/**
 * ActionExecutor — Parses LLM JSON and executes actions.
 *
 * Key improvements:
 * - Gesture tap fallback when node click fails
 * - Text-based node lookup when node_id doesn't match (screen changed)
 * - Bangla status messages
 * - tap_xy action for direct coordinate taps
 * - swipe action for custom swipe gestures
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    data class AgentAction(
        val action: String,
        val nodeId: Int?,
        val text: String?,
        val appName: String?,
        val contactName: String?,
        val url: String?,
        val speech: String?,
        val status: String,
        val x: Int?,         // For tap_xy
        val y: Int?,         // For tap_xy
        val reason: String?  // LLM's reasoning
    )

    fun parseResponse(json: String): AgentAction? {
        return try {
            val cleanJson = extractJsonObject(json)
            if (cleanJson == null) {
                Log.e(TAG, "No JSON found in: ${json.take(200)}")
                return null
            }

            val obj = JSONObject(cleanJson)
            AgentAction(
                action = obj.optString("action", "wait"),
                nodeId = if (obj.has("node_id") && !obj.isNull("node_id")) obj.optInt("node_id", -1) else null,
                text = obj.optString("text", "").takeIf { it.isNotEmpty() },
                appName = obj.optString("app_name", "").takeIf { it.isNotEmpty() },
                contactName = obj.optString("contact_name", "").takeIf { it.isNotEmpty() },
                url = obj.optString("url", "").takeIf { it.isNotEmpty() },
                speech = obj.optString("speech", "").takeIf { it.isNotEmpty() },
                status = obj.optString("status", "in_progress"),
                x = if (obj.has("x")) obj.optInt("x") else null,
                y = if (obj.has("y")) obj.optInt("y") else null,
                reason = obj.optString("reason", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed: ${json.take(200)}", e)
            null
        }
    }

    /**
     * Robust JSON extraction — handles:
     * - Clean JSON: {"action":"click"...}
     * - Markdown wrapped: ```json\n{...}\n```
     * - Text before/after JSON: "Sure! Here's the action: {...} Let me know"
     * - Multiple JSON objects (takes first)
     */
    private fun extractJsonObject(raw: String): String? {
        // Strip markdown code fences
        val stripped = raw
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        // Find first balanced { ... }
        val start = stripped.indexOf('{')
        if (start < 0) return null

        var depth = 0
        for (i in start until stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(start, i + 1)
                }
            }
        }
        // Unbalanced — try best effort
        val end = stripped.lastIndexOf('}')
        return if (end > start) stripped.substring(start, end + 1) else null
    }

    /**
     * Execute an action. Returns Bangla status string.
     */
    fun execute(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>): String {
        val service = AutoAgentService.instance
            ?: return "❌ Accessibility service nahi chal rahi"

        return when (action.action) {
            "click" -> executeClick(action, nodes, service)
            "type" -> executeType(action, nodes, service)
            "scroll_down" -> executeScroll(true, action, nodes, service)
            "scroll_up" -> executeScroll(false, action, nodes, service)
            "back" -> { service.pressBack(); "✅ Back dabaya" }
            "home" -> { service.pressHome(); "✅ Home dabaya" }
            "recent" -> { service.openRecents(); "✅ Recent apps khule" }
            "open_app" -> {
                val appName = action.appName ?: action.text ?: return "❌ App ka naam nahi diya"
                openAppByName(service.applicationContext, appName)
            }
            "open_url" -> executeOpenUrl(action, service)
            "tap_xy" -> executeTapXY(action, service)
            "swipe" -> executeSwipe(action, service)
            "long_press" -> executeLongPress(action, service)
            "screenshot" -> executeScreenshot(service)
            "copy" -> executeCopy(service)
            "paste" -> executePaste(action, nodes, service)
            "select_all" -> executeSelectAll(action, nodes, service)
            "open_notifications" -> { service.openNotifications(); "✅ Notifications khol diya" }

            // Phase 1A: Contacts / Phone / SMS
            "find_contact" -> findContact(action, service)
            "call_contact" -> callContact(action, service)
            "send_sms" -> sendSms(action, service)

            // Phase 2 — Calendar / Alarm / Timer / Maps / Camera
            "open_calendar" -> openCalendar(service)
            "set_alarm" -> setAlarm(action, service)
            "set_timer" -> setTimer(action, service)
            "open_maps" -> openMaps(action, service)
            "open_camera" -> openCamera(service)

            // Phase 2 — Media
            "media_play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, service)
            "media_next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, service)
            "media_previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, service)

            // Phase 2 — Share / Email / Downloads
            "share_text" -> shareText(action, service)
            "open_email" -> openEmail(action, service)
            "open_downloads" -> openDownloads(service)

            "wait" -> "⏳ Screen load ho raha hai..."
            "done" -> "✅ Kaj hoyeche!"
            else -> "❓ Unknown action: ${action.action}"
        }
    }

    // =========================================================================
    // Click — 3 strategies: node click → gesture tap at bounds → text fallback
    // =========================================================================
    private fun executeClick(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Click ke liye node_id chahiye"

        // Strategy 1: Find node by ID
        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        // Strategy 2: If not found by ID, try text match
        if (uiNode == null && action.text != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.text)
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, text se mila: '${action.text}' → id=${uiNode.id}")
            }
        }

        // Strategy 3: If still not found, try reason text for clues
        if (uiNode == null && action.reason != null) {
            uiNode = UiTreeExtractor.findNodeByText(nodes, action.reason)
        }

        if (uiNode == null) {
            Log.w(TAG, "Node $nodeId nahi mila, koi text match bhi nahi")
            return "❌ Element nahi mila (node $nodeId) — screen badal gaya"
        }

        val label = uiNode.text.ifEmpty { uiNode.contentDescription }.ifEmpty { "element" }

        // Try accessibility click first, then gesture tap at bounds
        val success = service.clickNode(uiNode.nodeInfo)
        if (success) {
            return "✅ '$label' pe click kiya"
        }

        // Fallback: gesture tap at bounds center
        val bounds = uiNode.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            service.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            return "✅ '$label' pe tap kiya (gesture)"
        }

        return "❌ '$label' pe click nahi ho paya"
    }

    // =========================================================================
    // Type — Click field first, then set text
    // =========================================================================
    private fun executeType(
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId ?: return "❌ Type ke liye node_id chahiye"
        val text = action.text ?: return "❌ Text nahi diya type ke liye"

        var uiNode = UiTreeExtractor.findNodeById(nodes, nodeId)

        // Fallback: find any editable field
        if (uiNode == null) {
            uiNode = nodes.find { it.editable }
            if (uiNode != null) {
                Log.d(TAG, "Node $nodeId nahi mila, editable field use kar rahe hain: id=${uiNode.id}")
            }
        }

        if (uiNode == null) {
            return "❌ Text field nahi mila (node $nodeId)"
        }

        // Click to focus
        service.clickNode(uiNode.nodeInfo)
        Thread.sleep(300)

        val success = service.setTextOnNode(uiNode.nodeInfo, text)
        return if (success) "✅ Type kiya: '$text'" else "❌ Type nahi ho paya"
    }

    // =========================================================================
    // Scroll — Gesture swipe fallback
    // =========================================================================
    private fun executeScroll(
        forward: Boolean,
        action: AgentAction,
        nodes: List<UiTreeExtractor.UiNode>,
        service: AutoAgentService
    ): String {
        val nodeId = action.nodeId

        // Try to find a scrollable node
        val scrollNode = if (nodeId != null) {
            UiTreeExtractor.findNodeById(nodes, nodeId)
        } else {
            // Auto-find scrollable container
            nodes.find { it.scrollable }
        }

        if (scrollNode != null) {
            if (forward) {
                service.scrollForward(scrollNode.nodeInfo)
            } else {
                service.scrollBackward(scrollNode.nodeInfo)
            }
        } else {
            // Global swipe (always works)
            val root = service.getRootNode()
            if (root != null) {
                if (forward) service.scrollForward(root)
                else service.scrollBackward(root)
            }
        }

        return if (forward) "✅ Neeche scroll kiya" else "✅ Upar scroll kiya"
    }

    // =========================================================================
    // Tap at XY coordinates (direct gesture)
    // =========================================================================
    private fun executeTapXY(action: AgentAction, service: AutoAgentService): String {
        val x = action.x ?: return "❌ x coordinate chahiye"
        val y = action.y ?: return "❌ y coordinate chahiye"
        service.tapAt(x.toFloat(), y.toFloat())
        return "✅ Tap kiya ($x, $y) pe"
    }

    // =========================================================================
    // Custom swipe gesture
    // =========================================================================
    private fun executeSwipe(action: AgentAction, service: AutoAgentService): String {
        // Use text field for direction hint
        val direction = action.text?.lowercase() ?: "down"
        when (direction) {
            "left" -> service.performGlobalSwipe(800f, 1200f, 200f, 1200f, 300)
            "right" -> service.performGlobalSwipe(200f, 1200f, 800f, 1200f, 300)
            "up" -> service.performGlobalSwipe(540f, 1500f, 540f, 500f, 350)
            else -> service.performGlobalSwipe(540f, 500f, 540f, 1500f, 350)
        }
        return "✅ Swipe kiya: $direction"
    }

    // =========================================================================
    // Open URL
    // =========================================================================
    private fun executeOpenUrl(action: AgentAction, service: AutoAgentService): String {
        val url = action.url ?: action.text ?: return "❌ URL nahi diya"
        val context = service.applicationContext
        return try {
            val finalUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
                           else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "✅ URL khola: $finalUrl"
        } catch (e: Exception) {
            "❌ URL nahi khul paya: ${e.message}"
        }
    }

    // =========================================================================
    // App Launcher — Robust with multiple fallbacks
    // =========================================================================
    private fun openAppByName(context: android.content.Context, appName: String): String {
        val pm = context.packageManager

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else { 0 }

        val activities = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, resolveFlags)
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivities failed", e)
            emptyList()
        }

        Log.d(TAG, "${activities.size} launchable apps mile, dhundh rahe hain: '$appName'")

        // 1. Exact match
        val exactMatch = activities.firstOrNull { info ->
            info.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        }

        // 2. Partial match
        val fuzzyMatch = exactMatch ?: activities.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(appName, ignoreCase = true) ||
            appName.contains(label, ignoreCase = true)
        }

        // 3. Package name match
        val packageMatch = fuzzyMatch ?: activities.firstOrNull { info ->
            info.activityInfo.packageName.contains(appName, ignoreCase = true)
        }

        // 4. Word-by-word match (e.g., "whats app" → "WhatsApp")
        val wordMatch = packageMatch ?: run {
            val searchWords = appName.lowercase().replace(" ", "")
            activities.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().lowercase().replace(" ", "")
                label.contains(searchWords) || searchWords.contains(label)
            }
        }

        val resolved = wordMatch
        if (resolved != null) {
            val label = resolved.loadLabel(pm).toString()
            val packageName = resolved.activityInfo.packageName
            return try {
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "✅ '$label' khol diya"
                } else {
                    val directIntent = Intent().apply {
                        setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(directIntent)
                    "✅ '$label' khol diya (direct)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: '$label'", e)
                "❌ '$label' nahi khul paya: ${e.message}"
            }
        }

        Log.w(TAG, "App nahi mili: '$appName'")
        return "❌ '$appName' app nahi mili device pe"
    }

    // =========================================================================
    // Long Press at coordinates
    // =========================================================================
    private fun executeLongPress(action: AgentAction, service: AutoAgentService): String {
        val x = action.x ?: return "❌ x coordinate chahiye"
        val y = action.y ?: return "❌ y coordinate chahiye"
        service.longPressAt(x.toFloat(), y.toFloat())
        return "✅ Long press kiya ($x, $y) pe"
    }

    // =========================================================================
    // Screenshot
    // =========================================================================
    private fun executeScreenshot(service: AutoAgentService): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "✅ Screenshot le liya"
        } else {
            "❌ Screenshot API needs Android 9+"
        }
    }

    // =========================================================================
    // Clipboard: Copy / Paste / Select All
    // =========================================================================
    private fun executeCopy(service: AutoAgentService): String {
        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            // Select all then copy
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT)
            val args = android.os.Bundle()
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, focusedNode.text?.length ?: 0)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_COPY)
            return "✅ Text copy kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }

    private fun executePaste(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>, service: AutoAgentService): String {
        // If node_id given, focus that first
        val nodeId = action.nodeId
        if (nodeId != null) {
            val node = UiTreeExtractor.findNodeById(nodes, nodeId)
                ?: UiTreeExtractor.findNodeByText(nodes, action.text ?: "")
            if (node != null) {
                node.nodeInfo.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                node.nodeInfo.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(200)
            }
        }

        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
            return "✅ Text paste kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }


    // =========================================================================
    // Phase 2 — Calendar / Alarm / Timer / Maps / Camera / Media / Share
    // =========================================================================

    private fun openCalendar(
        service: AutoAgentService
    ): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)
            "📅 Calendar kholchi"
        } catch (e: Exception) {
            Log.e(TAG, "Calendar open failed", e)
            "❌ Calendar app paoa jayni"
        }
    }

    private fun setAlarm(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                action.text?.takeIf { it.isNotBlank() }?.let {
                    putExtra(AlarmClock.EXTRA_MESSAGE, it)
                }

                if (action.x != null) {
                    putExtra(AlarmClock.EXTRA_HOUR, action.x)
                }

                if (action.y != null) {
                    putExtra(AlarmClock.EXTRA_MINUTES, action.y)
                }
            }

            service.applicationContext.startActivity(intent)
            "⏰ Alarm set korar screen khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Alarm open failed", e)
            "❌ Alarm app khola jayni"
        }
    }

    private fun setTimer(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        return try {
            val seconds = action.x
                ?: action.text?.trim()?.toIntOrNull()

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                if (seconds != null && seconds > 0) {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                }

                action.text?.takeIf { it.isNotBlank() }?.let {
                    putExtra(AlarmClock.EXTRA_MESSAGE, it)
                }
            }

            service.applicationContext.startActivity(intent)
            "⏱️ Timer screen khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Timer open failed", e)
            "❌ Timer app khola jayni"
        }
    }

    private fun openMaps(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        return try {
            val query = action.text
                ?: action.url
                ?: return "❌ Maps er location/search deya hoyni"

            val uri = Uri.parse(
                "geo:0,0?q=${Uri.encode(query)}"
            )

            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)
            "🗺️ Maps khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Maps open failed", e)
            "❌ Maps khola jayni"
        }
    }

    private fun openCamera(
        service: AutoAgentService
    ): String {
        return try {
            val intent = Intent("android.media.action.IMAGE_CAPTURE").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)
            "📷 Camera khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Camera open failed", e)
            "❌ Camera khola jayni"
        }
    }

    private fun mediaKey(
        keyCode: Int,
        service: AutoAgentService
    ): String {
        return try {
            val audioManager =
                service.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager

            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            )

            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, keyCode)
            )

            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭️ পরের গান"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮️ আগের গান"
                else -> "▶️ Media play/pause"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Media control failed", e)
            "❌ Media control kaj koreni"
        }
    }

    private fun shareText(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        val text = action.text
            ?: return "❌ Share korar text deya hoyni"

        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Share with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(chooser)
            "📤 Share menu khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Share failed", e)
            "❌ Share menu khola jayni"
        }
    }

    private fun openEmail(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        return try {
            val uri = Uri.parse(
                "mailto:${action.contactName ?: ""}"
            )

            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                action.text?.takeIf { it.isNotBlank() }?.let {
                    putExtra(Intent.EXTRA_TEXT, it)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)
            "📧 Email app khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Email open failed", e)
            "❌ Email app khola jayni"
        }
    }

    private fun openDownloads(
        service: AutoAgentService
    ): String {
        return try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)
            "📥 File/Downloads screen khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Downloads open failed", e)
            "❌ File manager screen khola jayni"
        }
    }

    // =========================================================================
    // Phase 1A — Contacts / Phone / SMS
    // =========================================================================

    private data class ContactResult(
        val name: String,
        val number: String
    )

    private fun findContact(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        val query = action.contactName
            ?: action.text
            ?: return "❌ Contact er naam deya hoyni"

        val contact = resolveContact(service, query)
            ?: return "❌ '$query' naam er contact paoa jayni"

        return "👤 ${contact.name}: ${contact.number}"
    }

    private fun callContact(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        val query = action.contactName
            ?: action.text
            ?: return "❌ Contact er naam deya hoyni"

        val contact = resolveContact(service, query)
            ?: return "❌ '$query' naam er contact paoa jayni"

        return try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(contact.number)}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)

            "📞 ${contact.name} er number dialer e khulechi"
        } catch (e: Exception) {
            Log.e(TAG, "Dialer open failed", e)
            "❌ Phone dialer khola jayni"
        }
    }

    private fun sendSms(
        action: AgentAction,
        service: AutoAgentService
    ): String {
        val contactName = action.contactName
            ?: return "❌ SMS contact er naam deya hoyni"

        val message = action.text
            ?: return "❌ SMS message deya hoyni"

        val contact = resolveContact(service, contactName)
            ?: return "❌ '$contactName' naam er contact paoa jayni"

        return try {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${Uri.encode(contact.number)}")
            ).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            service.applicationContext.startActivity(intent)

            "💬 ${contact.name} er jonno SMS ready korechi"
        } catch (e: Exception) {
            Log.e(TAG, "SMS app open failed", e)
            "❌ SMS app khola jayni"
        }
    }

    private fun resolveContact(
        service: AutoAgentService,
        query: String
    ): ContactResult? {

        val context = service.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CONTACTS permission missing")
            return null
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%${query.trim()}%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->

                if (!cursor.moveToFirst()) {
                    return null
                }

                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )

                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                if (nameIndex < 0 || numberIndex < 0) {
                    return null
                }

                val name = cursor.getString(nameIndex)
                val number = cursor.getString(numberIndex)

                if (name.isNullOrBlank() || number.isNullOrBlank()) {
                    null
                } else {
                    ContactResult(name, number)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Contacts permission denied", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
            null
        }
    }


    private fun executeSelectAll(action: AgentAction, nodes: List<UiTreeExtractor.UiNode>, service: AutoAgentService): String {
        val focusedNode = service.getRootNode()?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val textLength = focusedNode.text?.length ?: 0
            val args = android.os.Bundle()
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            args.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            return "✅ Sab text select kiya"
        }
        return "❌ Koi text field focused nahi hai"
    }
}
