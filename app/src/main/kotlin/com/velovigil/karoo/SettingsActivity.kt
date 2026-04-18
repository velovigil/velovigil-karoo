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
        const val KEY_BOARD_TOKEN = "board_token"   // Polaris static token, 40 hex chars
        const val KEY_BOARD_NAME = "board_name"     // BLE advertisement name, e.g. "ow452500"
        const val DEFAULT_ENDPOINT = "https://velovigil-fleet.robert-chuvala.workers.dev/api/v1/telemetry"
    }

    private lateinit var endpointInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var inviteCodeInput: EditText
    private lateinit var boardTokenInput: EditText
    private lateinit var boardNameInput: EditText
    private lateinit var riderIdDisplay: TextView
    private lateinit var statusText: TextView
    private lateinit var connectionStatus: TextView
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

        // Connection status indicator
        connectionStatus = TextView(this).apply {
            text = "Checking connection..."
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#E8E8E8"))
        }
        layout.addView(connectionStatus)

        layout.addView(spacer(16))

        // Invite code (for registration)
        layout.addView(label("Invite Code"))
        inviteCodeInput = EditText(this).apply {
            hint = "vv_invite_..."
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
        }
        layout.addView(inviteCodeInput)

        layout.addView(spacer(8))

        // Register button right after invite code
        val registerButton = Button(this).apply {
            text = "Register with Invite Code"
            setOnClickListener { doRegister() }
        }
        layout.addView(registerButton)

        layout.addView(spacer(16))

        // Rider ID (read-only)
        layout.addView(label("Rider ID"))
        riderIdDisplay = TextView(this).apply {
            text = if (currentRiderId.isNotEmpty()) currentRiderId else "(not registered — enter invite code above)"
            textSize = 14f
            setTextColor(if (currentRiderId.isNotEmpty()) Color.parseColor("#1B3B2F") else Color.parseColor("#CC0000"))
            setPadding(16, 16, 16, 16)
        }
        layout.addView(riderIdDisplay)

        layout.addView(spacer(8))

        // API key (masked for security)
        layout.addView(label("API Key"))
        apiKeyInput = EditText(this).apply {
            setText(currentKey)
            hint = "(auto-filled on registration)"
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(apiKeyInput)

        layout.addView(spacer(24))

        // Onewheel Board — Polaris token + name
        val currentBoardToken = prefs.getString(KEY_BOARD_TOKEN, "") ?: ""
        val currentBoardName = prefs.getString(KEY_BOARD_NAME, "") ?: ""
        layout.addView(label("Onewheel Board Name (optional, e.g. ow452500)"))
        boardNameInput = EditText(this).apply {
            setText(currentBoardName)
            hint = "leave blank to match any 'ow*' board"
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
        }
        layout.addView(boardNameInput)

        layout.addView(spacer(8))

        layout.addView(label("Polaris Token (40 hex chars — see docs/POLARIS_TOKEN_EXTRACTION.md)"))
        boardTokenInput = EditText(this).apply {
            setText(currentBoardToken)
            hint = "(leave blank for legacy MD5 auth)"
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(boardTokenInput)

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

        val saveButton = Button(this).apply {
            text = "Save Settings"
            setOnClickListener { doSave() }
        }

        val testButton = Button(this).apply {
            text = "Test Connection"
            setOnClickListener { doTestConnection() }
        }

        buttonRow.addView(saveButton)
        buttonRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(32, 1)
        })
        buttonRow.addView(testButton)
        layout.addView(buttonRow)

        layout.addView(spacer(24))

        // Current config summary
        layout.addView(label("Extension active. Data fields available on ride screens."))

        // Auto-import config from setup script if present
        importConfigIfPresent()

        // Check connection status on load
        checkConnectionStatus()

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
            .putString(KEY_BOARD_TOKEN, boardTokenInput.text.toString().trim())
            .putString(KEY_BOARD_NAME, boardNameInput.text.toString().trim())
            .apply()

        Log.i(TAG, "Settings saved")
        setStatus("Settings saved.")
    }

    private fun doRegister() {
        val inviteCode = inviteCodeInput.text.toString().trim()
        if (inviteCode.isEmpty()) {
            setStatus("Enter an invite code to register.")
            return
        }
        if (!inviteCode.startsWith("vv_invite_")) {
            setStatus("Invite code must start with vv_invite_")
            return
        }

        val endpoint = endpointInput.text.toString().trim()
        val baseUrl = endpoint
            .removeSuffix("/telemetry")
            .removeSuffix("/")
        val registerUrl = "$baseUrl/register"

        setStatus("Registering...")
        Log.i(TAG, "Registering at $registerUrl with invite code")

        scope.launch {
            try {
                val conn = URL(registerUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val body = """{"invite_code":"$inviteCode","device_type":"karoo2","app_version":"${BuildConfig.VERSION_NAME}"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

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
                            riderIdDisplay.setTextColor(Color.parseColor("#1B3B2F"))
                            apiKeyInput.setText(apiKey)
                            inviteCodeInput.setText("")
                            setStatus("Registered! Rider ID: $riderId\nAPI key saved. You're ready to ride.")
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
                        setStatus("Registration failed: HTTP $code\n$errorBody")
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

    private fun doTestConnection() {
        val endpoint = endpointInput.text.toString().trim()
        val key = apiKeyInput.text.toString().trim()

        if (key.isEmpty()) {
            setStatus("No API key — register first.")
            return
        }

        setStatus("Testing connection...")

        scope.launch {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Device-Key", key)
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                // Send a minimal test payload
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val riderId = prefs.getString(KEY_RIDER_ID, "test") ?: "test"
                val testPayload = """{"device_id":"karoo2","rider_id":"$riderId","timestamp_utc":"${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())}","ride_state":"IDLE","elapsed_seconds":0,"gps":{"lat":0,"lon":0},"speed_ms":0,"cadence_rpm":0,"power_watts":0,"altitude_m":0,"grade_pct":0,"distance_m":0,"hr_bpm":0,"hrv":{"rmssd":0,"sdnn":0,"pnn50":0,"mean_rr_ms":0},"gforce":{"current":1.0,"peak":0,"lateral":0,"airborne":false,"hang_time_ms":0}}"""
                conn.outputStream.use { it.write(testPayload.toByteArray()) }

                val code = conn.responseCode
                runOnUiThread {
                    when (code) {
                        in 200..299 -> {
                            setStatus("Connection OK! Backend received test point.\nYou're ready to ride.")
                            updateConnectionIndicator(true, "Connected — backend reachable")
                        }
                        401 -> setStatus("AUTH FAILED (401)\nYour API key is invalid or expired.\nTry re-registering with a new invite code.")
                        403 -> setStatus("FORBIDDEN (403)\nBackend rejected the request.\nCheck your API key.")
                        429 -> setStatus("RATE LIMITED (429)\nToo many requests. Wait a minute.")
                        else -> setStatus("Backend returned HTTP $code\nCheck endpoint URL.")
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Connection failed: ${e.message}\nCheck network and endpoint URL.")
                    updateConnectionIndicator(false, "Cannot reach backend")
                }
            }
        }
    }

    private fun checkConnectionStatus() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_RIDER_KEY, "") ?: ""
        val riderId = prefs.getString(KEY_RIDER_ID, "") ?: ""

        if (key.isEmpty() || riderId.isEmpty()) {
            updateConnectionIndicator(false, "Not registered — enter invite code to get started")
            return
        }

        // Check if extension is running and Polar is connected
        val ext = VeloVigilExtension.instance
        if (ext == null) {
            updateConnectionIndicator(false, "Extension not running — restart Karoo")
            return
        }

        val polarConnected = ext.polar?.isConnected == true
        val lastFlush = ext.telemetry.lastFlushStatus

        val parts = mutableListOf<String>()
        parts.add("Rider: $riderId")
        parts.add("Polar H10: ${if (polarConnected) "Connected" else "Not connected"}")
        parts.add("Last upload: $lastFlush")

        updateConnectionIndicator(polarConnected, parts.joinToString(" | "))
    }

    private fun updateConnectionIndicator(ok: Boolean, message: String) {
        runOnUiThread {
            connectionStatus.text = message
            connectionStatus.setTextColor(if (ok) Color.parseColor("#1B3B2F") else Color.parseColor("#CC0000"))
            connectionStatus.setBackgroundColor(if (ok) Color.parseColor("#D4EDDA") else Color.parseColor("#F8D7DA"))
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

    private fun importConfigIfPresent() {
        try {
            val configFile = java.io.File("/sdcard/velovigil/config.json")
            if (!configFile.exists()) return

            val json = configFile.readText()
            val riderId = extractJsonValue(json, "rider_id")
            val apiKey = extractJsonValue(json, "api_key")
            val endpoint = extractJsonValue(json, "endpoint")
            val boardName = extractJsonValue(json, "board_name")
            val boardToken = extractJsonValue(json, "polaris_token")

            if (riderId != null && apiKey != null) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putString(KEY_RIDER_ID, riderId)
                editor.putString(KEY_RIDER_KEY, apiKey)
                if (endpoint != null) editor.putString(KEY_FLEET_ENDPOINT, endpoint)
                if (boardName != null) editor.putString(KEY_BOARD_NAME, boardName)
                if (boardToken != null) editor.putString(KEY_BOARD_TOKEN, boardToken)
                editor.apply()

                riderIdDisplay.text = riderId
                riderIdDisplay.setTextColor(Color.parseColor("#1B3B2F"))
                apiKeyInput.setText(apiKey)
                if (endpoint != null) endpointInput.setText(endpoint)
                if (boardName != null) boardNameInput.setText(boardName)
                if (boardToken != null) boardTokenInput.setText(boardToken)
                setStatus("Imported config from setup script. You're ready to ride.")
                Log.i(TAG, "Auto-imported config: rider_id=$riderId boardName=$boardName token=${if (boardToken != null) "set" else "none"}")

                // Delete the config file after import (one-time use)
                configFile.delete()
                java.io.File("/sdcard/velovigil").delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Config import failed: ${e.message}")
        }
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
