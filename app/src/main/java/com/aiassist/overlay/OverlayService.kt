package com.aiassist.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.text.InputType
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Foreground service that:
 *  1. Draws a small rainbow-gradient floating bubble over any app/game.
 *  2. Tap the bubble to open a draggable panel with just two actions:
 *     - "Ask" (quick, generic help using the default instruction)
 *     - "Command" (type exactly what you want help with, then send)
 *  3. AI replies appear as a floating chat-style bubble near the icon,
 *     like a messaging app notification.
 *  4. Conversation memory persists across asks so follow-up questions
 *     have context, regardless of which command was used.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PrefsManager
    private lateinit var aiClient: AiClient

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var responseView: View? = null
    private var responseTextView: TextView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0

    private val handler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var autoRunnable: Runnable? = null

    companion object {
        const val ACTION_START = "com.aiassist.overlay.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "ai_overlay_channel"
        const val NOTIF_ID = 1001
        const val GENERIC_ASK_INSTRUCTION =
            "Ei screen e ki hocche dekhe bujhiye bolo, ami ki korte pari — sohoj o songkhipto bhabe."
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        aiClient = AiClient(prefs)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData != null) setupMediaProjection(resultCode, resultData)

            showBubble()
            if (prefs.autoModeOn) startAutoLoop()
        }
        return START_NOT_STICKY
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "AI Overlay Assistant", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Assistant is running")
            .setContentText("Bubble active — tap it for help")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    // ---------- Screen capture setup ----------

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                virtualDisplay?.release()
                imageReader?.close()
            }
        }, handler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AiOverlayCapture", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private fun captureScreenshot(): android.graphics.Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = android.graphics.Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    // ---------- Bubble UI ----------

    private fun showBubble() {
        if (bubbleView != null) return

        val bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundResource(R.drawable.bubble_gradient)
            setPadding(30, 30, 30, 30)
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
            elevation = 12f
        }

        val params = WindowManager.LayoutParams(
            150, 150,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0
            var touchX = 0f; var touchY = 0f
            var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubble, params)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                    }
                }
                return true
            }
        })

        windowManager.addView(bubble, params)
        bubbleView = bubble

        // Entrance bounce animation — feels alive, not just "appearing".
        bubble.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(420)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanel()
    }

    // ---------- Panel UI (draggable, 2 actions: Ask / Command) ----------

    private fun showPanel() {
        val bubbleParams = (bubbleView?.layoutParams as? WindowManager.LayoutParams)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.panel_bg)
            scaleX = 0.85f; scaleY = 0.85f; alpha = 0f
            elevation = 16f
        }

        // --- Draggable header ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.panel_header_bg)
            setPadding(20, 14, 16, 14)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "✨ AI Assistant"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(16, 0, 8, 0)
            setOnClickListener { hidePanel() }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // --- Body ---
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }

        // Ask button (big, gradient, primary action) — uses the saved default command
        val askBtn = TextView(this).apply {
            text = "🔍  Ask — jomano command diye help koro"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.button_primary_bg)
            setPadding(20, 24, 20, 24)
            setOnClickListener {
                pulse(this)
                val savedCommand = prefs.lastCommand.ifBlank { GENERIC_ASK_INSTRUCTION }
                runAskNow(savedCommand)
            }
        }
        body.addView(askBtn)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 14)
        }
        body.addView(spacer)

        val commandLabel = TextView(this).apply {
            text = "Command (nirdishto kotha bolo):"
            setTextColor(0xFFB3B3C6.toInt())
            textSize = 12f
            setPadding(4, 0, 0, 10)
        }
        body.addView(commandLabel)

        // Command row: EditText + circular send button
        val commandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val commandInput = EditText(this).apply {
            hint = "ex: ei level e ki korbo bolo"
            setHintTextColor(0xFF6B6B80.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.input_bg)
            setPadding(18, 14, 18, 14)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 2
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = TextView(this).apply {
            text = "➤"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.button_send_bg)
            layoutParams = LinearLayout.LayoutParams(72, 72).apply { marginStart = 10 }
            setOnClickListener {
                val text = commandInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    pulse(this)
                    runAskNow(text)
                    commandInput.text.clear()
                }
            }
        }
        commandRow.addView(commandInput)
        commandRow.addView(sendBtn)
        body.addView(commandRow)

        val spacer2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
        }
        body.addView(spacer2)

        // Auto mode + clear memory — small utility row, not extra "options"
        val utilRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val autoSwitch = Switch(this).apply {
            text = "Auto (${prefs.autoIntervalSeconds}s)"
            setTextColor(0xFFB3B3C6.toInt())
            textSize = 11f
            isChecked = prefs.autoModeOn
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnCheckedChangeListener { _, checked ->
                prefs.autoModeOn = checked
                if (checked) startAutoLoop() else stopAutoLoop()
            }
        }
        val clearBtn = TextView(this).apply {
            text = "🗑 Notun kotha"
            setTextColor(0xFF00D9A5.toInt())
            textSize = 11f
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                prefs.clearHistory()
                showResponseBubble("Purono shob kotha muche fela hoyeche. Notun kore shuru korun.")
            }
        }
        utilRow.addView(autoSwitch)
        utilRow.addView(clearBtn)
        body.addView(utilRow)

        root.addView(body)

        val params = WindowManager.LayoutParams(
            600, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: 0) + 120
            y = bubbleParams?.y ?: 300
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        // Make the header a drag handle so the whole panel can be moved.
        header.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0
            var touchX = 0f; var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = event.rawX; touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(root, params)
                    }
                }
                return true
            }
        })

        windowManager.addView(root, params)
        panelView = root
        panelParams = params

        root.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun hidePanel() {
        val panel = panelView ?: return
        panel.animate()
            .scaleX(0.85f).scaleY(0.85f).alpha(0f)
            .setDuration(180)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { windowManager.removeView(panel) }
                    panelView = null
                    panelParams = null
                }
            })
            .start()
    }

    private fun pulse(view: View) {
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
    }

    // ---------- Response chat-bubble (Messenger-style) ----------

    private fun showResponseBubble(message: String) {
        removeResponseBubble()

        val bubbleParams = (bubbleView?.layoutParams as? WindowManager.LayoutParams)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.response_bubble_bg)
            setPadding(28, 22, 28, 22)
            scaleX = 0.7f; scaleY = 0.7f; alpha = 0f
            elevation = 14f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = "🤖 AI"
            setTextColor(0xFF00D9A5.toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(0xFF8888A0.toInt())
            textSize = 14f
            setPadding(12, 0, 4, 0)
            setOnClickListener { removeResponseBubble() }
        }
        header.addView(label)
        header.addView(close)
        card.addView(header)

        val body = TextView(this).apply {
            this.text = message
            setTextColor(0xFFEDEDF5.toInt())
            textSize = 13.5f
            setPadding(0, 14, 0, 0)
        }
        card.addView(body)
        responseTextView = body

        val params = WindowManager.LayoutParams(
            560, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: 0)
            y = (bubbleParams?.y ?: 300) + 170
        }

        windowManager.addView(card, params)
        responseView = card

        card.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }

    private fun updateResponseBubbleText(message: String) {
        if (responseView != null && responseTextView != null) {
            responseTextView?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction {
                responseTextView?.text = message
                responseTextView?.animate()?.alpha(1f)?.setDuration(180)?.start()
            }?.start()
        } else {
            showResponseBubble(message)
        }
    }

    private fun removeResponseBubble() {
        val view = responseView ?: return
        runCatching { windowManager.removeView(view) }
        responseView = null
        responseTextView = null
    }

    // ---------- Ask logic ----------

    private fun runAskNow(instruction: String) {
        showResponseBubble("Bhabche... 🤔")
        bgExecutor.execute {
            var bitmap = captureScreenshot()
            var attempts = 0
            while (bitmap == null && attempts < 4) {
                Thread.sleep(250)
                bitmap = captureScreenshot()
                attempts++
            }
            val history = prefs.getHistory()
            val reply = if (bitmap != null) {
                aiClient.askAboutScreen(bitmap, instruction, history)
            } else {
                "Screenshot neya jayni. Screen-share permission ache kina check korun (Settings app e giye 'Overlay Permission diye Start koro' abar chapun)."
            }
            prefs.addHistoryTurn("user", instruction)
            prefs.addHistoryTurn("model", reply)
            handler.post { updateResponseBubbleText(reply) }
        }
    }

    private fun startAutoLoop() {
        stopAutoLoop()
        val intervalMs = prefs.autoIntervalSeconds * 1000L
        autoRunnable = object : Runnable {
            override fun run() {
                runAskNow(prefs.lastCommand.ifBlank { GENERIC_ASK_INSTRUCTION })
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(autoRunnable!!)
    }

    private fun stopAutoLoop() {
        autoRunnable?.let { handler.removeCallbacks(it) }
        autoRunnable = null
    }

    // ---------- Cleanup ----------

    override fun onDestroy() {
        super.onDestroy()
        stopAutoLoop()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        panelView?.let { runCatching { windowManager.removeView(it) } }
        responseView?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
