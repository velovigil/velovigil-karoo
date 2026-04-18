package com.velovigil.karoo

import android.content.Context
import android.util.Log
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*

/**
 * veloVigil Karoo Extension — Phase 3: Virtual HR Sensor
 *
 * Registers as a Karoo sensor device (scansDevices=true).
 * Connects to Polar H10 via BLE for HR, RR intervals (HRV), and accelerometer.
 * Provides HR back to Karoo as a native sensor source.
 * Also subscribes to Karoo data streams (speed, power, cadence) for telemetry.
 */
class VeloVigilExtension : KarooExtension("velovigil", BuildConfig.VERSION_NAME) {

    companion object {
        private const val TAG = "veloVigil"
        private const val POLAR_HR_DEVICE_UID = "velovigil-polar-hr"
        var instance: VeloVigilExtension? = null
    }

    lateinit var karooSystem: KarooSystemService
    lateinit var telemetry: TelemetryBuffer
    val hrv = HRVProcessor()
    val gforce = GForceProcessor()
    var polar: PolarConnector? = null
    var onewheel: OnewheelConnector? = null
    private val consumerIds = mutableListOf<String>()
    private var deviceEmitter: Emitter<DeviceEvent>? = null
    private var deviceStreamJob: Job? = null
    private val deviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var inactivityWatchdog: Job? = null
    @Volatile private var lastBoardDataAt: Long = 0L

    override val types by lazy {
        listOf(
            FleetStatusDataType(extension),
            BoardBatteryDataType(extension),
            MotorTempDataType(extension),
            HeadroomDataType(extension),
            BoardAmpsDataType(extension),
            BoardSpeedDataType(extension),
        )
    }

    /**
     * Called when user opens Sensor Scan on Karoo.
     * Emits a virtual HR sensor device that the user can pair.
     */
    override fun startScan(emitter: Emitter<Device>) {
        Log.i(TAG, "Sensor scan started — emitting Polar HR device")
        val job = deviceScope.launch {
            delay(1000) // Brief scan simulation
            emitter.onNext(
                Device(
                    extension,
                    POLAR_HR_DEVICE_UID,
                    listOf(DataType.Source.HEART_RATE),
                    "veloVigil HR (Polar H10)",
                )
            )
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Called when user selects "veloVigil HR (Polar H10)" in Sensor Scan.
     * Connects to the actual Polar H10 via BLE and streams HR to Karoo.
     */
    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Log.i(TAG, "connectDevice: uid=$uid")
        if (uid != POLAR_HR_DEVICE_UID) {
            Log.w(TAG, "Unknown device UID: $uid")
            return
        }

        deviceEmitter = emitter

        // Signal searching state
        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))

        // Connect to Polar H10 via BLE
        if (polar == null) {
            polar = PolarConnector(this, hrv, gforce, telemetry)
        }
        polar?.onHRUpdate = { hr ->
            // Push HR to Karoo as native sensor data
            emitter.onNext(
                OnDataPoint(
                    DataPoint(
                        DataType.Source.HEART_RATE,
                        mapOf(DataType.Field.HEART_RATE to hr.toDouble()),
                        POLAR_HR_DEVICE_UID,
                    )
                )
            )
        }
        polar?.onConnected = {
            Log.i(TAG, "Polar H10 connected — signaling Karoo")
            emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
            emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
            emitter.onNext(
                OnManufacturerInfo(
                    ManufacturerInfo("Polar", "H10", "veloVigil BLE Bridge")
                )
            )
        }
        polar?.onDisconnected = {
            Log.w(TAG, "Polar H10 disconnected — signaling Karoo")
            emitter.onNext(OnConnectionStatus(ConnectionStatus.DISCONNECTED))
        }
        polar?.connect()

        emitter.setCancellable {
            Log.i(TAG, "Device connection cancelled by Karoo")
            deviceEmitter = null
            polar?.onHRUpdate = null
            polar?.onConnected = null
            polar?.onDisconnected = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "veloVigil v${BuildConfig.VERSION_NAME} starting")

        // Load settings from SharedPreferences
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(SettingsActivity.KEY_FLEET_ENDPOINT, SettingsActivity.DEFAULT_ENDPOINT)
            ?: SettingsActivity.DEFAULT_ENDPOINT
        val riderKey = prefs.getString(SettingsActivity.KEY_RIDER_KEY, "") ?: ""
        val riderId = prefs.getString(SettingsActivity.KEY_RIDER_ID, "unregistered") ?: "unregistered"

