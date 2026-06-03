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
import org.json.JSONArray
import java.io.IOException
import kotlin.math.abs

class FloatingAIService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var expandedView: View
    private var isExpanded = false
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var dragStartX = 0
    private var dragStartY = 0
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val okHttpClient = OkHttpClient()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlayViews()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ── Encrypted key store ────────────────────────────────────────

    private fun getApiKey(): String = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this, "ai_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).getString("gemini_key", "") ?: ""
    } catch (e: Exception) { "" }

    // ── Overlay setup ──────────────────────────────────────────────

    private fun setupOverlayViews() {
        bubbleView   = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        expandedView = LayoutInflater.from(this).inflate(R.layout.floating_expanded, null)

        setupBubbleDragAndTap(bubbleView) { toggleExpanded() }

        expandedView.findViewById<ImageButton>(R.id.btnClose).setOnClickListener    { toggleExpanded() }
        expandedView.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        expandedView.findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30; y = 300
        }
        try { windowManager.addView(bubbleView, bubbleParams) } catch (e: Exception) {}
    }

    private fun setupBubbleDragAndTap(view: View, onTap: () -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX.toInt(); dragStartY = event.rawY.toInt()
                    initialX = bubbleParams?.x ?: 0; initialY = bubbleParams?.y ?: 0
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - dragStartX
                    val dy = event.rawY.toInt() - dragStartY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) isDragging = true
                    if (isDragging) {
                        bubbleParams?.x = initialX + dx; bubbleParams?.y = initialY + dy
                        try { windowManager.updateViewLayout(bubbleView, bubbleParams) } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) onTap(); true }
                else -> false
            }
        }
    }

    // ── Toggle expanded card ───────────────────────────────────────

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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bubbleParams?.x ?: 30; y = bubbleParams?.y ?: 300
            }
            expandedView.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) toggleExpanded()
                false
            }
            try { windowManager.addView(expandedView, expandedParams) } catch (e: Exception) {}
            isExpanded = true
            loadClipboard()
        }
    }

    // ── Clipboard ──────────────────────────────────────────────────

    private fun loadClipboard() {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isNotBlank()) {
            val input = expandedView.findViewById<EditText>(R.id.queryInput)
            if (input.text.isBlank()) { input.setText(text); callAI(text) }
        }
    }

    // ── Gemini API call ────────────────────────────────────────────

    private fun callAI(text: String) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            updateResponse("⚠️ Open Settings and add your Gemini API key")
            return
        }
        updateResponse("🤖 Thinking…")

        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "You are a concise floating assistant. Reply in 200 characters or less.\n\nUser: ${text.take(500)}")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 160)
                put("temperature", 0.7)
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
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
                                .getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text").trim()
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { windowManager.removeView(bubbleView) }   catch (e: Exception) {}
        try { windowManager.removeView(expandedView) } catch (e: Exception) {}
    }
}
