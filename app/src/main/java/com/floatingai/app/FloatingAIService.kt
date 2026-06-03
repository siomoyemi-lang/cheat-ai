package com.floatingai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

class FloatingAIService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var expandedView: View
    private var isExpanded = false
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Drag state
    private var dragStartX = 0
    private var dragStartY = 0
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val okHttpClient = OkHttpClient()

    // ──────────────────────────────────────────────────────────────
    //  Accessibility Service lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Minimal event subscription — we don't spy on other apps
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayViews()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not reading other apps' content — intentionally empty
    }

    override fun onInterrupt() {}

    // ──────────────────────────────────────────────────────────────
    //  Encrypted key store
    // ──────────────────────────────────────────────────────────────

    private fun getApiKey(): String = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this, "ai_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).getString("openai_key", "") ?: ""
    } catch (e: Exception) { "" }

    // ──────────────────────────────────────────────────────────────
    //  Overlay setup
    //
    //  WHY TYPE_ACCESSIBILITY_OVERLAY:
    //  • Does NOT set FLAG_WINDOW_IS_OBSCURED on underlying windows
    //    — the primary signal banking/finance apps use to detect overlays
    //  • Works over FLAG_SECURE windows (banking apps, password managers)
    //  • Doesn't require SYSTEM_ALERT_WINDOW permission
    //  • Treated as trusted accessibility UI by the system
    // ──────────────────────────────────────────────────────────────

    private fun setupOverlayViews() {
        bubbleView   = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        expandedView = LayoutInflater.from(this).inflate(R.layout.floating_expanded, null)

        // Drag + tap on bubble (threshold distinguishes them)
        setupBubbleDragAndTap(bubbleView) { toggleExpanded() }

        expandedView.findViewById<ImageButton>(R.id.btnClose).setOnClickListener    { toggleExpanded() }
        expandedView.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        expandedView.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener  {
            val q = expandedView.findViewById<EditText>(R.id.queryInput).text.toString().trim()
            if (q.isNotBlank()) callAI(q) else loadClipboard()
        }
        expandedView.findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val q = expandedView.findViewById<EditText>(R.id.queryInput).text.toString().trim()
            if (q.isNotBlank()) callAI(q)
            else updateResponse("Type or paste something first")
        }

        addBubbleToWindow()
    }

    private fun addBubbleToWindow() {
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_FOCUSABLE      → never steal keyboard focus from the host app
            // FLAG_NOT_TOUCH_MODAL    → touches outside the bubble pass through to host
            // FLAG_LAYOUT_IN_SCREEN   → clamp within screen bounds
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 300
        }
        try { windowManager.addView(bubbleView, bubbleParams) } catch (e: Exception) {}
    }

    private fun setupBubbleDragAndTap(view: View, onTap: () -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX.toInt()
                    dragStartY = event.rawY.toInt()
                    initialX   = bubbleParams?.x ?: 0
                    initialY   = bubbleParams?.y ?: 0
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - dragStartX
                    val dy = event.rawY.toInt() - dragStartY
                    // Only start dragging after 10 px movement
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) isDragging = true
                    if (isDragging) {
                        bubbleParams?.x = initialX + dx
                        bubbleParams?.y = initialY + dy
                        try { windowManager.updateViewLayout(bubbleView, bubbleParams) } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Treat as tap only if the finger barely moved
                    if (!isDragging) onTap()
                    true
                }
                else -> false
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Toggle expanded card
    // ──────────────────────────────────────────────────────────────

    private fun toggleExpanded() {
        if (isExpanded) {
            try { windowManager.removeView(expandedView) } catch (e: Exception) {}
            isExpanded = false
            addBubbleToWindow()
        } else {
            try { windowManager.removeView(bubbleView) } catch (e: Exception) {}
            val expandedParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Card receives touches + keyboard; outside taps collapse it
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bubbleParams?.x ?: 30
                y = bubbleParams?.y ?: 300
            }
            expandedView.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) toggleExpanded()
                false
            }
            try { windowManager.addView(expandedView, expandedParams) } catch (e: Exception) {}
            isExpanded = true
            loadClipboard()   // pre-fill input from clipboard on open
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Clipboard — only read when user explicitly opens the card
    //  (avoids silent background exfiltration)
    // ──────────────────────────────────────────────────────────────

    private fun loadClipboard() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isNotBlank()) {
            val input = expandedView.findViewById<EditText>(R.id.queryInput)
            if (input.text.isBlank()) {
                input.setText(text)
                callAI(text)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  OpenAI call
    // ──────────────────────────────────────────────────────────────

    private fun callAI(text: String) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            updateResponse("⚠️ Open Settings and add your OpenAI API key")
            return
        }
        updateResponse("🤖 Thinking…")

        val payload = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a concise floating assistant. Reply in ≤200 characters.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text.take(500))
                })
            })
            put("max_tokens", 160)
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                serviceScope.launch { updateResponse("❌ ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                serviceScope.launch {
                    val reply = if (response.isSuccessful) {
                        try {
                            JSONObject(response.body?.string() ?: "")
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content").trim()
                        } catch (e: Exception) { "Could not parse response" }
                    } else "API error ${response.code}"
                    updateResponse(reply)
                }
            }
        })
    }

    private fun updateResponse(text: String) {
        try { expandedView.findViewById<TextView>(R.id.aiResponseText).text = text } catch (e: Exception) {}
    }

    // ──────────────────────────────────────────────────────────────
    //  Cleanup
    // ──────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { windowManager.removeView(bubbleView) }   catch (e: Exception) {}
        try { windowManager.removeView(expandedView) } catch (e: Exception) {}
    }
}
