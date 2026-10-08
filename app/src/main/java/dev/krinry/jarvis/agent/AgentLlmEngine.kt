package dev.krinry.jarvis.agent

import android.content.Context
import android.util.Log
import dev.krinry.jarvis.ai.GroqApiClient
import dev.krinry.jarvis.service.AutoAgentService
import kotlinx.coroutines.*

/**
 * AgentLlmEngine — The brain of Krinry AI (Jarvis).
 *
 * Loop: Read screen → build prompt → call LLM → speak → execute → VERIFY → repeat
 *
 * Key fixes:
 * - VERIFICATION: Agent re-reads screen after "done" to confirm task actually completed
 * - Bengali status updates shown to user
 * - Agent speaks Bengali summary of what it's doing
 * - Coordinates (cx, cy) in UI tree for gesture-based tap
 * - When message typed, agent must find and click SEND button before saying done
 */
class AgentLlmEngine(private val context: Context) {

    private val ttsManager = AgentTtsManager(context)

    // P3F — persistent long-term memory.
    // Stores only short, non-sensitive context locally.
    private val longTermMemory = LongTermMemory(context)

    companion object {
        private const val MAX_ACTION_RETRIES = 3
        private const val RETRY_DELAY = 900L
        private const val MEMORY_SIZE = 20

        private const val TAG = "AgentLlmEngine"
        private const val MAX_ITERATIONS = 30
        private const val SCREEN_SETTLE_DELAY = 600L
        private const val MAX_HISTORY_MESSAGES = 10  // Keep small for token savings

        // Compressed system prompt: ~600 tokens vs ~1800 before (67% savings)
        private const val SYSTEM_PROMPT = """You are Krinry, AI phone assistant. Full device control via AccessibilityService. Respond ONLY in valid JSON, no markdown.

ACTIONS (JSON format: {"action":"X","speech":"Bengali or empty","reason":"why","status":"in_progress|done"} + action-specific fields):
- open_app: +app_name | click: +node_id | type: +node_id,text | tap_xy: +x,y | long_press: +x,y
- scroll_down/scroll_up | swipe: +text(left|right|up|down) | back/home/recent
- open_url: +url | screenshot | copy | paste: +node_id | select_all | open_notifications
- find_contact: +contact_name
- call_contact: +contact_name
- send_sms: +contact_name,text
- open_calendar
- set_alarm: +x(hour),y(minutes),text(label)
- set_timer: +x(seconds),text(label)
- open_maps: +text(destination)
- open_camera
- media_play_pause | media_next | media_previous
- share_text: +text
- open_email: +contact_name,text
- open_downloads
- wait | done: status="done"

UI nodes: i=id,t=text,d=desc,T=type(B=Button,E=EditText,IB=ImageButton,TV=TextView,IV=ImageView),x=centerX,y=centerY,c=clickable,e=editable,s=scrollable. Use node_id(i) for click/type. Fallback: tap_xy with x,y coords.

RULES:
1. Speech: Bengali only. First step=short confirm, middle=empty, done=completion msg, error=Bengali explain
2. Apps: ALWAYS open_app first, never scroll home. Use exact name: "WhatsApp","YouTube","Chrome"
3. NEVER say done early. After type→MUST click Send button→verify→done. Complete full task inside app
4. Node missing? scroll→tap_xy→search by text. Give up only after trying all
5. Verify before done: check screen confirms action worked
6. Contacts: use find_contact/call_contact/send_sms with contact_name. Never guess a contact.
7. Multiple contact matches? Ask user which one. Do not choose randomly.
8. call_contact opens the phone dialer only. Never silently place a call.
9. send_sms opens the SMS composer with the message filled in. Never silently send SMS.
10. For SMS, preserve the user's intended message exactly. Do not invent or alter important content.
11. After call_contact/send_sms opens the target app, verify the correct contact/number/message is visible before saying done.
12. Calendar: open the calendar app; do not silently create/delete events.
13. Alarm: open the alarm UI with requested hour/minute; do not silently create an alarm without user confirmation.
14. Timer: open the timer UI with requested duration; do not silently start a timer without user confirmation.
15. Maps: use open_maps with the user's destination/search text.
16. Camera: use open_camera; do not take or send photos without explicit user instruction.
17. Media controls: only play/pause/next/previous. Never purchase or subscribe.
18. Share/email: open the system chooser or email composer; never silently send.
19. Downloads/files: open the system file picker. Do not delete or overwrite files unless explicitly instructed."""
    }

