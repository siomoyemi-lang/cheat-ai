package com.floatingai.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            if (isAccessibilityEnabled()) {
                Toast.makeText(this, "Floating AI is already running!", Toast.LENGTH_SHORT).show()
            } else {
                // Open system Accessibility settings so the user can enable us
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this,
                    "Find \"Floating AI\" in the list and toggle it ON",
                    Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh status badge every time the user returns from Accessibility settings
        val statusText = findViewById<TextView>(R.id.statusText)
        if (isAccessibilityEnabled()) {
            statusText.text = "✅ Floating AI is active"
            statusText.setTextColor(0xFF4CAF50.toInt())
        } else {
            statusText.text = "❌ Not enabled — tap the button below"
            statusText.setTextColor(0xFFFF5722.toInt())
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }
}
