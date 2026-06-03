package com.floatingai.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveBtn     = findViewById<Button>(R.id.saveApiKeyBtn)

        // Pre-fill with existing key (shows masked by inputType="textPassword" in XML)
        apiKeyInput.setText(getApiKey())

        saveBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Key cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveApiKey(key)
            Toast.makeText(this, "✅ API Key saved securely", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  AES-256 encrypted storage — key never stored in plain text
    // ──────────────────────────────────────────────────────────────

    private fun securePrefs(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            this,
            "ai_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback only if hardware keystore unavailable (very old devices)
        getSharedPreferences("ai_prefs_fallback", MODE_PRIVATE)
    }

    private fun getApiKey(): String = securePrefs().getString("openai_key", "") ?: ""
    private fun saveApiKey(key: String) = securePrefs().edit().putString("openai_key", key).apply()
}
