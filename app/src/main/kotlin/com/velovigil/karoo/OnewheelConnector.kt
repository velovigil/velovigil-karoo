package com.velovigil.karoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct BLE connection to an Onewheel board (GT-S + legacy models).
 *
 * Feeds board telemetry into TelemetryBuffer so the fleet worker receives
 * speed, battery, motor temp, safety headroom, current, and pitch/roll
 * alongside the existing HR/HRV/gforce stream.
 *
 * Auth paths:
 *   - Polaris static token (firmware ≥ 6215, incl. GT-S) — write 20-byte token to
 *     UART Write char, rewrite every 15s as keep-alive
 *   - Legacy MD5 challenge-response (pre-Polaris firmware) — subscribe to UART
 *     Read, write FW rev back, collect 20-byte challenge, compute MD5(challenge+key),
 *     send response, re-auth every 20s
 *
 * Configuration via SharedPreferences:
 *   - SettingsActivity.KEY_BOARD_TOKEN — hex string, 40 chars, Polaris token
 *   - SettingsActivity.KEY_BOARD_NAME  — BLE advertisement name to match (e.g., "ow452500")
 *
 * If no token configured, falls back to legacy MD5 path. If that fails too,
 * connection stays alive but characteristic reads return zeros (expected pre-auth
 * behavior).
 */
