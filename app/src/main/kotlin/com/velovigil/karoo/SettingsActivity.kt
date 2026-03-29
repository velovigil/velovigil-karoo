package com.velovigil.karoo

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

// "Courage is not the absence of despair; it is, rather, the capacity to move ahead in spite of despair."
// — Gord Downie, paraphrased from the Tragically Hip ethos

/**
 * Settings activity — configure fleet endpoint, API key, and rider registration.
 * Uses SharedPreferences for persistence. Programmatic layout (no XML).
 */
class SettingsActivity : Activity() {

    companion object {
        private const val TAG = "veloVigil.Settings"
        const val PREFS_NAME = "velovigil_prefs"
        const val KEY_FLEET_ENDPOINT = "fleet_endpoint"
        const val KEY_RIDER_KEY = "rider_key"
        const val KEY_RIDER_ID = "rider_id"
        const val DEFAULT_ENDPOINT = "https://velovigil-fleet.robert-chuvala.workers.dev/api/v1/telemetry"
    }

    private lateinit var endpointInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var riderIdDisplay: TextView
    private lateinit var statusText: TextView
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentEndpoint = prefs.getString(KEY_FLEET_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        val currentKey = prefs.getString(KEY_RIDER_KEY, "") ?: ""
        val currentRiderId = prefs.getString(KEY_RIDER_ID, "") ?: ""

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 48)
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B3B2F"))
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "veloVigil"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val version = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            textSize = 14f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
        }

        header.addView(title)
        header.addView(version)
        layout.addView(header)

        // Spacer
        layout.addView(spacer(24))

        // Fleet endpoint
        layout.addView(label("Fleet Endpoint URL"))
        endpointInput = EditText(this).apply {
            setText(currentEndpoint)
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
        }
        layout.addView(endpointInput)

        layout.addView(spacer(16))

        // API key (masked for security)
        layout.addView(label("API Key"))
        apiKeyInput = EditText(this).apply {
            setText(currentKey)
            hint = "Enter your rider API key"
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(apiKeyInput)

        layout.addView(spacer(16))

        // Rider ID (read-only)
        layout.addView(label("Rider ID (assigned by registration)"))
        riderIdDisplay = TextView(this).apply {
            text = if (currentRiderId.isNotEmpty()) currentRiderId else "(not registered)"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 16, 16, 16)
        }
        layout.addView(riderIdDisplay)

        layout.addView(spacer(24))

        // Status text
        statusText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#1B3B2F"))
            setPadding(16, 8, 16, 8)
        }
        layout.addView(statusText)

        layout.addView(spacer(16))

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val registerButton = Button(this).apply {
            text = "Register"
            setOnClickListener { doRegister() }
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener { doSave() }
        }

        buttonRow.addView(registerButton)
        buttonRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(32, 1)
        })
        buttonRow.addView(saveButton)
        layout.addView(buttonRow)

        layout.addView(spacer(24))

        // Current config summary
        layout.addView(label("Extension active. Data fields available on ride screens."))

        root.addView(layout)
        setContentView(root)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // "No dress rehearsal, this is our life." — Gord Downie
    private fun doSave() {
        val endpoint = endpointInput.text.toString().trim()
        if (!endpoint.startsWith("https://")) {
            setStatus("Error: Endpoint must use HTTPS")
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_FLEET_ENDPOINT, endpoint)
            .putString(KEY_RIDER_KEY, apiKeyInput.text.toString().trim())
            .apply()

        Log.i(TAG, "Settings saved")
        setStatus("Settings saved.")
    }

    private fun doRegister() {
        val endpoint = endpointInput.text.toString().trim()
        // Derive base URL: strip /telemetry or /api/v1/telemetry to get the register endpoint
        val baseUrl = endpoint
            .removeSuffix("/telemetry")
            .removeSuffix("/")
        val registerUrl = "$baseUrl/register"

        setStatus("Registering with $registerUrl ...")
        Log.i(TAG, "Registering at $registerUrl")

        scope.launch {
            try {
                val conn = URL(registerUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val body = """{"device_type":"karoo2","app_version":"${BuildConfig.VERSION_NAME}"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    // Parse rider_id and api_key from JSON response
                    // Simple parsing — no JSON lib dependency needed
                    val riderId = extractJsonValue(response, "rider_id")
                    val apiKey = extractJsonValue(response, "api_key")
                        ?: extractJsonValue(response, "key")

                    if (riderId != null && apiKey != null) {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString(KEY_RIDER_ID, riderId)
                            .putString(KEY_RIDER_KEY, apiKey)
                            .apply()

                        runOnUiThread {
                            riderIdDisplay.text = riderId
                            apiKeyInput.setText(apiKey)
                            setStatus("Registered. Rider ID: $riderId")
                        }
                        Log.i(TAG, "Registration successful: rider_id=$riderId")
                    } else {
                        runOnUiThread {
                            setStatus("Registration response missing expected fields")
                        }
                        Log.w(TAG, "Unexpected registration response format (redacted)")
                    }
                } else {
                    val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    conn.disconnect()
                    runOnUiThread {
                        setStatus("Registration failed: HTTP $code $errorBody")
                    }
                    Log.w(TAG, "Registration failed: $code $errorBody")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Registration error: ${e.message}")
                }
                Log.e(TAG, "Registration error", e)
            }
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    /**
     * Extract a string value from a JSON object by key.
     * Handles: "key":"value" and "key": "value"
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = """"$key"\s*:\s*"([^"]+)""""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.get(1)
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 4)
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (heightDp * resources.displayMetrics.density).toInt()
            )
        }
    }
}
