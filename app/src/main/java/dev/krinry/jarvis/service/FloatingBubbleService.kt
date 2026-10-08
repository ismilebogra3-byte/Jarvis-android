package dev.krinry.jarvis.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import dev.krinry.jarvis.MainActivity
import dev.krinry.jarvis.R
import dev.krinry.jarvis.agent.AgentLlmEngine
import dev.krinry.jarvis.agent.AgentTtsManager
import dev.krinry.jarvis.ai.GroqApiClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatingBubbleService — Always-on-top overlay bubble for Jarvis AI.
 *
 * Features:
 * - Foreground service with notification (won't be killed by Android)
 * - Tap to START recording → tap again to STOP and process
 * - Long-press for menu: Clear subtitles, Keep screen on, Repeat last
 * - Subtitle-style scrolling transcript (NO auto-disappear)
 * - Pulse animation while listening, orbit animation while thinking
 * - Vibration feedback on start/stop
 * - Sound effect on task complete
 * - Draggable bubble
 * - Double-tap to repeat last command
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"
        const val ACTION_START = "dev.krinry.jarvis.START_BUBBLE"
        const val ACTION_STOP = "dev.krinry.jarvis.STOP_BUBBLE"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "jarvis_agent_channel"

        private const val MAX_RECORDING_SECONDS = 10
        private const val SAMPLE_RATE = 16000
        private const val MAX_SUBTITLE_LINES = 5

        // =========================
        // Hello Nura Wake Word
        // =========================
        private const val WAKE_WORD = "hello nura"
        private const val WAKE_RESPONSE = "জি বস"

        private val WAKE_WORDS = listOf(
            "hello nura",
            "hello, nura",
            "helo nura",
            "hallo nura",
            "hello noora",
            "hello nur",
            "nura"
        )

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var subtitleView: View? = null
    private var subtitleClearJob: Job? = null
    private var agentEngine: AgentLlmEngine? = null
    private lateinit var wakeTtsManager: AgentTtsManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isListening = false
    private var isProcessingCommand = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null

    // Hello Nura wake-word state
    @Volatile
    private var wakeWordEnabled = true

    @Volatile
    private var isWakeListening = false

    private var wakeListeningJob: Job? = null
    private val subtitleHistory = mutableListOf<String>()

    // Keep screen on
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepScreenOn = false

    // Double-tap & last command
    private var lastTapTime = 0L
    private var lastCommand: String? = null

    // Thinking animation
    private var thinkingAnimator: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        wakeTtsManager = AgentTtsManager(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        agentEngine = AgentLlmEngine(applicationContext)
        agentEngine?.onStatusUpdate = { status ->
            scope.launch(Dispatchers.Main) {
                addSubtitle(status)
                if (status.startsWith("✅")) {
                    isProcessingCommand = false
                    stopThinkingAnimation()
                    playCompletionSound()
                    vibratePattern(longArrayOf(0, 80, 60, 80)) // success pattern

                    // Part 5: Task complete -> return to Hello Nura standby.
                    scope.launch {
                        delay(800)
                        if (!isProcessingCommand && !isListening && !isWakeListening) {
                            startWakeWordListener()
                        }
                    }
                } else if (status.startsWith("❌") || status.startsWith("⚠️") || status.startsWith("⏹")) {
                    isProcessingCommand = false
                    stopThinkingAnimation()
                    vibrateShort()
                }
            }
        }
        isRunning = true
        startForegroundNotification()

    // Voice-only mode: subtitle overlay without bubble UI.
    ensureSubtitleOverlay()


        // Part 5: Start Hello Nura standby automatically.
        scope.launch {
            delay(700)

            // Hello Nura standby auto-start.
            // Bubble UI remains OFF.
            if (!isProcessingCommand && !isListening) {
                if (isWakeListening) {
                    stopWakeWordListener()
                }
                startWakeWordListener()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        // Part 4: Bubble/icon UI disabled.
        // Jarvis now runs voice-only in the background.
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        stopRecording()
        stopThinkingAnimation()
        releaseWakeLock()
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        subtitleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null; subtitleView = null
        super.onDestroy()
    }

    // =========================================================================
    // === Foreground Notification (prevents Android from killing service) ===
    // =========================================================================

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "Jarvis AI Agent",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Jarvis background service"
                    setShowBadge(false)
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }

            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Jarvis Active")
                .setContentText("Tap bubble to give command")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(openApp)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service: ${e.message}")
            // Service still works, just not as foreground — may be killed by system
        }
    }

    // =========================================================================
    // === Bubble Creation ===
    // =========================================================================

    private fun createBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val bubbleSize = dpToPx(54)
        val bubble = FrameLayout(this).apply {
            background = createBubbleDrawable()
            elevation = dpToPx(8).toFloat()
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            val padding = dpToPx(13)
            setPadding(padding, padding, padding, padding)
        }
        bubble.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val bubbleParams = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(10); y = dpToPx(400)
        }

        // Touch: drag + tap + double-tap + long press
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isMoved = false
        val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val longPressRunnable = Runnable { if (!isMoved) { vibrateShort(); showBubbleMenu() } }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x; initialY = bubbleParams.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isMoved = false
                    longPressHandler.postDelayed(longPressRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dpToPx(5) || Math.abs(dy) > dpToPx(5)) {
                        if (!isMoved) {
                            isMoved = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    bubbleParams.x = initialX + dx; bubbleParams.y = initialY + dy
                    windowManager.updateViewLayout(bubble, bubbleParams); true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!isMoved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 350 && lastCommand != null) {
                            // Double-tap: repeat last command
                            onDoubleTap()
                        } else {
                            onBubbleTapped()
                        }
                        lastTapTime = now
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
        bubbleView = bubble

        // === Subtitle overlay ===
        val subtitleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10))
            background = createSubtitleBackground()
            visibility = View.GONE
        }

        val subtitleText = TextView(this).apply {
            id = View.generateViewId()
            tag = "subtitle_text"
            text = ""
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = MAX_SUBTITLE_LINES
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
            lineHeight = dpToPx(22)
        }
        subtitleContainer.addView(subtitleText)

        val subtitleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dpToPx(16)
            y = dpToPx(80)
        }

        windowManager.addView(subtitleContainer, subtitleParams)
        subtitleView = subtitleContainer
    }

    // =========================================================================

    // Voice-only subtitle overlay. Bubble UI remains OFF.
    private fun ensureSubtitleOverlay() {
        if (subtitleView != null) return

        try {
            val overlayType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE

            val subtitleContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10))
                background = createSubtitleBackground()
                visibility = View.VISIBLE
            }

            val tv = TextView(this).apply {
                tag = "subtitle_text"
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = MAX_SUBTITLE_LINES
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(4f, 1f, 1f, Color.BLACK)
            }

            subtitleContainer.addView(tv)

            val subtitleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = dpToPx(16)
                y = dpToPx(80)
            }

            windowManager.addView(subtitleContainer, subtitleParams)
            subtitleView = subtitleContainer
        } catch (e: Exception) {
            Log.e("Jarvis", "Subtitle overlay failed", e)
        }
    }

    // === Long-Press Menu ===
    // =========================================================================

    private fun showBubbleMenu() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val menuItems = mutableListOf<String>()
        menuItems.add("🔄 Repeat Last Command")
        menuItems.add("🗑️ Clear Subtitles")
        menuItems.add(if (keepScreenOn) "🌙 Screen: Auto-off" else "☀️ Screen: Always On")
        menuItems.add("❌ Close Jarvis")

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0xF0141428.toInt())
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), 0x40FFFFFF)
            }
            elevation = dpToPx(16).toFloat()
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        menuItems.forEachIndexed { index, item ->
            val tv = TextView(this).apply {
                text = item
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(14))
                setOnClickListener {
                    try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
                    when (index) {
                        0 -> repeatLastCommand()
                        1 -> clearSubtitles()
                        2 -> toggleKeepScreenOn()
                        3 -> stopSelf()
                    }
                }
            }
            menuLayout.addView(tv)
        }

        menuLayout.setOnClickListener {
            try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
        }

        windowManager.addView(menuLayout, menuParams)

        scope.launch {
            delay(5000)
            try { windowManager.removeView(menuLayout) } catch (_: Exception) {}
        }
    }

    private fun repeatLastCommand() {
        val cmd = lastCommand
        if (cmd.isNullOrBlank()) {
            addSubtitle("❌ No previous command")
            return
        }
        isProcessingCommand = true
        subtitleHistory.clear()
        addSubtitle("🔄 Repeating: \"$cmd\"")
        startThinkingAnimation()
        agentEngine?.startTask(cmd, scope)
    }

    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) {
            acquireWakeLock()
            addSubtitle("☀️ Screen will stay on")
        } else {
            releaseWakeLock()
            addSubtitle("🌙 Screen auto-off restored")
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "jarvis:screenon"
            )
        }
        wakeLock?.acquire(60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    // =========================================================================
    // === Subtitle System ===
    // =========================================================================

    private fun addSubtitle(text: String) {
        ensureSubtitleOverlay()

        subtitleClearJob?.cancel()

        subtitleView?.let { view ->
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
            }

            val tv = view.findViewWithTag<TextView>("subtitle_text")
            tv?.text = text

            subtitleClearJob = scope.launch {
                delay(5000)
                withContext(Dispatchers.Main) {
                    tv?.text = ""
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun clearSubtitles() {
        subtitleHistory.clear()
        subtitleView?.let { view ->
            val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 200 }
            view.startAnimation(fadeOut)
            scope.launch { delay(200); view.visibility = View.GONE }
        }
    }

    // =========================================================================
    // === Bubble Tap / Double-Tap ===
    // =========================================================================

    private fun onBubbleTapped() {
        if (isProcessingCommand) {
            agentEngine?.cancelTask()
            isProcessingCommand = false
            stopRecording()
            stopThinkingAnimation()
            addSubtitle("⏹ Cancelled")
            vibrateShort()
            return
        }
        if (isListening) stopRecordingAndProcess()
        else startWhisperRecording()
    }

    private fun onDoubleTap() {
        vibratePattern(longArrayOf(0, 50, 40, 50))
        repeatLastCommand()
    }

    // =========================================================================
    // === Vibration & Sound ===
    // =========================================================================

    private fun vibrateShort() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(50)
                }
            }
        } catch (_: Exception) {}
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION") v.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun playCompletionSound() {
        try {
            // Use system notification sound as completion tone
            val mp = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (_: Exception) {}
    }

    // =========================================================================
    // === Thinking Animation (rotating glow while LLM processing) ===
    // =========================================================================

    private fun startThinkingAnimation() {
        bubbleView?.let { view ->
            thinkingAnimator?.cancel()
            thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val angle = anim.animatedValue as Float
                    view.rotation = angle
                    // Subtle pulse
                    val scale = 1f + 0.08f * Math.sin(Math.toRadians(angle.toDouble() * 2)).toFloat()
                    view.scaleX = scale
                    view.scaleY = scale
                }
            }
            thinkingAnimator?.start()
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimator?.cancel()
        thinkingAnimator = null
        bubbleView?.let { view ->
            view.rotation = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    // =========================================================================
    // === Whisper STT Recording ===
    // =========================================================================

    // =========================================================================
    // === Hello Nura Wake Listener ===
    // =========================================================================

    private fun startWakeWordListener() {
        if (!wakeWordEnabled || isWakeListening || isListening || isProcessingCommand) {
            return
        }

        if (!AutoAgentService.isRunning()) {
            addSubtitle("Accessibility Service on korun")
            return
        }

        isWakeListening = true
        addSubtitle("Nura standby te ache")

        wakeListeningJob = scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (bufferSize <= 0) {
                    withContext(Dispatchers.Main) {
                        addSubtitle("Mic setup korte parini")
                    }
                    return@launch
                }

                while (isWakeListening && wakeWordEnabled) {

                    // Small chunk for wake-word detection.
                    val chunkSamples = SAMPLE_RATE * 3
                    val audioBuffer = ArrayList<Short>(chunkSamples)

                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 2
                    )

                    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                        recorder.release()

                        withContext(Dispatchers.Main) {
                            addSubtitle("Mic available nei")
                        }

                        break
                    }

                    audioRecord = recorder
                    recorder.startRecording()

                    val buffer = ShortArray(bufferSize)

                    while (
                        isWakeListening &&
                        wakeWordEnabled &&
                        audioBuffer.size < chunkSamples
                    ) {
                        val read = recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )

                        if (read > 0) {
                            for (i in 0 until read) {
                                audioBuffer.add(buffer[i])
                            }
                        }
                    }

                    try {
                        recorder.stop()
                    } catch (_: Exception) {
                    }

                    recorder.release()
                    audioRecord = null

                    if (!isWakeListening || audioBuffer.isEmpty()) {
                        continue
                    }

                    val wavFile = saveAsWav(audioBuffer)

                    if (wavFile == null) {
                        continue
                    }

                    // IMPORTANT:
                    // Explicitly use Bengali for Groq Whisper.
                    val transcript = try {
                        GroqApiClient.transcribeAudio(
                            applicationContext,
                            wavFile,
                            "bn"
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Wake transcription failed", e)
                        null
                    }

                    wavFile.delete()

                    if (!transcript.isNullOrBlank()) {
                        val text = transcript.trim().lowercase()

                        Log.d(TAG, "Wake listener heard: $text")

                        val detected = WAKE_WORDS.any { wake ->
                            text.contains(wake.lowercase())
                        }

                        if (detected) {
                            withContext(Dispatchers.Main) {
                                onWakeWordDetected()
                            }

                            break
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Wake listener failed", e)

                withContext(Dispatchers.Main) {
                    addSubtitle("Wake listener error")
                }

            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {
                }

                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }

                audioRecord = null
                isWakeListening = false
            }
        }
    }

    private fun stopWakeWordListener() {
        isWakeListening = false
        wakeListeningJob?.cancel()
        wakeListeningJob = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
    }

    private fun onWakeWordDetected() {
        if (isProcessingCommand || isListening) {
            return
        }

        stopWakeWordListener()

        vibrateShort()

        // Status is Banglish.
        addSubtitle("Hello Nura detect hoyeche")

        // Bengali voice response.
        // Start command recording only after "জি বস" finishes.
        wakeTtsManager.speak(WAKE_RESPONSE) {
            scope.launch {
                delay(250)

                if (!isProcessingCommand && !isListening && !isWakeListening) {
                    startWhisperRecording()
                }
            }
        }
    }

    private fun startWhisperRecording() {
        if (!AutoAgentService.isRunning()) {
            addSubtitle("❌ Enable Accessibility Service first!")
            return
        }

        isListening = true
        animateBubble(true)
        vibrateShort()
        addSubtitle("🎤 Listening... tap to send")

        recordingJob = scope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4)

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { addSubtitle("❌ Microphone unavailable"); isListening = false; animateBubble(false) }
                    return@launch
                }

                val audioBuffer = mutableListOf<Short>()
                val buffer = ShortArray(bufferSize)
                audioRecord?.startRecording()
                val maxSamples = SAMPLE_RATE * MAX_RECORDING_SECONDS

                while (isListening && audioBuffer.size < maxSamples) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) for (i in 0 until read) audioBuffer.add(buffer[i])
                }

                audioRecord?.stop(); audioRecord?.release(); audioRecord = null

                if (audioBuffer.isEmpty()) {
                    withContext(Dispatchers.Main) { isListening = false; animateBubble(false); addSubtitle("❌ No audio captured") }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    isListening = false
                    animateBubble(false)
                    vibrateShort()
                    addSubtitle("🔄 Transcribing...")
                }

                val wavFile = saveAsWav(audioBuffer)
                if (wavFile == null) { withContext(Dispatchers.Main) { addSubtitle("❌ Failed to save audio") }; return@launch }

                val transcript = GroqApiClient.transcribeAudio(applicationContext, wavFile, "bn")
                wavFile.delete()

                withContext(Dispatchers.Main) {
                    if (transcript.isNullOrBlank()) {
                        addSubtitle("❌ Couldn't understand, try again")
                    } else {
                        val command = stripWakeWord(transcript)
                        lastCommand = command
                        isProcessingCommand = true
                        subtitleHistory.clear()
                        addSubtitle("🗣️ \"$command\"")
                        startThinkingAnimation()
                        agentEngine?.startTask(command, scope)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Whisper recording failed", e)
                withContext(Dispatchers.Main) { isListening = false; animateBubble(false); addSubtitle("❌ Error: ${e.message?.take(40)}") }
            }
        }
    }

    private fun stopRecordingAndProcess() {
        isListening = false; animateBubble(false)
        vibrateShort()
        addSubtitle("🔄 Processing...")
    }

    private fun stopRecording() {
        isListening = false; recordingJob?.cancel(); recordingJob = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null; animateBubble(false)
    }

    private fun saveAsWav(audioData: List<Short>): File? {
        return try {
            val file = File(cacheDir, "whisper_input_${System.currentTimeMillis()}.wav")
            val totalPcmBytes = audioData.size * 2
            FileOutputStream(file).use { fos ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()); header.putInt(36 + totalPcmBytes)
                header.put("WAVE".toByteArray()); header.put("fmt ".toByteArray())
                header.putInt(16); header.putShort(1); header.putShort(1)
                header.putInt(SAMPLE_RATE); header.putInt(SAMPLE_RATE * 2)
                header.putShort(2); header.putShort(16)
                header.put("data".toByteArray()); header.putInt(totalPcmBytes)
                fos.write(header.array())
                val pcmBytes = ByteBuffer.allocate(totalPcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in audioData) pcmBytes.putShort(sample)
                fos.write(pcmBytes.array())
            }
            file
        } catch (e: Exception) { Log.e(TAG, "Failed to save WAV", e); null }
    }

    // =========================================================================
    // === Helpers ===
    // =========================================================================

    private fun stripWakeWord(command: String): String {
        val lower = command.trim().lowercase()
        for (wake in WAKE_WORDS) {
            if (lower.startsWith(wake)) {
                val stripped = command.substring(wake.length).trim().trimStart(',', ' ')
                if (stripped.isNotEmpty()) return stripped
            }
        }
        return command.trim()
    }

    private fun animateBubble(listening: Boolean) {
        bubbleView?.let { view ->
            ValueAnimator.ofFloat(view.scaleX, if (listening) 1.25f else 1.0f).apply {
                duration = 200; interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { view.scaleX = animatedValue as Float; view.scaleY = animatedValue as Float }
            }.start()

            if (listening) {
                view.animate().alpha(0.6f).setDuration(350).withEndAction {
                    if (isListening) view.animate().alpha(1f).setDuration(350).withEndAction { if (isListening) animateBubble(true) }.start()
                }.start()
            } else { view.animate().cancel(); view.alpha = 1f }
        }
    }

    private fun createBubbleDrawable() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        colors = intArrayOf(0xFF6C5CE7.toInt(), 0xFF8E7CF3.toInt())
        cornerRadius = dpToPx(28).toFloat()
        setStroke(dpToPx(2), 0xFFFFFFFF.toInt())
    }

    private fun createSubtitleBackground() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(0xCC0A0A1A.toInt())
        cornerRadius = dpToPx(16).toFloat()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
