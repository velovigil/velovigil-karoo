package com.velovigil.karoo

import android.util.Log
import java.util.LinkedList
import kotlin.math.sqrt

/**
 * Computes real-time HRV metrics from Polar H10 RR intervals.
 * Maintains a rolling window and recalculates on each new sample.
 */
class HRVProcessor(
    private val windowSeconds: Int = 300, // 5 minute rolling window
) {
    companion object {
        private const val TAG = "veloVigil.HRV"
    }

    private val rrBuffer = LinkedList<Double>() // RR intervals in ms
    private val timestamps = LinkedList<Long>()  // when each RR was received

    // Current computed metrics
    @Volatile var rmssd: Double = 0.0
    @Volatile var sdnn: Double = 0.0
    @Volatile var pnn50: Double = 0.0
    @Volatile var meanRR: Double = 0.0
    @Volatile var sampleCount: Int = 0

    fun addRR(rrMs: Int) {
        // v0.3.0 artifact rejection: physiologic RR is 300–2000 ms (30–200 bpm). Anything else is a dropout
        // or a reconnect gap, and one of those drove RMSSD to 14,079 ms in a May 2026 ride. Also reject a
        // successive difference > 400 ms — no heart does that beat to beat; a strap does.
        if (rrMs < 300 || rrMs > 2000) return
        val prev = rrBuffer.lastOrNull()
        if (prev != null && kotlin.math.abs(rrMs - prev) > 400) return
        val now = System.currentTimeMillis()
        val windowMs = windowSeconds * 1000L

        rrBuffer.add(rrMs.toDouble())
        timestamps.add(now)

        // Trim to window
        while (timestamps.isNotEmpty() && (now - timestamps.first) > windowMs) {
            timestamps.removeFirst()
            rrBuffer.removeFirst()
        }

        sampleCount = rrBuffer.size

        if (sampleCount >= 2) {
            computeMetrics()
        }
    }

    private fun computeMetrics() {
        val rrs = rrBuffer.toList()
        val n = rrs.size

        // Mean RR
        meanRR = rrs.sum() / n

        // SDNN — standard deviation of all RR intervals
        val meanVal = meanRR
        val variance = rrs.sumOf { (it - meanVal) * (it - meanVal) } / n
        sdnn = sqrt(variance)

        // Successive differences
        val diffs = (1 until n).map { rrs[it] - rrs[it - 1] }

        // RMSSD — root mean square of successive differences
        val squaredDiffs = diffs.sumOf { it * it }
        rmssd = sqrt(squaredDiffs / diffs.size)

        // pNN50 — percentage of successive diffs > 50ms
        val nn50Count = diffs.count { kotlin.math.abs(it) > 50.0 }
        pnn50 = (nn50Count.toDouble() / diffs.size) * 100.0
    }

    fun reset() {
        rrBuffer.clear()
        timestamps.clear()
        rmssd = 0.0
        sdnn = 0.0
        pnn50 = 0.0
        meanRR = 0.0
        sampleCount = 0
    }
}
