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
    scansDevices="false">
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

## Data Stream Subscriptions

**Don't use** generic `addConsumer<KarooEvent>` — Kotlin reflection not available, crashes.

**Do use** typed consumers with params:
```kotlin
karooSystem.addConsumer<OnStreamState>(
    OnStreamState.StartStreaming(DataType.Type.HEART_RATE),
) { event -> handleStreamState(event.state) }
```

Subscribe to each data type individually: HEART_RATE, SPEED, POWER, CADENCE, DISTANCE.

## Polar BLE SDK on Karoo

### What works
- `searchForDevice()` — finds H10 via BLE scan
- Scan returns: deviceId, name, MAC address, RSSI

### What doesn't work (as of 2026-03-28)
- `connectToDevice()` — H10 sends non-connectable BLE advertisements when Karoo has it paired via ANT+
- SDK logs: "Skipped connection attempt due to reason device is not in connectable advertisement or missing service"
- This is a firmware/protocol-level constraint, not a code bug

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
- H10 supports simultaneous ANT+ and BLE — but BLE advertisements may be non-connectable when ANT+ is active on same host device
- Device ID format: 8-char hex (e.g., D6EEFF27), printed on device back
- Rob's H10: ID=D6EEFF27, MAC=FC:FE:29:4F:9D:E1

### Open question
Can we force connectable BLE advertisements? Options to research:
1. Unpair H10 from Karoo's ANT+ and use BLE exclusively
2. Use Android's native BluetoothAdapter.startLeScan() to bypass Polar SDK's connectable check
3. Check if Polar SDK has a config to skip the connectable advertisement check
4. Use a phone as BLE bridge (H10 → phone → Karoo via WiFi)

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

# Logs
adb logcat -d | grep "veloVigil"

# Karoo info
adb shell getprop ro.build.version.sdk  # Returns 27
```

## Reference Extensions

Working extensions to study:
- `timklge/karoo-reminder` — simplest, clean manifest
- `timklge/karoo-headwind` — complex, multiple DataTypes, weather API
- `hammerheadnav/karoo-ext` — SDK source + sample extension
- `timklge/awesome-karoo` — community extension index

## Build

```bash
cd /root/projects/velovigil-karoo
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Updated: 2026-03-28
