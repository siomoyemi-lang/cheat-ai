package com.floatingai.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsActivity : AppCompatActivity() {

    private val providers = listOf(
        "Google Gemini (Free)",
        "Groq (Free)",
        "OpenRouter (Free models)",
        "OpenAI (Paid)",
        "Anthropic Claude (Paid)"
    )

    private val defaultModels = listOf(
        "gemini-2.0-flash",
        "llama-3.3-70b-versatile",
        "meta-llama/llama-3.3-70b-instruct:free",
        "gpt-3.5-turbo",
        "claude-haiku-4-5-20251001"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val spinner      = findViewById<Spinner>(R.id.providerSpinner)
        val apiKeyInput  = findViewById<EditText>(R.id.apiKeyInput)
        val modelInput   = findViewById<EditText>(R.id.modelInput)
        val hintText     = findViewById<TextView>(R.id.apiKeyHint)
        val saveBtn      = findViewById<Button>(R.id.saveApiKeyBtn)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Restore saved selection
        val prefs = securePrefs()
        val savedProvider = prefs.getInt("provider_index", 0)
        spinner.setSelection(savedProvider)
        apiKeyInput.setText(prefs.getString("api_key_$savedProvider", ""))
        modelInput.setText(prefs.getString("model_$savedProvider", defaultModels[savedProvider]))
        updateHint(hintText, savedProvider)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                apiKeyInput.setText(prefs.getString("api_key_$pos", ""))
                modelInput.setText(prefs.getString("model_$pos", defaultModels[pos]))
                updateHint(hintText, pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        saveBtn.setOnClickListener {
            val key   = apiKeyInput.text.toString().trim()
            val model = modelInput.text.toString().trim()
            val pos   = spinner.selectedItemPosition
            if (key.isEmpty()) {
                Toast.makeText(this, "API key cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            securePrefs().edit()
                .putString("api_key_$pos", key)
                .putString("model_$pos", model.ifEmpty { defaultModels[pos] })
                .putInt("provider_index", pos)
                .apply()
            Toast.makeText(this, "✅ Saved for ${providers[pos]}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateHint(tv: TextView, pos: Int) {
        tv.text = when (pos) {
            0 -> "Get free key at aistudio.google.com → Get API Key"
            1 -> "Get free key at console.groq.com → API Keys"
            2 -> "Get free key at openrouter.ai → Keys (pick free models)"
            3 -> "Get key at platform.openai.com → API Keys"
            4 -> "Get key at console.anthropic.com → API Keys"
            else -> ""
        }
    }

    private fun securePrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "ai_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        getSharedPreferences("ai_prefs_fallback", MODE_PRIVATE)
    }
}
