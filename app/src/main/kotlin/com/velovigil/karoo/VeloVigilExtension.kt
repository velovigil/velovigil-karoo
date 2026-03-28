package com.velovigil.karoo

import android.util.Log
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.*

/**
 * veloVigil Karoo Extension — Phase 2
 *
 * Subscribes to Karoo data streams (HR, speed, power, cadence).
 * Buffers telemetry and POSTs to Fleet backend every 5 seconds.
 * Displays live status on Karoo data field.
 */
class VeloVigilExtension : KarooExtension("velovigil", BuildConfig.VERSION_NAME) {

    companion object {
        private const val TAG = "veloVigil"
        var instance: VeloVigilExtension? = null
    }

    lateinit var karooSystem: KarooSystemService
    val telemetry = TelemetryBuffer()
    val hrv = HRVProcessor()
    val gforce = GForceProcessor()
    var polar: PolarConnector? = null
    private val consumerIds = mutableListOf<String>()

    override val types by lazy {
        listOf(
            FleetStatusDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "veloVigil v${BuildConfig.VERSION_NAME} starting")

        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            if (connected) {
                Log.i(TAG, "Connected to Karoo system")
                subscribeToData()
                telemetry.start()

                // Connect to Polar H10 for RR intervals + accelerometer
                polar = PolarConnector(this@VeloVigilExtension, hrv, gforce, telemetry)
                polar?.connect() // Auto-search for H10
            } else {
                Log.w(TAG, "Failed to connect to Karoo system")
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "veloVigil stopping")
        polar?.disconnect()
        telemetry.stop()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        instance = null
        super.onDestroy()
    }

    private fun subscribeToData() {
        // Subscribe to each data type individually
        val dataTypes = listOf(
            DataType.Type.HEART_RATE,
            DataType.Type.SPEED,
            DataType.Type.POWER,
            DataType.Type.CADENCE,
            DataType.Type.DISTANCE,
        )

        for (type in dataTypes) {
            val id = karooSystem.addConsumer<OnStreamState>(
                OnStreamState.StartStreaming(type),
                onError = { error -> Log.e(TAG, "Consumer error ($type): $error") },
                onComplete = { Log.i(TAG, "Consumer complete ($type)") },
            ) { event ->
                handleStreamState(type, event.state)
            }
            consumerIds.add(id)
        }
        Log.i(TAG, "Subscribed to ${dataTypes.size} Karoo data streams")
    }

    private fun handleStreamState(type: String, state: StreamState) {
        when (state) {
            is StreamState.Streaming -> {
                telemetry.rideState = "RECORDING"
                val value = state.dataPoint.singleValue
                Log.d(TAG, "Stream $type: value=$value")
                value?.let {
                    when (type) {
                        DataType.Type.HEART_RATE -> { telemetry.heartRate = it.toInt(); Log.i(TAG, "HR: ${it.toInt()}") }
                        DataType.Type.SPEED -> telemetry.speed = it
                        DataType.Type.POWER -> telemetry.power = it.toInt()
                        DataType.Type.CADENCE -> telemetry.cadence = it.toInt()
                        DataType.Type.DISTANCE -> telemetry.distance = it
                    }
                }
                state.dataPoint.values["lat"]?.let { telemetry.latitude = it }
                state.dataPoint.values["lng"]?.let { telemetry.longitude = it }
            }
            is StreamState.Idle -> {
                telemetry.rideState = "IDLE"
            }
            else -> {}
        }
    }
}

/**
 * Fleet Status data field — shows ride state and HR on the Karoo screen.
 */
class FleetStatusDataType(extension: String) : DataTypeImpl(extension, "fleet_status") {

    companion object {
        private const val TAG = "veloVigil.Status"
    }

    override fun startView(
        context: android.content.Context,
        config: ViewConfig,
        emitter: ViewEmitter,
    ) {
        Log.i(TAG, "Status tile started")
        val ext = VeloVigilExtension.instance
        val hr = ext?.telemetry?.heartRate ?: 0
        val state = ext?.telemetry?.rideState ?: "INIT"
        emitter.onNext(ShowCustomStreamState("$state HR:$hr", null))
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val hr = VeloVigilExtension.instance?.telemetry?.heartRate ?: 0
        val dataPoint = DataPoint(
            dataTypeId = dataTypeId,
            values = mapOf("hr" to hr.toDouble()),
        )
        emitter.onNext(StreamState.Streaming(dataPoint))
    }
}