        if (riderKey.isEmpty()) {
            Log.w(TAG, "No rider API key configured — telemetry will get 401 until registered via Settings")
        }

        telemetry = TelemetryBuffer(
            endpoint = endpoint,
            deviceKey = riderKey, // Must register via Settings first
            riderId = riderId,
        )

        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            if (connected) {
                Log.i(TAG, "Connected to Karoo system")
                subscribeToData()
                subscribeToRideState()
                telemetry.start()
                startOnewheelConnector(prefs)
                startInactivityWatchdog()
            } else {
                Log.w(TAG, "Failed to connect to Karoo system")
            }
        }
    }

    private fun startOnewheelConnector(prefs: android.content.SharedPreferences) {
        val token = prefs.getString(SettingsActivity.KEY_BOARD_TOKEN, "") ?: ""
        val boardName = prefs.getString(SettingsActivity.KEY_BOARD_NAME, "") ?: ""
        if (token.isEmpty() && boardName.isEmpty()) {
            Log.i(TAG, "Onewheel: no token or board name configured — skipping board connection")
            return
        }
        Log.i(TAG, "Starting OnewheelConnector (board=${boardName.ifEmpty { "any ow*" }})")
        val ow = OnewheelConnector(this, telemetry, token, boardName)
        ow.onBoardConnected = {
            telemetry.boardConnected = true
            lastBoardDataAt = System.currentTimeMillis()
        }
        ow.onBoardDisconnected = {
            telemetry.boardConnected = false
        }
        ow.onBoardAuthenticated = {
            Log.i(TAG, "Board authenticated — telemetry flowing")
        }
        ow.onTelemetryUpdate = {
            lastBoardDataAt = System.currentTimeMillis()
        }
        ow.start()
        onewheel = ow
    }

    private fun subscribeToRideState() {
        // Listen to Karoo's own ride lifecycle — fixes stuck RECORDING when user hits stop on Karoo
        val id = karooSystem.addConsumer<RideState> { event ->
            when (event) {
                is RideState.Recording -> telemetry.rideState = "RECORDING"
                is RideState.Paused    -> telemetry.rideState = "PAUSED"
                is RideState.Idle      -> telemetry.rideState = "IDLE"
                else -> {}
            }
            Log.i(TAG, "RideState → ${telemetry.rideState}")
        }
        consumerIds.add(id)
    }

    /**
     * Safety net for stuck RECORDING: if we haven't seen any telemetry movement
     * (speed, board RPM, HR change) for 120s AND board isn't connected, flip to IDLE.
     * This catches cases where the Karoo never emits RideState.Idle.
     */
    private fun startInactivityWatchdog() {
        inactivityWatchdog = deviceScope.launch {
            var lastSpeed = 0.0
            var lastHr = 0
            var lastRpm = 0
            var lastChangeAt = System.currentTimeMillis()
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                val moved = telemetry.speed != lastSpeed ||
                            telemetry.heartRate != lastHr ||
                            telemetry.boardRpm != lastRpm ||
                            telemetry.boardConnected
                if (moved) {
                    lastSpeed = telemetry.speed
                    lastHr = telemetry.heartRate
                    lastRpm = telemetry.boardRpm
                    lastChangeAt = now
                } else if (telemetry.rideState == "RECORDING" && now - lastChangeAt > 120_000L) {
                    Log.w(TAG, "Inactivity watchdog: no motion 120s — flipping state to IDLE")
                    telemetry.rideState = "IDLE"
                    lastChangeAt = now
                }
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "veloVigil stopping")
        inactivityWatchdog?.cancel()
        deviceScope.cancel()
        polar?.disconnect()
        onewheel?.stop()
        onewheel = null
        telemetry.stop()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        instance = null
        super.onDestroy()
    }

    private fun subscribeToData() {
        // Subscribe to non-HR data types (HR now comes from our Polar BLE connection)
        val dataTypes = listOf(
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
        Log.i(TAG, "Subscribed to ${dataTypes.size} Karoo data streams (HR via Polar BLE)")
    }

    private fun handleStreamState(type: String, state: StreamState) {
        when (state) {
            is StreamState.Streaming -> {
                telemetry.rideState = "RECORDING"
                val value = state.dataPoint.singleValue
                Log.d(TAG, "Stream $type: value=$value")
                value?.let {
                    when (type) {
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
