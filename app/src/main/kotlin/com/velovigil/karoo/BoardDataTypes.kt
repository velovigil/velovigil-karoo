package com.velovigil.karoo

import android.util.Log
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Custom Karoo data types for Onewheel board telemetry.
 *
 * Each type reads from the shared TelemetryBuffer held by VeloVigilExtension.instance
 * and emits a StreamState.Streaming DataPoint once per second. This makes the values
 * pickable in the Karoo data-field grid during profile setup.
 */

private const val TAG_PREFIX = "veloVigil."

private fun telemetryValue(dataTypeId: String): Double? {
    val t = VeloVigilExtension.instance?.telemetry ?: return null
    return when (dataTypeId) {
        "board_battery"    -> if (t.boardBatteryPct >= 0) t.boardBatteryPct.toDouble() else null
        "motor_temp"       -> if (t.motorTempC >= 0) t.motorTempC.toDouble() else null
        "safety_headroom"  -> if (t.safetyHeadroom >= 0) t.safetyHeadroom.toDouble() else null
        "board_amps"       -> t.boardCurrentAmps
        "board_speed"      -> t.speed // m/s, fed by OnewheelConnector from RPM
        else -> null
    }
}

private class BoardStreamingDataType(
    extension: String,
    private val id: String,
    private val tag: String,
    private val noDataLabel: String,
) : DataTypeImpl(extension, id) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun startView(
        context: android.content.Context,
        config: ViewConfig,
        emitter: ViewEmitter,
    ) {
        Log.i(TAG_PREFIX + tag, "View started")
        val v = telemetryValue(id)
        val label = if (v == null) noDataLabel else formatValue(id, v)
        emitter.onNext(ShowCustomStreamState(label, null))
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.i(TAG_PREFIX + tag, "Stream started")
        val job: Job = scope.launch {
            while (isActive) {
                val v = telemetryValue(id)
                if (v != null) {
                    emitter.onNext(StreamState.Streaming(
                        DataPoint(dataTypeId = id, values = mapOf("SINGLE" to v))
                    ))
                } else {
                    emitter.onNext(StreamState.NotAvailable)
                }
                delay(1000L)
            }
        }
        emitter.setCancellable {
            job.cancel()
            Log.i(TAG_PREFIX + tag, "Stream cancelled")
        }
    }

    private fun formatValue(id: String, v: Double): String = when (id) {
        "board_battery"   -> "${v.toInt()}%"
        "motor_temp"      -> "${v.toInt()}°C"
        "safety_headroom" -> v.toInt().toString()
        "board_amps"      -> "%.1fA".format(v)
        "board_speed"     -> "%.1f m/s".format(v)
        else -> v.toString()
    }
}

class BoardBatteryDataType(extension: String)   : DataTypeImpl(extension, "board_battery") {
    private val inner = BoardStreamingDataType(extension, "board_battery", "Battery", "--%")
    override fun startView(c: android.content.Context, cfg: ViewConfig, e: ViewEmitter) = inner.startView(c, cfg, e)
    override fun startStream(e: Emitter<StreamState>) = inner.startStream(e)
}
class MotorTempDataType(extension: String)      : DataTypeImpl(extension, "motor_temp") {
    private val inner = BoardStreamingDataType(extension, "motor_temp", "MotorTemp", "-- °C")
    override fun startView(c: android.content.Context, cfg: ViewConfig, e: ViewEmitter) = inner.startView(c, cfg, e)
    override fun startStream(e: Emitter<StreamState>) = inner.startStream(e)
}
class HeadroomDataType(extension: String)       : DataTypeImpl(extension, "safety_headroom") {
    private val inner = BoardStreamingDataType(extension, "safety_headroom", "Headroom", "--")
    override fun startView(c: android.content.Context, cfg: ViewConfig, e: ViewEmitter) = inner.startView(c, cfg, e)
    override fun startStream(e: Emitter<StreamState>) = inner.startStream(e)
}
class BoardAmpsDataType(extension: String)      : DataTypeImpl(extension, "board_amps") {
    private val inner = BoardStreamingDataType(extension, "board_amps", "Amps", "-- A")
    override fun startView(c: android.content.Context, cfg: ViewConfig, e: ViewEmitter) = inner.startView(c, cfg, e)
    override fun startStream(e: Emitter<StreamState>) = inner.startStream(e)
}
class BoardSpeedDataType(extension: String)     : DataTypeImpl(extension, "board_speed") {
    private val inner = BoardStreamingDataType(extension, "board_speed", "BoardSpeed", "-- m/s")
    override fun startView(c: android.content.Context, cfg: ViewConfig, e: ViewEmitter) = inner.startView(c, cfg, e)
    override fun startStream(e: Emitter<StreamState>) = inner.startStream(e)
}