    var onStatusUpdate: ((String) -> Unit)? = null

    // Short-term conversation memory for the current agent session.
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    // P3A persistent-style in-memory task memory.
    // Kept local to the running Jarvis process.
    private val taskMemory = mutableListOf<String>()

    // Track repeated failures so the agent can change strategy.
    private val actionFailures = mutableMapOf<String, Int>()

    private var currentJob: Job? = null

    fun startTask(voiceCommand: String, scope: CoroutineScope) {
        currentJob?.cancel()
        ttsManager.stop()

        // P3F — handle explicit memory commands locally.
        // These do not need an LLM call.
        if (handleMemoryCommand(voiceCommand)) {
            currentJob = null
            return
        }

        // New task gets a fresh failure map but keeps useful short memory.
        actionFailures.clear()
        rememberTask(voiceCommand)

        currentJob = scope.launch {
            runAgentLoop(voiceCommand)
        }
    }

    /**
     * P3F — Explicit long-term memory commands.
     *
     * Examples:
     *   "মনে রাখো আমি বাংলা ভাষায় কথা বলি"
     *   "remember that I use Bengali"
     *   "এটা ভুলে যাও বাংলা ভাষা"
     *   "আমার memory দেখাও"
     *
     * Sensitive information should never be intentionally stored.
     */
    private fun handleMemoryCommand(command: String): Boolean {
        val raw = command.trim()
        if (raw.isBlank()) return false

        val lower = raw.lowercase()

        val rememberPrefixes = listOf(
            "মনে রাখো",
            "মনে রাখ",
            "মনে রাখবেন",
            "remember that",
            "remember this",
            "remember"
        )

        val forgetPrefixes = listOf(
            "ভুলে যাও",
            "এটা ভুলে যাও",
            "forget this",
            "forget that",
            "forget"
        )

        val showPrefixes = listOf(
            "আমার memory দেখাও",
            "মেমোরি দেখাও",
            "মনে কি আছে",
            "কি কি মনে আছে",
            "show memory",
            "show memories",
            "what do you remember"
        )

        // SHOW MEMORY
        if (showPrefixes.any { lower == it || lower.startsWith("$it ") }) {
            val memories = longTermMemory.getAll()

            if (memories.isEmpty()) {
                onStatusUpdate?.invoke("🧠 এখনো কোনো long-term memory নেই")
                ttsManager.speak("এখনো কোনো তথ্য মনে রাখা হয়নি।")
            } else {
                val recent = memories.takeLast(8)

                onStatusUpdate?.invoke(
                    "🧠 Memory: ${recent.size}টি তথ্য"
                )

                val spoken = recent.joinToString(
                    separator = "। ",
                    prefix = "আমি মনে রেখেছি: "
                )

                ttsManager.speak(
                    spoken.take(700)
                )
            }

            return true
        }

        // FORGET
        val forgetPrefix = forgetPrefixes.firstOrNull {
            lower.startsWith(it)
        }

        if (forgetPrefix != null) {
            val target = raw
                .substring(forgetPrefix.length)
                .trim()
                .trim(':', '-', ' ')

            if (target.isBlank()) {
                onStatusUpdate?.invoke("🧠 কোন তথ্য ভুলতে হবে তা বলুন")
                ttsManager.speak("কোন তথ্য ভুলে যেতে হবে বলুন।")
                return true
            }

            val removed = longTermMemory.forget(target)

            if (removed) {
                onStatusUpdate?.invoke("🧠 Memory মুছে দেওয়া হয়েছে")
                ttsManager.speak("ঠিক আছে, তথ্যটি ভুলে গেছি।")
            } else {
                onStatusUpdate?.invoke("🧠 ওই তথ্যটি memory-তে পাওয়া যায়নি")
                ttsManager.speak("ওই তথ্যটি আমার memory-তে পাওয়া যায়নি।")
            }

            return true
        }

        // REMEMBER
        val rememberPrefix = rememberPrefixes.firstOrNull {
            lower.startsWith(it)
        }

        if (rememberPrefix != null) {
            val memory = raw
                .substring(rememberPrefix.length)
                .trim()
                .trim(':', '-', ' ')

            if (memory.isBlank()) {
                onStatusUpdate?.invoke("🧠 কী মনে রাখতে হবে তা বলুন")
                ttsManager.speak("কী মনে রাখতে হবে বলুন।")
                return true
            }

            if (containsSensitiveMemory(memory)) {
                onStatusUpdate?.invoke(
                    "🔐 Sensitive তথ্য memory-তে রাখা যাবে না"
                )
                ttsManager.speak(
                    "পাসওয়ার্ড, OTP বা sensitive তথ্য আমি memory-তে রাখব না।"
                )
                return true
            }

            longTermMemory.add(memory)
            rememberTask("USER_MEMORY: $memory")

            onStatusUpdate?.invoke("🧠 মনে রাখলাম")
            ttsManager.speak("ঠিক আছে, মনে রাখলাম।")

            return true
        }

        return false
    }