class OnewheelConnector(
    private val context: Context,
    private val telemetry: TelemetryBuffer,
    private val tokenHex: String,
    private val boardNameFilter: String,
) {
    companion object {
        private const val TAG = "veloVigil.OW"

        // Service UUID
        private val OW_SERVICE = UUID.fromString("e659f300-ea98-11e3-ac10-0800200c9a66")

        // Characteristic UUIDs
        private val OW_BATTERY_PCT   = UUID.fromString("e659f303-ea98-11e3-ac10-0800200c9a66")
        private val OW_PITCH         = UUID.fromString("e659f307-ea98-11e3-ac10-0800200c9a66")
        private val OW_ROLL          = UUID.fromString("e659f308-ea98-11e3-ac10-0800200c9a66")
        private val OW_TRIP_ODO      = UUID.fromString("e659f30a-ea98-11e3-ac10-0800200c9a66")
        private val OW_RPM           = UUID.fromString("e659f30b-ea98-11e3-ac10-0800200c9a66")
        private val OW_SPEED         = UUID.fromString("e659f30c-ea98-11e3-ac10-0800200c9a66")
        private val OW_MOTOR_TEMP    = UUID.fromString("e659f311-ea98-11e3-ac10-0800200c9a66")
        private val OW_FIRMWARE_REV  = UUID.fromString("e659f312-ea98-11e3-ac10-0800200c9a66")
        private val OW_CURRENT_AMPS  = UUID.fromString("e659f313-ea98-11e3-ac10-0800200c9a66")
        private val OW_SAFETY_HEADROOM = UUID.fromString("e659f316-ea98-11e3-ac10-0800200c9a66")
        private val OW_UART_READ     = UUID.fromString("e659f3fe-ea98-11e3-ac10-0800200c9a66")
        private val OW_UART_WRITE    = UUID.fromString("e659f3ff-ea98-11e3-ac10-0800200c9a66")

        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Shared auth key (same across all OW models — see skills/Domain/Onewheel/SKILL.md)
        private val LEGACY_AUTH_KEY = byteArrayOf(
            0xD9.toByte(), 0x25, 0x5F, 0x0F, 0x23, 0x35, 0x4E, 0x19,
            0xBA.toByte(), 0x73, 0x9C.toByte(), 0xCD.toByte(), 0xC4.toByte(), 0xA9.toByte(), 0x17, 0x65
        )

        // Wheel circumference for RPM→m/s conversion: GT-S is 11.5" tire, 36.1" rolling circumference
        private const val WHEEL_CIRCUMFERENCE_M = 0.917 // 36.1" → 0.917m
        private const val KEEP_ALIVE_INTERVAL_MS = 15_000L  // Polaris: 15s. Legacy: override to 20s
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val MTU_SIZE = 512
    }

    private val btAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isConnected: Boolean = false
        private set
    @Volatile var isAuthenticated: Boolean = false
        private set
    @Volatile var lastStatus: String = "Idle"
        private set

    private val isPolaris: Boolean = tokenHex.length == 40 && tokenHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    private val tokenBytes: ByteArray = if (isPolaris) hexToBytes(tokenHex) else ByteArray(0)

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanRunning: AtomicBoolean = AtomicBoolean(false)
    private var keepAliveRunnable: Runnable? = null
    private val legacyChallengeBuffer = mutableListOf<Byte>()
    @Volatile private var awaitingChallenge = false
    @Volatile private var fwRevBytes: ByteArray? = null  // captured from onCharacteristicRead

    // Volatile cached values (so extension can peek state)
    @Volatile var lastBatteryPct: Int = -1
        private set
    @Volatile var lastMotorTempC: Int = -1
        private set
    @Volatile var lastHeadroom: Int = -1
        private set
    @Volatile var lastCurrentAmps: Double = 0.0
        private set
    @Volatile var lastRpm: Int = 0
        private set
    @Volatile var lastSpeedMs: Double = 0.0
        private set
    @Volatile var lastPitchDeg: Double = 0.0
        private set
    @Volatile var lastRollDeg: Double = 0.0
        private set

    // Callbacks for extension to observe state transitions
    var onBoardConnected: (() -> Unit)? = null
    var onBoardDisconnected: (() -> Unit)? = null
    var onBoardAuthenticated: (() -> Unit)? = null
    var onTelemetryUpdate: (() -> Unit)? = null

    /** Begin scan + connect + auth pipeline. Non-blocking. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (btAdapter == null || btAdapter?.isEnabled != true) {
            lastStatus = "BT off or unavailable"
            Log.w(TAG, lastStatus)
            return
        }
        if (tokenHex.isEmpty()) {
            Log.w(TAG, "No Polaris token configured — will attempt legacy MD5 auth (probably fails on GT-S)")
        }
        startScan()
    }

    /** Stop everything and release BLE resources cleanly. */
    @SuppressLint("MissingPermission")
    fun stop() {
        stopScan()
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect perm: ${e.message}")
        }
        gatt = null
        targetDevice = null
        isConnected = false
        isAuthenticated = false
        lastStatus = "Stopped"
    }

    // ─── Scanning ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanRunning.getAndSet(true)) return
        val adapter = btAdapter ?: return
        scanner = adapter.bluetoothLeScanner ?: run {
            lastStatus = "No LE scanner"
            scanRunning.set(false)
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(OW_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.i(TAG, "Scanning for Onewheel (filter=${boardNameFilter.ifEmpty { "any ow*" }})")
        lastStatus = "Scanning"
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            lastStatus = "BT scan perm denied"
            Log.e(TAG, "scan perm: ${e.message}")
            scanRunning.set(false)
            return
        }

        mainHandler.postDelayed({
            if (scanRunning.get() && targetDevice == null) {
                Log.w(TAG, "Scan timeout — board not advertising. Retrying in 20s.")
                lastStatus = "No board found — retrying"
                stopScan()
                mainHandler.postDelayed({
                    Log.i(TAG, "Scan-timeout retry firing")
                    start()
                }, 20_000L)
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanRunning.getAndSet(false)) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan perm: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = try { device.name ?: "" } catch (e: SecurityException) { "" }

            val match = if (boardNameFilter.isNotEmpty()) {
                name.equals(boardNameFilter, ignoreCase = true)
            } else {
                name.startsWith("ow", ignoreCase = true)
            }

            if (match && targetDevice == null) {
                Log.i(TAG, "Found board: $name @ ${device.address} rssi=${result.rssi}")
                lastStatus = "Found $name"
                targetDevice = device
                stopScan()
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            lastStatus = "Scan failed ($errorCode)"
            scanRunning.set(false)
        }
    }

    // ─── GATT connection ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        Log.i(TAG, "Connecting to ${device.address}")
        lastStatus = "Connecting"
        try {
            gatt = device.connectGatt(context, /*autoConnect=*/false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt perm: ${e.message}")
            lastStatus = "Connect perm denied"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Conn state: status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                lastStatus = "Connected, MTU negotiating"
                onBoardConnected?.invoke()
                try {
                    g.requestMtu(MTU_SIZE)
                } catch (e: SecurityException) {
                    Log.w(TAG, "requestMtu perm: ${e.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnect path — status=$status — scheduling retry in 3s")
                isConnected = false
                isAuthenticated = false
                lastStatus = "Disconnected status=$status"
                keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
                keepAliveRunnable = null
                try { g.close() } catch (e: SecurityException) { /* noop */ }
                gatt = null
                targetDevice = null  // Must reset so scan-callback target match fires again
                scanRunning.set(false) // Ensure scan can restart
                onBoardDisconnected?.invoke()
                mainHandler.postDelayed({
                    Log.i(TAG, "Retry fired — starting fresh scan")
                    start()
                }, 3_000L)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            try {
                g.discoverServices()
            } catch (e: SecurityException) {
                Log.e(TAG, "discoverServices perm: ${e.message}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                lastStatus = "Service disc failed"
                return
            }
            val service = g.getService(OW_SERVICE)
            if (service == null) {
                Log.e(TAG, "OW service not present on this device")
                lastStatus = "Not an OW board"
                return
            }
            lastStatus = "Services found, authenticating"
            // Subscribe to UART Read first — required for legacy challenge path AND
            // FM app does it first for Polaris too
            enableNotifications(g, service.getCharacteristic(OW_UART_READ)) {
                if (isPolaris) {
                    writePolarisToken(g, service)
                } else {
                    startLegacyAuth(g, service)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write: ${descriptor.characteristic.uuid} status=$status")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "Char write: ${characteristic.uuid} status=$status")
            if (characteristic.uuid == OW_UART_WRITE && status == BluetoothGatt.GATT_SUCCESS && isPolaris) {
                // Token write succeeded — poke a read to verify unlock
                mainHandler.postDelayed({
                    val batt = g.getService(OW_SERVICE)?.getCharacteristic(OW_BATTERY_PCT)
                    if (batt != null) {
                        try { g.readCharacteristic(batt) } catch (e: SecurityException) { /* noop */ }
                    }
                }, 400)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Char read failed: ${characteristic.uuid} status=$status")
                return
            }
            // Legacy auth: capture the first non-zero 2-byte read from f312 OR f311
            // (Polaris firmware hides real fw rev at f311 and returns 0 at f312)
            if (!isPolaris && !awaitingChallenge &&
                (characteristic.uuid == OW_FIRMWARE_REV || characteristic.uuid == OW_MOTOR_TEMP)) {
                val v = characteristic.value
                val nonZero = v != null && v.any { it != 0.toByte() }
                if (nonZero) {
                    fwRevBytes = v!!.copyOf()
                    Log.i(TAG, "fw rev captured from ${characteristic.uuid.toString().takeLast(6)}: " +
                        "${v.joinToString("") { "%02X".format(it) }}")
                    mainHandler.postDelayed({ writeFwRevToTriggerChallenge(g) }, 100)
                    return
                } else {
                    Log.d(TAG, "read ${characteristic.uuid.toString().takeLast(6)} returned zeros, trying alt")
                }
            }
            handleCharacteristicData(g, characteristic)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == OW_UART_READ && awaitingChallenge) {
                legacyChallengeBuffer.addAll(characteristic.value.toList())
                if (legacyChallengeBuffer.size >= 20) {
                    awaitingChallenge = false
                    completeLegacyAuth(g, legacyChallengeBuffer.take(20).toByteArray())
                }
            } else {
                handleCharacteristicData(g, characteristic)
            }
        }
    }

    // ─── Polaris auth ────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun writePolarisToken(g: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val uartWrite = service.getCharacteristic(OW_UART_WRITE) ?: run {
            Log.e(TAG, "No UART Write char")
            return
        }
        tryPolarisWrite(g, uartWrite, attempt = 1, maxAttempts = 5)
    }

    @SuppressLint("MissingPermission")
    private fun tryPolarisWrite(
        g: BluetoothGatt,
        uartWrite: BluetoothGattCharacteristic,
        attempt: Int,
        maxAttempts: Int,
    ) {
        uartWrite.value = tokenBytes
        uartWrite.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val ok = g.writeCharacteristic(uartWrite)
            Log.i(TAG, "Polaris token write attempt $attempt: ok=$ok")
            if (!ok && attempt < maxAttempts) {
                mainHandler.postDelayed({
                    tryPolarisWrite(g, uartWrite, attempt + 1, maxAttempts)
                }, 500L)
                return
            }
            if (ok) {
                lastStatus = "Token written (attempt $attempt)"
                scheduleKeepAlive()
                // Wait 800ms after successful write for board to process, then subscribe
                val svc = g.getService(OW_SERVICE) ?: return
                mainHandler.postDelayed({ subscribeToTelemetry(g, svc) }, 800L)
            } else {
                lastStatus = "Token write failed after $maxAttempts attempts"
                Log.e(TAG, lastStatus)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "writeChar perm: ${e.message}")
        }
    }

    private fun scheduleKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = object : Runnable {
            override fun run() {
                val g = gatt
                val svc = g?.getService(OW_SERVICE)
                val uart = svc?.getCharacteristic(OW_UART_WRITE)
                if (g == null || uart == null) return
                if (isPolaris) {
                    uart.value = tokenBytes
                    uart.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    try {
                        @SuppressLint("MissingPermission")
                        g.writeCharacteristic(uart)
                    } catch (e: SecurityException) { /* noop */ }
                } else {
                    // legacy: re-run full challenge flow (read fw → write back → await CRX)
                    startLegacyAuth(g, svc!!)
                    // keep-alive interval is longer for legacy (20s not 15s per skill doc)
                }
                mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(keepAliveRunnable!!, KEEP_ALIVE_INTERVAL_MS)
    }

    // ─── Legacy MD5 challenge-response ───────────────────────────
    //
    // Correct order (per skills/Domain/Onewheel/SKILL.md step 2-4):
    //   1) UART Read notifications already enabled upstream
    //   2) Read firmware revision → onCharacteristicRead captures fwRevBytes
    //   3) Write firmware revision bytes back → triggers board to emit CRX challenge
    //   4) onCharacteristicChanged collects 20 bytes → completeLegacyAuth

    @SuppressLint("MissingPermission")
    private fun startLegacyAuth(g: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        legacyChallengeBuffer.clear()
        fwRevBytes = null
        awaitingChallenge = false
        // On Polaris firmware, f312 returns 0 pre-auth. Real fw rev (0x1847 = 6215)
        // appears at f311. Try reading both; whichever is non-zero, use it.
        val fwChar = service.getCharacteristic(OW_FIRMWARE_REV)
        val altChar = service.getCharacteristic(OW_MOTOR_TEMP)  // Polaris fw-rev hiding spot
        Log.i(TAG, "Legacy auth: reading fw rev from f312 + f311")
        try {
            if (fwChar != null) g.readCharacteristic(fwChar)
        } catch (e: SecurityException) { /* noop */ }
        mainHandler.postDelayed({
            try {
                if (altChar != null) g.readCharacteristic(altChar)
            } catch (e: SecurityException) { /* noop */ }
        }, 300)
    }

    @SuppressLint("MissingPermission")
    private fun writeFwRevToTriggerChallenge(g: BluetoothGatt) {
        val fw = fwRevBytes ?: run {
            Log.w(TAG, "Legacy auth: no fwRevBytes captured yet")
            return
        }
        val service = g.getService(OW_SERVICE) ?: return
        val fwChar = service.getCharacteristic(OW_FIRMWARE_REV) ?: return
        fwChar.value = fw
        fwChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        awaitingChallenge = true
        Log.i(TAG, "Legacy auth: writing fw=${fw.joinToString("") { "%02X".format(it) }} to trigger challenge")
        try {
            val ok = g.writeCharacteristic(fwChar)
            Log.i(TAG, "Legacy auth: write result=$ok")
            lastStatus = "Legacy auth: challenge requested"
        } catch (e: SecurityException) {
            Log.e(TAG, "writeCharacteristic perm: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun completeLegacyAuth(g: BluetoothGatt, challenge: ByteArray) {
        if (challenge.size != 20) return
        // Verify CRX signature
        if (challenge[0] != 0x43.toByte() || challenge[1] != 0x52.toByte() || challenge[2] != 0x58.toByte()) {
            Log.w(TAG, "Invalid challenge signature")
            lastStatus = "Legacy auth: bad sig"
            return
        }
        // Bytes 3..18 are the 16 challenge bytes (skip byte 19 check)
        val challengeMat = challenge.copyOfRange(3, 19)
        val md5Input = challengeMat + LEGACY_AUTH_KEY
        val md5 = MessageDigest.getInstance("MD5").digest(md5Input)
        val response = ByteArray(20)
        response[0] = 0x43; response[1] = 0x52; response[2] = 0x58  // CRX
        System.arraycopy(md5, 0, response, 3, 16)
        // XOR checksum byte at index 19
        var xor = 0
        for (i in 0 until 19) xor = xor xor response[i].toInt()
        response[19] = (xor and 0xFF).toByte()

        val service = g.getService(OW_SERVICE) ?: return
        val uartWrite = service.getCharacteristic(OW_UART_WRITE) ?: return
        uartWrite.value = response
        uartWrite.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            g.writeCharacteristic(uartWrite)
            lastStatus = "Legacy auth: response sent"
        } catch (e: SecurityException) { /* noop */ }

        scheduleKeepAlive()
        subscribeToTelemetry(g, service)
    }

    // ─── Subscribe to telemetry characteristics ──────────────────

    @SuppressLint("MissingPermission")
    private fun subscribeToTelemetry(g: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val charsToNotify = listOf(
            OW_BATTERY_PCT, OW_RPM, OW_SPEED, OW_MOTOR_TEMP,
            OW_CURRENT_AMPS, OW_SAFETY_HEADROOM, OW_PITCH, OW_ROLL
        )
        // Enable notifications sequentially with a tiny stagger so CCCD writes don't queue-overflow
        var delay = 0L
        for (u in charsToNotify) {
            val ch = service.getCharacteristic(u) ?: continue
            mainHandler.postDelayed({
                enableNotifications(g, ch) { }
            }, delay)
            delay += 120
        }
        // Also do a one-time read of each so we have initial values even if board
        // doesn't push a notification immediately
        mainHandler.postDelayed({
            for (u in charsToNotify) {
                val ch = service.getCharacteristic(u) ?: continue
                try { g.readCharacteristic(ch) } catch (e: SecurityException) { /* noop */ }
            }
        }, delay + 300)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic?, onComplete: () -> Unit) {
        if (ch == null) { onComplete(); return }
        val notifSet = try { g.setCharacteristicNotification(ch, true) } catch (e: SecurityException) { false }
        val desc = ch.getDescriptor(CCCD) ?: run { onComplete(); return }
        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        try {
            g.writeDescriptor(desc)
        } catch (e: SecurityException) {
            Log.w(TAG, "writeDescriptor perm: ${e.message}")
        }
        // Wait longer (600ms) so the CCCD descriptor write completes before caller tries
        // another write. Without this, the token write right after returns ok=false.
        mainHandler.postDelayed(onComplete, 600)
    }

    // ─── Telemetry parsing ───────────────────────────────────────

    private fun handleCharacteristicData(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val v = ch.value ?: return
        when (ch.uuid) {
            OW_BATTERY_PCT -> {
                // GT-S reports as uint8 (single byte), older as uint16
                val pct = if (v.size == 1) v[0].toInt() and 0xFF else ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
                if (pct in 0..100) {
                    lastBatteryPct = pct
                    telemetry.boardBatteryPct = pct
                    if (!isAuthenticated) {
                        isAuthenticated = true
                        lastStatus = "Authenticated — battery=$pct%"
                        onBoardAuthenticated?.invoke()
                    }
                }
            }
            OW_RPM -> {
                val rpm = parseUint16(v)
                lastRpm = rpm
                val speedMs = (rpm / 60.0) * WHEEL_CIRCUMFERENCE_M
                lastSpeedMs = speedMs
                // Feed into Karoo's speed field (what the cycling pipeline expects)
                telemetry.speed = speedMs
                telemetry.boardRpm = rpm
            }
            OW_SPEED -> {
                // Board-computed speed (for cross-check, not primary)
                val spd = parseUint16(v)
                // Some firmwares report in 0.1 mph — clamp
                // Keep as raw value for raw_json
                telemetry.boardSpeedRaw = spd
            }
            OW_MOTOR_TEMP -> {
                // Try tenths-of-C first, fallback to raw C
                val raw = parseUint16(v)
                val tempC = when {
                    raw in 100..1500 -> raw / 10   // tenths → 10-150°C
                    raw in 0..150    -> raw         // raw celsius
                    else              -> -1
                }
                if (tempC >= 0) {
                    lastMotorTempC = tempC
                    telemetry.motorTempC = tempC
                }
            }
            OW_CURRENT_AMPS -> {
                val amps = parseInt16(v) / 10.0
                lastCurrentAmps = amps
                telemetry.boardCurrentAmps = amps
                // Estimate power: amps × nominal pack voltage (~63V for GT-S 16S)
                val approxPower = (amps * 63.0).toInt()
                telemetry.power = approxPower.coerceAtLeast(0)
            }
            OW_SAFETY_HEADROOM -> {
                val hr = parseUint16(v)
                // Value semantics: higher = more headroom; ≤ 10% = pushback imminent
                lastHeadroom = hr
                telemetry.safetyHeadroom = hr
            }
            OW_PITCH -> {
                val d = parseInt16(v) / 10.0
                lastPitchDeg = d
                telemetry.pitchDeg = d
            }
            OW_ROLL -> {
                val d = parseInt16(v) / 10.0
                lastRollDeg = d
                telemetry.rollDeg = d
            }
            else -> {}
        }
        onTelemetryUpdate?.invoke()
    }

    private fun parseUint16(b: ByteArray): Int {
        if (b.size < 2) return 0
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun parseInt16(b: ByteArray): Int {
        val u = parseUint16(b)
        return if (u and 0x8000 != 0) u - 0x10000 else u
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
