# Karoo Extension Development Reference

Hard-won knowledge from building veloVigil. Read this before touching any Karoo extension code.

## Karoo Environment

- **OS:** Android 8.1 (API 27)
- **SDK:** `io.hammerhead:karoo-ext:1.1.8`
- **Extension runs as:** Bound service (not Activity)
- **No foregroundServiceType** — API 29+ only, crashes on Karoo

## extension_info.xml (CRITICAL)

The Karoo AppStore service parses this at install. A missing required attribute crashes the **entire AppStore + Profile Configurator**, not just your extension.

**Required format** (from working extensions):
```xml
<ExtensionInfo
    displayName="@string/extension_name"
    icon="@drawable/your_icon"       <!-- REQUIRED — null crash without this -->
    id="your-extension-id"           <!-- Not extensionId -->
    scansDevices="true">             <!-- true = appears in Sensor Scan -->
    <DataType
        description="..."
        displayName="..."
        graphical="false"
        icon="@drawable/your_icon"   <!-- REQUIRED on each DataType too -->
        typeId="your_type_id" />     <!-- Not dataTypeId -->
</ExtensionInfo>
```

**Must be declared inside the `<service>` tag:**
```xml
<service android:name=".YourExtension" android:exported="true">
    <intent-filter>
        <action android:name="io.hammerhead.karooext.KAROO_EXTENSION" />
    </intent-filter>
    <meta-data
        android:name="io.hammerhead.karooext.EXTENSION_INFO"
        android:resource="@xml/extension_info" />
</service>
```

Note: `EXTENSION_INFO` and `MANIFEST_URL` are UPPERCASE.

## Virtual Sensor Device Registration (Phase 3)

Extensions can register as sensor sources by setting `scansDevices="true"` and overriding two methods:

### startScan — Device Discovery
```kotlin
override fun startScan(emitter: Emitter<Device>) {
    emitter.onNext(Device(
        extension,                              // extension ID
        "your-device-uid",                      // unique device UID
        listOf(DataType.Source.HEART_RATE),      // data types provided
        "Display Name",                         // shown in Sensor Scan
    ))
    emitter.setCancellable { /* cleanup */ }
}
```

### connectDevice — Pairing & Streaming
```kotlin
override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
    // Connection lifecycle
    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
    emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
    emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Mfg", "Model", "HW")))

    // Stream data points
    emitter.onNext(OnDataPoint(DataPoint(
        DataType.Source.HEART_RATE,
        mapOf(DataType.Field.HEART_RATE to hr.toDouble()),
        "your-device-uid",
    )))

    emitter.setCancellable { /* cleanup */ }
}
```

### Available DataType.Source constants
`HEART_RATE`, `POWER`, `CADENCE`, `SPEED`, `RADAR`, `SHIFTING_BATTERY`, `SHIFTING_FRONT_GEAR`, `SHIFTING_REAR_GEAR`

### Key DataType.Field constants
`HEART_RATE`, `POWER`, `SPEED`, `CADENCE`, `DISTANCE`, `SINGLE`, `LOC_LATITUDE`, `LOC_LONGITUDE`, `ELEVATION_GRADE`, `TEMPERATURE`

### Reference: lockevod/Karoo-Power_Extension
Best example of a virtual sensor. Registers as power meter via `scansDevices="true"`, appears in Sensor Scan with puzzle piece icon.

## Data Stream Subscriptions

**Don't use** generic `addConsumer<KarooEvent>` — Kotlin reflection not available, crashes.

**Do use** typed consumers with params:
```kotlin
karooSystem.addConsumer<OnStreamState>(
    OnStreamState.StartStreaming(DataType.Type.HEART_RATE),
) { event -> handleStreamState(event.state) }
```