    /**
     * Basic safety filter for explicit long-term memory.
     * This is intentionally conservative.
     */
    private fun containsSensitiveMemory(text: String): Boolean {
        val value = text.lowercase()

        val sensitiveTerms = listOf(
            "password",
            "passcode",
            "pin",
            "otp",
            "verification code",
            "cvv",
            "cvc",
            "api key",
            "secret key",
            "private key",
            "পাসওয়ার্ড",
            "পিন",
            "ওটিপি",
            "কোড",
            "ব্যাংক",
            "bank account",
            "card number",
            "credit card",
            "debit card"
        )

        return sensitiveTerms.any { value.contains(it) }
    }

    fun cancelTask() {
        currentJob?.cancel()
        currentJob = null
        ttsManager.stop()
        onStatusUpdate?.invoke("⏹ Ruk gaya")
    }

    private suspend fun runAgentLoop(command: String) {
        val service = AutoAgentService.instance
        if (service == null) {
            onStatusUpdate?.invoke("❌ Accessibility Service on nahi hai")
            ttsManager.speak("Accessibility Service chalu karo pehle.")
            return
        }

        onStatusUpdate?.invoke("🧠 Samajh raha hoon: \"$command\"")
        Log.d(TAG, "Starting task: $command")

        for (iteration in 1..MAX_ITERATIONS) {
            if (!isActive) return

            Log.d(TAG, "=== Step $iteration ===")

            // 1. Screen padho
            val rootNode = service.getRootNode()
            if (rootNode == null) {
                onStatusUpdate?.invoke("❌ Screen nahi padh paya")
                delay(800)
                continue
            }

            val uiNodes = UiTreeExtractor.extractTree(rootNode)
            val uiJson = UiTreeExtractor.toJson(uiNodes)
            Log.d(TAG, "UI nodes: ${uiNodes.size}")

            // 2. P3C — Screenshot + Vision AI
            var visionText: String? = null

            if (iteration == 1 || iteration > 1) {
                val screenshot = kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                    service.captureScreenBase64 { base64 ->
                        if (continuation.isActive) {
                            continuation.resume(base64) {}
                        }
                    }
                }

                if (!screenshot.isNullOrEmpty()) {
                    onStatusUpdate?.invoke("👁️ Screen bujhtechi...")

                    visionText = GroqApiClient.analyzeScreenshot(
                        context = context,
                        imageBase64 = screenshot,
                        userPrompt = """
                            Analyze this Android screenshot for the current user task:
                            "$command"

                            Identify useful visible UI elements such as:
                            buttons, text fields, menus, icons, dialogs and important text.

                            If a target UI element is visible, describe its location
                            and purpose concisely. Do not invent elements that are not visible.
                        """.trimIndent()
                    )

                    Log.d(
                        TAG,
                        "P3C Vision result: ${visionText?.take(500)}"
                    )
                } else {
                    Log.w(TAG, "P3C: Screenshot capture returned null")
                }
            }

            // 3. Compact LLM message (save tokens)
            val userMessage = if (iteration == 1) {
                buildPlanningMessage(
                    command,
                    uiJson + if (!visionText.isNullOrBlank()) {
                        "\n\nSCREEN VISION:\n$visionText"
                    } else {
                        ""
                    }
                )
            } else {
                buildFollowupMessage(
                    uiJson + if (!visionText.isNullOrBlank()) {
                        "\n\nSCREEN VISION:\n$visionText"
                    } else {
                        ""
                    },
                    iteration
                )
            }

            // 3. LLM call (GroqApiClient handles retries internally)
            onStatusUpdate?.invoke("🤔 Step $iteration...")
            val llmResponse = try {
                GroqApiClient.agentChat(context, SYSTEM_PROMPT, conversationHistory, userMessage)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed: ${e.message}")
                onStatusUpdate?.invoke("❌ ${e.message?.take(50) ?: "Server error"}")
                ttsManager.speak("Server se jawab nahi aaya.")
                return
            }

            if (llmResponse == null) {
                // P3E — Offline fallback for common safe commands.
                val offlineAction = OfflineCommandRouter.route(command)

                if (offlineAction != null) {
                    onStatusUpdate?.invoke("📴 Offline mode: ${offlineAction.reason}")

                    offlineAction.speech
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ttsManager.speak(it) }

                    val offlineResult = try {
                        ActionExecutor.execute(offlineAction, uiNodes)
                    } catch (e: Exception) {
                        Log.e(TAG, "Offline action failed", e)
                        "❌ Offline action failed: ${e.message ?: "unknown error"}"
                    }

                    onStatusUpdate?.invoke(offlineResult)

                    if (!offlineResult.startsWith("❌")) {
                        delay(SCREEN_SETTLE_DELAY)
                        continue
                    }
                }

                onStatusUpdate?.invoke("❌ Empty response from server")
                ttsManager.speak("ইন্টারনেট বা AI server পাওয়া যাচ্ছে না।")
                return
            }

