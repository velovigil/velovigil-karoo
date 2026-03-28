package com.velovigil.karoo

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarSensorSetting
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.UUID

/**
 * Manages direct BLE connection to Polar H10 for:
 * - RR intervals → HRV computation
 * - 3-axis accelerometer → g-force computation
 *
 * This is a SEPARATE connection from the Karoo's own HR pairing.
 */
class PolarConnector(
    context: Context,
    private val hrv: HRVProcessor,
    private val gforce: GForceProcessor,
    private val telemetry: TelemetryBuffer,
) {
    companion object {
        private const val TAG = "veloVigil.Polar"
    }

    private val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
        )
    ).also {
        it.setApiLogger { msg -> Log.d(TAG, "SDK: $msg") }
        Log.i(TAG, "Polar SDK initialized with applicationContext")
    }

    private val disposables = CompositeDisposable()
    private var connectedDeviceId: String? = null
    @Volatile var isConnected: Boolean = false
    @Volatile var h10Battery: Int = -1

    // Callbacks for device event bridge (set by VeloVigilExtension.connectDevice)
    var onHRUpdate: ((Int) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect(deviceId: String? = null) {
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.i(TAG, "BLE power: $powered")
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.i(TAG, "H10 connecting: ${polarDeviceInfo.deviceId}")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.i(TAG, "H10 connected: ${polarDeviceInfo.deviceId}")
                connectedDeviceId = polarDeviceInfo.deviceId
                isConnected = true
                onConnected?.invoke()
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.w(TAG, "H10 disconnected: ${polarDeviceInfo.deviceId}")
                isConnected = false
                disposables.clear()
                onDisconnected?.invoke()
            }

            override fun bleSdkFeatureReady(
                identifier: String,
                feature: PolarBleApi.PolarBleSdkFeature,
            ) {
                Log.i(TAG, "Feature ready: $feature for $identifier")
                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR -> {
                        startHRStreaming(identifier)
                    }
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        startAccelerometer(identifier)
                    }
                    else -> {}
                }
            }

            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData.PolarHrSample,
            ) {
                telemetry.heartRate = data.hr
                onHRUpdate?.invoke(data.hr)

                if (data.rrAvailable) {
                    for (rr in data.rrsMs) {
                        hrv.addRR(rr)
                    }
                    telemetry.rmssd = hrv.rmssd
                    telemetry.sdnn = hrv.sdnn
                    telemetry.pnn50 = hrv.pnn50
                    telemetry.meanRR = hrv.meanRR
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {}

            override fun batteryLevelReceived(identifier: String, level: Int) {
                h10Battery = level
                Log.i(TAG, "H10 battery: $level%")
            }
        })

        if (deviceId != null) {
            Log.i(TAG, "Connecting to H10: $deviceId")
            api.connectToDevice(deviceId)
        } else {
            // Search for any nearby Polar device via BLE scan
            Log.i(TAG, "Scanning for Polar devices...")
            val disposable = api.searchForDevice()
                .subscribe(
                    { deviceInfo ->
                        Log.i(TAG, "Found Polar device: id=${deviceInfo.deviceId} name=${deviceInfo.name} addr=${deviceInfo.address} rssi=${deviceInfo.rssi}")
                        if (deviceInfo.name.contains("H10", ignoreCase = true) && connectedDeviceId == null) {
                            connectedDeviceId = deviceInfo.deviceId // Prevent duplicate connects
                            Log.i(TAG, "Connecting to H10: ${deviceInfo.deviceId} (MAC: ${deviceInfo.address})")
                            api.connectToDevice(deviceInfo.deviceId)
                        }
                    },
                    { e -> Log.e(TAG, "Device search failed: ${e.message}") },
                )
            disposables.add(disposable)
        }
    }

    private fun startHRStreaming(deviceId: String) {
        Log.i(TAG, "Starting HR stream on $deviceId")
        val disposable = api.startHrStreaming(deviceId)
            .subscribe(
                { hrData ->
                    for (sample in hrData.samples) {
                        telemetry.heartRate = sample.hr
                        onHRUpdate?.invoke(sample.hr)
                        if (sample.rrAvailable) {
                            for (rr in sample.rrsMs) {
                                hrv.addRR(rr)
                            }
                            telemetry.rmssd = hrv.rmssd
                            telemetry.sdnn = hrv.sdnn
                            telemetry.pnn50 = hrv.pnn50
                            telemetry.meanRR = hrv.meanRR
                        }
                    }
                },
                { e -> Log.e(TAG, "HR stream error: ${e.message}") },
            )
        disposables.add(disposable)
    }

    private fun startAccelerometer(deviceId: String) {
        Log.i(TAG, "Requesting ACC settings for $deviceId")
        val disposable = api.requestStreamSettings(
            deviceId,
            PolarBleApi.PolarDeviceDataType.ACC,
        ).flatMapPublisher { settings ->
            // Use 50Hz sample rate
            val selectedSettings = settings.maxSettings()
            Log.i(TAG, "Starting ACC stream: $selectedSettings")
            api.startAccStreaming(deviceId, selectedSettings)
        }.subscribe(
            { accData ->
                for (sample in accData.samples) {
                    gforce.addSample(sample.x, sample.y, sample.z)
                }
                telemetry.currentG = gforce.currentG
                telemetry.peakG = gforce.peakG
                telemetry.lateralG = gforce.lateralG
                telemetry.isAirborne = gforce.isAirborne
                telemetry.hangTimeMs = gforce.hangTimeMs
            },
            { e -> Log.e(TAG, "ACC stream error: ${e.message}") },
        )
        disposables.add(disposable)
    }

    fun disconnect() {
        disposables.clear()
        connectedDeviceId?.let {
            try {
                api.disconnectFromDevice(it)
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect error: ${e.message}")
            }
        }
        isConnected = false
        api.shutDown()
        Log.i(TAG, "Polar connector shut down")
    }
}