Subscribe to each data type individually: SPEED, POWER, CADENCE, DISTANCE.
(HR now comes from our Polar BLE connection, not Karoo's sensor layer.)

## Polar BLE SDK on Karoo

### BLE Conflict (SOLVED)
- **Problem:** H10 supports one BLE connection. Karoo pairs H10 via ANT+ and BLE, taking the BLE slot. On reboot, Karoo reclaims BLE.
- **Solution:** Register veloVigil as Karoo's HR sensor via `scansDevices="true"`. User pairs veloVigil in Sensor Scan, removes H10 from Karoo's sensor list. veloVigil owns the BLE connection, feeds HR back to Karoo as native sensor data.
- **Verified:** Working on device. Karoo shows HR from veloVigil, H10 BLE free for RR intervals + accelerometer.

### What works
- `searchForDevice()` — finds H10 via BLE scan
- `connectToDevice()` — connects when H10 is NOT paired by Karoo (BLE slot free)
- `startHrStreaming()` — HR + RR intervals for HRV computation
- `startAccStreaming()` — 3-axis accelerometer at 200Hz
- `hrNotificationReceived` callback — fires alongside streaming (both paths deliver HR)

### Critical initialization
```kotlin
// MUST use applicationContext, not service context
val api = PolarBleApiDefaultImpl.defaultImplementation(
    context.applicationContext,  // NOT `this` or `context`
    setOf(FEATURE_HR, FEATURE_POLAR_ONLINE_STREAMING, FEATURE_DEVICE_INFO, FEATURE_BATTERY_INFO)
)
api.setApiLogger { msg -> Log.d(TAG, "SDK: $msg") }  // Essential for debugging
```

### Permissions (API 27)
Both required in manifest AND granted at runtime:
- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`

Grant via ADB: `adb shell pm grant com.velovigil.karoo android.permission.ACCESS_FINE_LOCATION`

### Known H10 BLE behavior
- H10 disconnects after 45s if removed from chest strap
- Device ID format: 8-char hex (e.g., D6EEFF27), printed on device back
- Rob's H10: ID=D6EEFF27, MAC=FC:FE:29:4F:9D:E1

### Accelerometer: "No factor found for type: ACC"
- This warning is cosmetic — the SDK still delivers values in mG
- Confirmed: raw values at rest show ~980-995 mG magnitude (correct 1g)
- The SDK can't find a formal calibration factor entry, but data is correctly scaled

### G-Force Filtering (CRITICAL for crash detection)
- **Raw single-sample peaks are unreliable.** A kitchen jump reads 7.64g raw due to strap bounce/vibration.
- **Filtered with 50ms sliding window RMS:** same jump reads 3.39g (actual body g-force).
- **Strap bounce amplifies ~2-3x.** The H10 on an elastic chest strap oscillates on impact.
- **Crash detection requires sustained force:** filtered g > 4g for > 200ms = crash candidate.
- **Reference:** MotoGP at 63° lean = 2.2g total. Hard cycling crash = 3-6g sustained.

## ADB / Sideloading

```bash
# Attach Karoo USB to WSL
usbipd.exe attach --wsl --busid 2-1

# Verify
adb devices

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Grant permissions
adb shell pm grant com.velovigil.karoo android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.velovigil.karoo android.permission.ACCESS_COARSE_LOCATION

# Force restart
adb shell am force-stop com.velovigil.karoo

# Logs (filter SDK noise)
adb logcat -d | rg "veloVigil" | rg -v "pmd data"

# Karoo info
adb shell getprop ro.build.version.sdk  # Returns 27
```

## Reference Extensions

Working extensions to study:
- `lockevod/Karoo-Power_Extension` — virtual sensor, `scansDevices="true"`, best example
- `timklge/karoo-headwind` — complex, 25 DataTypes, weather API, `scansDevices="false"`
- `timklge/karoo-reminder` — simplest, clean manifest
- `hammerheadnav/karoo-ext` — SDK source + sample extension
- `timklge/awesome-karoo` — community extension index

## Build

```bash
cd /root/projects/velovigil-karoo
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Current Status (Phase 3 — 2026-03-28)

### Working end-to-end
- HR from Polar H10 via BLE → Karoo display (as native HR sensor)
- RR intervals → HRV computation (RMSSD, SDNN, pNN50)
- 3-axis accelerometer → filtered g-force (50ms RMS window)
- Speed, power, cadence, distance from Karoo data streams
- Telemetry buffer → fleet backend POST every 5s
- Survives Karoo power cycle (extension auto-restarts)

### Not working yet
- GPS shows 0,0 — need to subscribe to location data type or use OnLocationChanged
- Speed shows 0 when stationary (expected) — verify on ride
- Crash detection logic implemented but untested on actual ride

Updated: 2026-03-28