            Log.d(TAG, "LLM response: $llmResponse")

            // 5. History me save karo
            addConversationMessage("user", userMessage)
            addConversationMessage("assistant", llmResponse)

            // 6. Parse action
            val action = ActionExecutor.parseResponse(llmResponse)
            if (action == null) {
                onStatusUpdate?.invoke("❌ Response samajh nahi aaya")
                // Don't stop — try again with fresh screen
                delay(1000)
                continue
            }

            // 7. Bengali status update with reason
            val reasonText = action.reason ?: action.action
            onStatusUpdate?.invoke("⚡ ${getBanglaAction(action.action)}: $reasonText")

            // 8. TTS speak (only on first, done, or error)
            action.speech?.takeIf { it.isNotBlank() }?.let { speechText ->
                ttsManager.speak(speechText)
            }

            // 9. Check if done
            if (action.status == "done" || action.action == "done") {
                onStatusUpdate?.invoke("✅ Kaj hoyeche: ${action.reason ?: "Task complete"}")
                delay(2500) // TTS finish hone do
                return
            }

            // 10. P3D — Execute + smart verification + retry.
            val result = executeWithVerification(
                action = action,
                uiNodes = uiNodes,
                iteration = iteration
            )

            Log.d(TAG, "P3D final result: $result")
            onStatusUpdate?.invoke(result)

