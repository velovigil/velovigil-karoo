package com.velovigil.karoo

import android.util.Log
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Computes g-force metrics from Polar H10 accelerometer data.
 * H10 outputs 3-axis acceleration in mG (milli-g) at up to 200Hz.
 *
 * Uses a sliding window RMS filter to reject strap bounce artifacts.
 * Raw single-sample peaks are unreliable — a kitchen jump reads 7.64g raw
 * but actual body g-force is ~2-3g. Strap vibration amplifies 2-3x.
 *
 * Crash detection requires SUSTAINED g-force, not instantaneous spikes.
 * Threshold: filtered g > 4g sustained for > 200ms = potential crash.
 */
class GForceProcessor {

    companion object {
        private const val TAG = "veloVigil.GForce"
        private const val MG_TO_G = 1.0 / 1000.0

        // Filter: 10 samples at 200Hz = 50ms sliding window
        private const val FILTER_WINDOW = 10

        // Crash detection: sustained high-g over this duration
        private const val CRASH_G_THRESHOLD = 4.0     // filtered g above this = crash candidate
        private const val CRASH_DURATION_MS = 200L     // must sustain for this long
        private const val CRASH_COOLDOWN_MS = 30_000L  // don't re-trigger for 30s
    }

    // Filtered values (sliding window RMS — what the body actually feels)
    @Volatile var currentG: Double = 1.0
    @Volatile var lateralG: Double = 0.0
    @Volatile var verticalG: Double = 1.0
    @Volatile var longitudinalG: Double = 0.0

    // Peak filtered values for current ride
    @Volatile var peakG: Double = 0.0
    @Volatile var peakLateralG: Double = 0.0

    // Raw instantaneous (for diagnostics only, not used for detection)
    @Volatile var rawG: Double = 1.0

    // Jump/airborne detection (uses filtered signal)
    @Volatile var isAirborne: Boolean = false
    @Volatile var hangTimeMs: Long = 0
    private var airborneStartTime: Long = 0

    // Crash detection state
    @Volatile var crashDetected: Boolean = false
    private var highGStartTime: Long = 0
    private var lastCrashTime: Long = 0

    // Sliding window buffers for RMS filtering
    private val gBuffer = ArrayDeque<Double>(FILTER_WINDOW)
    private val latBuffer = ArrayDeque<Double>(FILTER_WINDOW)
    private val vertBuffer = ArrayDeque<Double>(FILTER_WINDOW)
    private val lonBuffer = ArrayDeque<Double>(FILTER_WINDOW)

    // Vibration RMS (deviation from 1g — road roughness metric)
    private val vibrationBuffer = ArrayDeque<Double>(100)
    @Volatile var vibrationRMS: Double = 0.0

    /**
     * Process a 3-axis accelerometer sample from the Polar H10.
     * @param x lateral acceleration in mG
     * @param y longitudinal acceleration in mG
     * @param z vertical acceleration in mG (1000 = 1g at rest)
     */
    fun addSample(x: Int, y: Int, z: Int) {
        val xG = x * MG_TO_G
        val yG = y * MG_TO_G
        val zG = z * MG_TO_G
        val mag = sqrt(xG * xG + yG * yG + zG * zG)

        rawG = mag

        // Add to sliding window buffers
        gBuffer.addLast(mag)
        latBuffer.addLast(xG)
        vertBuffer.addLast(zG)
        lonBuffer.addLast(yG)
        if (gBuffer.size > FILTER_WINDOW) gBuffer.removeFirst()
        if (latBuffer.size > FILTER_WINDOW) latBuffer.removeFirst()
        if (vertBuffer.size > FILTER_WINDOW) vertBuffer.removeFirst()
        if (lonBuffer.size > FILTER_WINDOW) lonBuffer.removeFirst()

        // RMS of sliding window — filters out strap bounce
        currentG = rms(gBuffer)
        lateralG = signedRms(latBuffer)
        verticalG = signedRms(vertBuffer)
        longitudinalG = signedRms(lonBuffer)

        // Update peaks (filtered values only)
        if (currentG > peakG) peakG = currentG
        if (abs(lateralG) > peakLateralG) peakLateralG = abs(lateralG)

        // Jump detection — airborne when filtered g < 0.3
        val now = System.currentTimeMillis()
        if (currentG < 0.3 && !isAirborne) {
            isAirborne = true
            airborneStartTime = now
        } else if (currentG >= 0.3 && isAirborne) {
            isAirborne = false
            hangTimeMs = now - airborneStartTime
            if (hangTimeMs > 100) {
                Log.i(TAG, "JUMP! Hang: ${hangTimeMs}ms, Impact: ${"%.1f".format(currentG)}g (raw peak: ${"%.1f".format(rawG)}g)")
            }
        }

        // Crash detection — sustained high g-force
        if (currentG > CRASH_G_THRESHOLD) {
            if (highGStartTime == 0L) highGStartTime = now
            val duration = now - highGStartTime
            if (duration >= CRASH_DURATION_MS && (now - lastCrashTime) > CRASH_COOLDOWN_MS) {
                crashDetected = true
                lastCrashTime = now
                Log.w(TAG, "CRASH DETECTED! Filtered: ${"%.1f".format(currentG)}g sustained for ${duration}ms")
            }
        } else {
            highGStartTime = 0
        }

        // Vibration RMS — road roughness
        val deviation = currentG - 1.0
        vibrationBuffer.addLast(deviation * deviation)
        if (vibrationBuffer.size > 100) vibrationBuffer.removeFirst()
        vibrationRMS = sqrt(vibrationBuffer.average())
    }

    /** RMS of a buffer — magnitude of oscillation */
    private fun rms(buf: ArrayDeque<Double>): Double {
        if (buf.isEmpty()) return 1.0
        return sqrt(buf.sumOf { it * it } / buf.size)
    }

    /** Signed RMS — preserves direction of predominant force */
    private fun signedRms(buf: ArrayDeque<Double>): Double {
        if (buf.isEmpty()) return 0.0
        val mean = buf.average()
        val magnitude = sqrt(buf.sumOf { it * it } / buf.size)
        return if (mean < 0) -magnitude else magnitude
    }

    fun reset() {
        peakG = 0.0
        peakLateralG = 0.0
        hangTimeMs = 0
        crashDetected = false
        highGStartTime = 0
        gBuffer.clear()
        latBuffer.clear()
        vertBuffer.clear()
        lonBuffer.clear()
        vibrationBuffer.clear()
    }

    fun acknowledgeCrash() {
        crashDetected = false
    }
}
