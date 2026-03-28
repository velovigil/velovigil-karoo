package com.velovigil.karoo

import android.util.Log
import kotlin.math.sqrt

/**
 * Computes g-force metrics from Polar H10 accelerometer data.
 * H10 outputs 3-axis acceleration in mG (milli-g) at up to 200Hz.
 *
 * G-force applications:
 * - Cornering load (lateral g)
 * - Jump detection (vertical g drop → zero-g → impact)
 * - Sprint force (longitudinal acceleration)
 * - Descending smoothness (vibration amplitude)
 */
class GForceProcessor {

    companion object {
        private const val TAG = "veloVigil.GForce"
        private const val MG_TO_G = 1.0 / 1000.0 // mG → g conversion
    }

    // Current values (updated at sensor rate)
    @Volatile var currentG: Double = 1.0       // total g-force magnitude
    @Volatile var lateralG: Double = 0.0       // side-to-side (cornering)
    @Volatile var verticalG: Double = 1.0      // up-down (jumps, impacts)
    @Volatile var longitudinalG: Double = 0.0  // front-back (accel/decel)

    // Peak values for current ride
    @Volatile var peakG: Double = 0.0
    @Volatile var peakLateralG: Double = 0.0
    @Volatile var peakVerticalG: Double = 0.0

    // Jump detection
    @Volatile var isAirborne: Boolean = false
    @Volatile var hangTimeMs: Long = 0
    @Volatile var lastImpactG: Double = 0.0
    private var airborneStartTime: Long = 0

    // Smoothness (rolling RMS of vibration)
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

        lateralG = xG
        longitudinalG = yG
        verticalG = zG
        currentG = sqrt(xG * xG + yG * yG + zG * zG)

        // Update peaks
        if (currentG > peakG) peakG = currentG
        if (kotlin.math.abs(lateralG) > peakLateralG) peakLateralG = kotlin.math.abs(lateralG)
        if (kotlin.math.abs(verticalG) > peakVerticalG) peakVerticalG = kotlin.math.abs(verticalG)

        // Jump detection — airborne when total g < 0.3
        val now = System.currentTimeMillis()
        if (currentG < 0.3 && !isAirborne) {
            isAirborne = true
            airborneStartTime = now
        } else if (currentG >= 0.3 && isAirborne) {
            isAirborne = false
            hangTimeMs = now - airborneStartTime
            lastImpactG = currentG
            if (hangTimeMs > 100) { // Filter noise — real jumps are > 100ms
                Log.i(TAG, "JUMP! Hang time: ${hangTimeMs}ms, Impact: ${"%.1f".format(lastImpactG)}g")
            }
        }

        // Vibration RMS — deviation from 1g over rolling window
        val deviation = currentG - 1.0
        vibrationBuffer.addLast(deviation * deviation)
        if (vibrationBuffer.size > 100) vibrationBuffer.removeFirst()
        vibrationRMS = sqrt(vibrationBuffer.average())
    }

    fun reset() {
        peakG = 0.0
        peakLateralG = 0.0
        peakVerticalG = 0.0
        hangTimeMs = 0
        lastImpactG = 0.0
        vibrationBuffer.clear()
    }
}