            // If action failed, inform LLM through conversation context.
            if (result.startsWith("❌")) {
                Log.w(TAG, "P3D action failed: $result")

                addConversationMessage(
                    "user",
                    "SYSTEM: Previous action failed or could not be verified. " +
                        "Error: $result. Re-read the current screen and choose another approach."
                )
            }

            // 11. Screen settle hone do
            delay(SCREEN_SETTLE_DELAY)
        }

        onStatusUpdate?.invoke("⚠️ Onek steps hoye geche ($MAX_ITERATIONS)")
        ttsManager.speak("কাজটি সময়মতো শেষ করা যায়নি। ছোট একটি কমান্ড চেষ্টা করুন।")
    }

    /**
     * Bengali action name for status display.
     */

    // =========================================================================
    // P3A — Planning / Retry / Memory helpers
    // =========================================================================

    private fun buildPlanningMessage(
        command: String,
        uiJson: String
    ): String {
        // P3F — Smart Context Relevance.
        // Do not send the entire long-term memory to the LLM.
        // Select only memories related to the current command.
        val relevantMemory = getRelevantMemory(command)

        val memory = if (relevantMemory.isEmpty()) {
            "MEMORY:none"
        } else {
            "MEMORY:\n" + relevantMemory.joinToString("\n")
        }

        return """
CMD:$command

$memory

PLAN:
Break the user's request into the smallest safe sequence of actions.
Do not perform unrelated actions.
Do not claim completion until the final state is verified.

CURRENT_UI:
$uiJson
""".trimIndent()
    }

    /**
     * P3F — Select only relevant memories for the current command.
     *
     * Uses simple local keyword matching so no extra API/model call
     * is required. Maximum 6 memories are returned.
     */
    private fun getRelevantMemory(command: String): List<String> {
        val memories = longTermMemory.getAll()

        if (memories.isEmpty()) {
            return taskMemory.takeLast(3)
        }

        val commandWords = tokenizeForMemory(command)

        if (commandWords.isEmpty()) {
            return memories.takeLast(5)
        }

        val scored = memories.map { memory ->
            val memoryWords = tokenizeForMemory(memory)

            val overlap = commandWords.count { word ->
                memoryWords.contains(word)
            }

            val score = when {
                overlap >= 3 -> 5
                overlap == 2 -> 3
                overlap == 1 -> 1
                else -> 0
            }

            memory to score
        }

        val relevant = scored
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }

        // If nothing matches, provide only the newest 2 memories
        // rather than sending the whole memory database.
        return if (relevant.isNotEmpty()) {
            relevant
        } else {
            memories.takeLast(2)
        }
    }

    private fun tokenizeForMemory(text: String): Set<String> {
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .filterNot {
                it in setOf(
                    "আমি",
                    "আমার",
                    "আমাকে",
                    "তুমি",
                    "তোমার",
                    "করো",
                    "করতে",
                    "দিয়ে",
                    "এটা",
                    "ওটা",
                    "that",
                    "this",
                    "please",
                    "remember",
                    "forget",
                    "show"
                )
            }
            .toSet()
    }

    private fun buildFollowupMessage(
        uiJson: String,
        iteration: Int
    ): String {
        val recentFailures = actionFailures
            .filterValues { it > 0 }
            .entries
            .joinToString("\n") {
                "${it.key}: failed ${it.value} time(s)"
            }

        return """
STEP:$iteration

PREVIOUS_FAILURES:
${if (recentFailures.isBlank()) "none" else recentFailures}

CURRENT_UI:
$uiJson

INSTRUCTION:
Continue the plan.
Use the current screen, not an old screen assumption.
If the previous approach failed, choose another valid approach.
Verify the result before returning done.
""".trimIndent()
    }

    private suspend fun executeWithRetry(
        action: ActionExecutor.AgentAction,
        uiNodes: List<UiTreeExtractor.UiNode>,
        iteration: Int
    ): String {

        val actionKey = buildActionKey(action)

        var lastResult = "❌ Action failed"

        for (attempt in 1..MAX_ACTION_RETRIES) {
            if (!isActive) {
                return "⏹ Task stopped"
            }

            if (attempt > 1) {
                onStatusUpdate?.invoke(
                    "🔄 Retry $attempt/$MAX_ACTION_RETRIES: ${getBanglaAction(action.action)}"
                )
                delay(RETRY_DELAY)
            }

            lastResult = try {
                ActionExecutor.execute(action, uiNodes)
            } catch (e: Exception) {
                Log.e(TAG, "Action execution exception", e)
                "❌ ${e.message ?: "Action execution failed"}"
            }

            Log.d(
                TAG,
                "P3A action=$actionKey attempt=$attempt result=$lastResult"
            )

            if (!lastResult.startsWith("❌")) {
                actionFailures.remove(actionKey)
                val successMemory =
                    "SUCCESS: ${action.action} -> ${lastResult.take(120)}"

                rememberTask(successMemory)

                // P3F — persist useful successful context across app restarts.
                // Keep only safe, short execution context.
                longTermMemory.add(successMemory)

                return lastResult
            }

            val failures = (actionFailures[actionKey] ?: 0) + 1
            actionFailures[actionKey] = failures
        }

        rememberTask(
            "FAILED: ${action.action} -> ${lastResult.take(120)}"
        )

        return lastResult
    }

    /**
     * P3D — Smart action verification.
     *
     * First execute using the existing P3A retry mechanism.
     * For actions where a screen transition/state change is expected,
     * re-read the Accessibility tree after the action.
     *
     * We intentionally do not require a UI change for actions such as
     * media controls, copy, or paste because those can succeed without
     * producing a different screen tree.
     */
    private suspend fun executeWithVerification(
        action: ActionExecutor.AgentAction,
        uiNodes: List<UiTreeExtractor.UiNode>,
        iteration: Int
    ): String {

        val result = executeWithRetry(
            action = action,
            uiNodes = uiNodes,
            iteration = iteration
        )

        if (result.startsWith("❌") || !needsScreenVerification(action.action)) {
            return result
        }

        delay(800)

        val service = AutoAgentService.instance
            ?: return "❌ Accessibility service পাওয়া যাচ্ছে না"

        val freshRoot = service.getRootNode()
            ?: return "❌ Action হয়েছে, কিন্তু screen verify করা যায়নি"

        val freshNodes = UiTreeExtractor.extractTree(freshRoot)

        if (freshNodes.isEmpty()) {
            return "❌ Action হয়েছে, কিন্তু নতুন screen state পাওয়া যায়নি"
        }

        val oldSignature = buildUiSignature(uiNodes)
        val newSignature = buildUiSignature(freshNodes)

        if (oldSignature != newSignature) {
            rememberTask(
                "VERIFIED: ${action.action} -> screen changed"
            )
            Log.d(TAG, "P3D verified ${action.action}: screen changed")
            return result
        }

        /*
         * Some actions can legitimately leave the same UI tree.
         * For those we accept the executor result. For navigation,
         * opening an app/URL, and explicit clicks, an unchanged screen
         * is suspicious, so tell the next LLM iteration to reconsider.
         */
        if (requiresVisibleStateChange(action.action)) {
            rememberTask(
                "UNVERIFIED: ${action.action} -> screen unchanged"
            )

            return "❌ ${action.action} execute হয়েছে, কিন্তু screen change verify করা যায়নি"
        }

        rememberTask(
            "VERIFIED: ${action.action} -> executor success"
        )

        return result
    }

    private fun needsScreenVerification(action: String): Boolean {
        return action in setOf(
            "click",
            "tap_xy",
            "long_press",
            "open_app",
            "open_url",
            "open_notifications",
            "open_calendar",
            "open_maps",
            "open_camera",
            "back",
            "home",
            "recent",
            "scroll_down",
            "scroll_up",
            "swipe",
            "set_alarm",
            "set_timer",
            "share_text",
            "open_email",
            "open_downloads"
        )
    }

    private fun requiresVisibleStateChange(action: String): Boolean {
        return action in setOf(
            "click",
            "tap_xy",
            "open_app",
            "open_url",
            "open_notifications",
            "open_calendar",
            "open_maps",
            "open_camera",
            "back",
            "home",
            "recent"
        )
    }

    private fun buildUiSignature(
        nodes: List<UiTreeExtractor.UiNode>
    ): String {
        return nodes
            .take(120)
            .joinToString("|") {
                listOf(
                    it.id,
                    it.text,
                    it.contentDescription,
                    it.className,
                    it.clickable,
                    it.editable,
                    it.scrollable
                ).joinToString(":")
            }
    }

    private fun buildActionKey(
        action: ActionExecutor.AgentAction
    ): String {
        return listOf(
            action.action,
            action.nodeId?.toString() ?: "",
            action.contactName ?: "",
            action.appName ?: "",
            action.text?.take(80) ?: ""
        ).joinToString("|")
    }

    private fun addConversationMessage(
        role: String,
        message: String
    ) {
        conversationHistory.add(role to message)

        while (conversationHistory.size > MAX_HISTORY_MESSAGES) {
            conversationHistory.removeAt(0)
        }
    }

    private fun rememberTask(
        memory: String
    ) {
        taskMemory.add(memory)

        while (taskMemory.size > MEMORY_SIZE) {
            taskMemory.removeAt(0)
        }
    }

    private fun getBanglaAction(action: String): String {
        return when (action) {
            "click" -> "ক্লিক করছি"
            "type" -> "টাইপ করছি"
            "scroll_down" -> "নিচে স্ক্রল করছি"
            "scroll_up" -> "উপরে স্ক্রল করছি"
            "back" -> "পেছনে যাচ্ছি"
            "home" -> "হোমে যাচ্ছি"
            "recent" -> "সাম্প্রতিক অ্যাপ দেখছি"
            "open_app" -> "অ্যাপ খুলছি"
            "open_url" -> "URL খুলছি"
            "tap_xy" -> "ট্যাপ করছি"
            "long_press" -> "লং প্রেস করছি"
            "swipe" -> "সোয়াইপ করছি"
            "screenshot" -> "স্ক্রিনশট নিচ্ছি"
            "copy" -> "কপি করছি"
            "paste" -> "পেস্ট করছি"
            "select_all" -> "সব নির্বাচন করছি"
            "open_notifications" -> "নোটিফিকেশন দেখছি"
            "find_contact" -> "কন্টাক্ট খুঁজছি"
            "call_contact" -> "কলের জন্য নম্বর খুলছি"
            "send_sms" -> "SMS প্রস্তুত করছি"
            "open_calendar" -> "ক্যালেন্ডার খুলছি"
            "set_alarm" -> "অ্যালার্ম খুলছি"
            "set_timer" -> "টাইমার খুলছি"
            "open_maps" -> "ম্যাপ খুলছি"
            "open_camera" -> "ক্যামেরা খুলছি"
            "media_play_pause" -> "মিডিয়া কন্ট্রোল করছি"
            "media_next" -> "পরের গান চালাচ্ছি"
            "media_previous" -> "আগের গান চালাচ্ছি"
            "share_text" -> "শেয়ার মেনু খুলছি"
            "open_email" -> "ইমেইল খুলছি"
            "open_downloads" -> "ফাইল খুলছি"
            "wait" -> "অপেক্ষা করছি"
            "done" -> "হয়ে গেছে"
            else -> action
        }
    }

    private val isActive: Boolean
        get() = currentJob?.isActive == true
}
