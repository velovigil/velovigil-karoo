package com.velovigil.karoo

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal settings activity — placeholder for Phase 1.
 * Will hold Fleet endpoint URL, auth token, and upload interval in later phases.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "veloVigil"
            textSize = 24f
        }

        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 14f
        }

        val status = TextView(this).apply {
            text = "Extension active. Data fields available on ride screens."
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }

        layout.addView(title)
        layout.addView(version)
        layout.addView(status)
        setContentView(layout)
    }
}
