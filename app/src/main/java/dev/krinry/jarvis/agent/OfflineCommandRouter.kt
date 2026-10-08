package dev.krinry.jarvis.agent

object OfflineCommandRouter {

    fun route(command: String): ActionExecutor.AgentAction? {
        val text = command.trim().lowercase()

        fun action(
            name: String,
            app: String? = null,
            speech: String = "",
            reason: String
        ) = ActionExecutor.AgentAction(
            action = name,
            nodeId = null,
            text = null,
            appName = app,
            contactName = null,
            url = null,
            speech = speech,
            status = "in_progress",
            x = null,
            y = null,
            reason = reason
        )

        return when {
            text.contains("back") ||
            text.contains("পিছনে") ||
            text.contains("ফিরে") ->
                action(
                    "back",
                    speech = "পিছনে যাচ্ছি।",
                    reason = "Back command"
                )

            text.contains("home") ||
            text.contains("হোম") ->
                action(
                    "home",
                    speech = "হোমে যাচ্ছি।",
                    reason = "Home command"
                )

            text.contains("recent") ||
            text.contains("recents") ||
            text.contains("রিসেন্ট") ->
                action(
                    "recent",
                    speech = "Recent apps খুলছি।",
                    reason = "Recent apps command"
                )

            text.contains("notification") ||
            text.contains("নোটিফিকেশন") ->
                action(
                    "open_notifications",
                    speech = "নোটিফিকেশন খুলছি।",
                    reason = "Notifications command"
                )

            text.contains("calendar") ||
            text.contains("ক্যালেন্ডার") ->
                action(
                    "open_calendar",
                    app = "Calendar",
                    speech = "ক্যালেন্ডার খুলছি।",
                    reason = "Calendar command"
                )

            text.contains("camera") ||
            text.contains("ক্যামেরা") ->
                action(
                    "open_camera",
                    speech = "ক্যামেরা খুলছি।",
                    reason = "Camera command"
                )

            text.contains("chrome") ||
            text.contains("ক্রোম") ->
                action(
                    "open_app",
                    app = "Chrome",
                    speech = "Chrome খুলছি।",
                    reason = "Open Chrome"
                )

            text.contains("whatsapp") ||
            text.contains("হোয়াটসঅ্যাপ") ||
            text.contains("হোয়াটসঅ্যাপ") ->
                action(
                    "open_app",
                    app = "WhatsApp",
                    speech = "WhatsApp খুলছি।",
                    reason = "Open WhatsApp"
                )

            text.contains("youtube") ||
            text.contains("ইউটিউব") ->
                action(
                    "open_app",
                    app = "YouTube",
                    speech = "YouTube খুলছি।",
                    reason = "Open YouTube"
                )

            text.contains("settings") ||
            text.contains("setting") ||
            text.contains("সেটিংস") ->
                action(
                    "open_app",
                    app = "Settings",
                    speech = "Settings খুলছি।",
                    reason = "Open Settings"
                )

            else -> null
        }
    }
}
