package com.floatingai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

class FloatingAIService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var expandedView: View
    private var isExpanded = false
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var dragStartX = 0; private var dragStartY = 0
    private var initialX = 0;   private var initialY = 0
    private var isDragging = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val okHttpClient = OkHttpClient()

    // ── Lifecycle ──────────────────────────────────────────────────

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

    // ── Secure prefs ───────────────────────────────────────────────

    private fun prefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "ai_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) { getSharedPreferences("ai_prefs_fallback", MODE_PRIVATE) }

    private fun getProviderIndex() = prefs().getInt("provider_index", 0)
    private fun getApiKey(): String {
        val idx = getProviderIndex()
        return prefs().getString("api_key_$idx", "") ?: ""
    }
    private fun getModel(): String {
        val idx = getProviderIndex()
        val defaults = listOf(
            "gemini-2.0-flash",
            "llama-3.3-70b-versatile",
            "meta-llama/llama-3.3-70b-instruct:free",
            "gpt-3.5-turbo",
            "claude-haiku-4-5-20251001"
        )
        return prefs().getString("model_$idx", defaults[idx]) ?: defaults[idx]
    }

    // ── Overlay ────────────────────────────────────────────────────

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
            if (q.isNotBlank()) callAI(q) else updateResponse("Type or paste something first")
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
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 300 }
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

    private fun toggleExpanded() {
        if (isExpanded) {
            try { windowManager.removeView(expandedView) } catch (e: Exception) {}
            isExpanded = false; addBubbleToWindow()
        } else {
            try { windowManager.removeView(bubbleView) } catch (e: Exception) {}
            val p = WindowManager.LayoutParams(
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
                if (ev.action == MotionEvent.ACTION_OUTSIDE) toggleExpanded(); false
            }
            try { windowManager.addView(expandedView, p) } catch (e: Exception) {}
            isExpanded = true; loadClipboard()
        }
    }

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

    // ── Multi-provider AI call ─────────────────────────────────────

    private fun callAI(text: String) {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            updateResponse("⚠️ Open Settings (⚙) and add your API key")
            return
        }
        updateResponse("🤖 Thinking…")

        val providerIdx = getProviderIndex()
        val model = getModel()
        val prompt = text.take(500)

        val request = when (providerIdx) {
            0 -> buildGeminiRequest(apiKey, model, prompt)
            1, 2, 3 -> buildOpenAICompatRequest(apiKey, model, prompt,
                if (providerIdx == 1) "https://api.groq.com/openai/v1/chat/completions"
                else if (providerIdx == 2) "https://openrouter.ai/api/v1/chat/completions"
                else "https://api.openai.com/v1/chat/completions"
            )
            4 -> buildAnthropicRequest(apiKey, model, prompt)
            else -> buildGeminiRequest(apiKey, model, prompt)
        }

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                serviceScope.launch { updateResponse("❌ ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                serviceScope.launch {
                    val body = response.body?.string() ?: ""
                    val reply = if (response.isSuccessful) {
                        try {
                            when (providerIdx) {
                                0 -> parseGeminiResponse(body)
                                4 -> parseAnthropicResponse(body)
                                else -> parseOpenAIResponse(body)
                            }
                        } catch (e: Exception) { "Parse error: ${e.message}" }
                    } else "API error ${response.code}"
                    updateResponse(reply)
                }
            }
        })
    }

    // Gemini
    private fun buildGeminiRequest(apiKey: String, model: String, prompt: String): Request {
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "You are a concise assistant. Reply in 200 characters or less.\n\nUser: $prompt")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 160); put("temperature", 0.7)
            })
        }
        return Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
    private fun parseGeminiResponse(body: String) =
        JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text").trim()

    // OpenAI-compatible (OpenAI, Groq, OpenRouter)
    private fun buildOpenAICompatRequest(apiKey: String, model: String, prompt: String, url: String): Request {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a concise assistant. Reply in 200 characters or less.")
                })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("max_tokens", 160); put("temperature", 0.7)
        }
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
    private fun parseOpenAIResponse(body: String) =
        JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()

    // Anthropic
    private fun buildAnthropicRequest(apiKey: String, model: String, prompt: String): Request {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 160)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("system", "You are a concise assistant. Reply in 200 characters or less.")
        }
        return Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
    private fun parseAnthropicResponse(body: String) =
        JSONObject(body).getJSONArray("content").getJSONObject(0).getString("text").trim()

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
