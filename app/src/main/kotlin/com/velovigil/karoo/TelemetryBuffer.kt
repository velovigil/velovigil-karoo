package com.velovigil.karoo

import android.util.Log
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Buffers telemetry data and POSTs to the Fleet backend.
 * Offline-first: queues locally, flushes when network available.
 */
class TelemetryBuffer(
    private val endpoint: String = "https://velovigil-fleet.robert-chuvala.workers.dev/api/v1/telemetry",
    private val deviceKey: String = "vv_rider_jwo9xh4oaw8lnuwihqgu5ttsyyj24834",
    private val riderId: String = "robert_chuvala",
    private val intervalMs: Long = 5000,
) {
    companion object {
        private const val TAG = "veloVigil.Telemetry"
        private const val MAX_BUFFER_SIZE = 1000
    }

    private val buffer = ConcurrentLinkedQueue<String>()
    private var uploadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Current sensor values — written by extension, read by upload loop
    @Volatile var rideState: String = "IDLE"
    @Volatile var heartRate: Int = 0
    @Volatile var speed: Double = 0.0
    @Volatile var power: Int = 0
    @Volatile var cadence: Int = 0
    @Volatile var latitude: Double = 0.0
    @Volatile var longitude: Double = 0.0
    @Volatile var altitude: Double = 0.0
    @Volatile var grade: Double = 0.0
    @Volatile var distance: Double = 0.0
    @Volatile var elapsedTime: Long = 0

    // HRV metrics (from Polar H10 RR intervals)
    @Volatile var rmssd: Double = 0.0
    @Volatile var sdnn: Double = 0.0
    @Volatile var pnn50: Double = 0.0
    @Volatile var meanRR: Double = 0.0

    // G-force metrics (from Polar H10 accelerometer)
    @Volatile var currentG: Double = 1.0
    @Volatile var peakG: Double = 0.0
    @Volatile var lateralG: Double = 0.0
    @Volatile var isAirborne: Boolean = false
    @Volatile var hangTimeMs: Long = 0

    fun start() {
        uploadJob = scope.launch {
            Log.i(TAG, "Telemetry upload loop started (${intervalMs}ms interval)")
            while (isActive) {
                if (rideState == "RECORDING") {
                    val payload = buildPayload()
                    buffer.add(payload)

                    // Cap buffer size
                    while (buffer.size > MAX_BUFFER_SIZE) {
                        buffer.poll()
                    }

                    // Try to flush
                    flush()
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        uploadJob?.cancel()
        // Final flush attempt
        scope.launch { flush() }
        Log.i(TAG, "Telemetry upload loop stopped (${buffer.size} buffered)")
    }

    // "Half of life is just showing up." — Henry Rollins
    private fun buildPayload(): String {
        val ts = dateFormat.format(Date())
        // Fallback: if HR is 0 but we have valid RR intervals, derive HR from meanRR
        val effectiveHR = if (heartRate > 0) heartRate
            else if (meanRR > 0) (60000.0 / meanRR).toInt()
            else 0
        return """{"device_id":"karoo2","rider_id":"$riderId","timestamp_utc":"$ts","ride_state":"$rideState","elapsed_seconds":$elapsedTime,"gps":{"lat":$latitude,"lon":$longitude},"speed_ms":$speed,"cadence_rpm":$cadence,"power_watts":$power,"altitude_m":$altitude,"grade_pct":$grade,"distance_m":$distance,"hr_bpm":$effectiveHR,"hrv":{"rmssd":${"%.1f".format(rmssd)},"sdnn":${"%.1f".format(sdnn)},"pnn50":${"%.1f".format(pnn50)},"mean_rr_ms":${"%.1f".format(meanRR)}},"gforce":{"current":${"%.2f".format(currentG)},"peak":${"%.2f".format(peakG)},"lateral":${"%.2f".format(lateralG)},"airborne":$isAirborne,"hang_time_ms":$hangTimeMs}}"""
    }

    private suspend fun flush() {
        while (buffer.isNotEmpty()) {
            val payload = buffer.peek() ?: break
            try {
                val success = post(payload)
                if (success) {
                    buffer.poll()
                } else {
                    Log.w(TAG, "POST failed, keeping ${buffer.size} in buffer")
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Network error, keeping ${buffer.size} in buffer: ${e.message}")
                break
            }
        }
    }

    private fun post(payload: String): Boolean {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Key", deviceKey)
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "POST OK ($code)")
                true
            } else {
                Log.w(TAG, "POST failed: $code")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST error: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }
}
