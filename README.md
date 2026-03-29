# veloVigil

**Your ride. Your data. Your AI.**

Open source Karoo extension that captures HR, HRV, power, and crash detection from your Polar H10. Your own Claude analyzes the data. We never touch it.

## What It Does

- **Heart rate** from any Bluetooth HR strap, displayed as a native Karoo sensor
- **Real HRV** (RMSSD, SDNN, pNN50) from Polar H10 beat-to-beat RR intervals
- **Crash detection** from H10 3-axis accelerometer, filtered with 50ms sliding window RMS
- **Live telemetry** to a Cloudflare backend for dashboards and AI coaching
- **Post-ride AI debrief** from your own Claude instance

Works with any BLE heart rate strap. Polar H10 unlocks HRV and crash detection.

## Install

**Full install guide:** [velovigil.com/install](https://velovigil.com/install/)

Quick version:
1. Download the APK from [Releases](https://github.com/velovigil/velovigil-karoo/releases/latest)
2. Connect Karoo via USB, copy APK to the Download folder
3. On Karoo, open the file and tap Install
4. Go to Settings > Sensors > Scan — pair "veloVigil HR"
5. Ride

## AI Coaching

After your ride, point your Claude at [velovigil.com/setup](https://velovigil.com/setup/). It reads the page, understands the system, and becomes your cycling coach. No configuration.

## Strap Compatibility

| Feature | Any BLE HR strap | Polar H10 |
|---------|:---:|:---:|
| Heart rate on Karoo | Yes | Yes |
| HR zones and coaching | Yes | Yes |
| Cardiac drift detection | Yes | Yes |
| Live fleet dashboard | Yes | Yes |
| HRV (RMSSD, SDNN, pNN50) | - | Yes |
| Fatigue detection | - | Yes |
| Crash detection (g-force) | - | Yes |

## Architecture

```
Karoo 2/3                    Cloudflare
+-----------------+          +------------------+
| veloVigil ext   |  POST    | velovigil-fleet  |
| - Polar BLE SDK |--------->| - D1 database    |
| - HR provider   |  5-10s   | - Ride exports   |
| - HRV compute   |          | - Live dashboard |
| - G-force filter|          +--------+---------+
+-----------------+                   |
                                      | GET
                              +-------v---------+
                              | Your Claude      |
                              | - Ride analysis  |
                              | - Coaching       |
                              | - Athlete Model  |
                              +-----------------+
```

## Build From Source

```bash
git clone https://github.com/velovigil/velovigil-karoo.git
cd velovigil-karoo
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires: Android SDK, JDK 17+, Kotlin

## Tech Stack

- **Karoo SDK:** io.hammerhead:karoo-ext:1.1.8
- **Polar SDK:** com.polar:polar-ble-sdk (BLE HR, RR intervals, accelerometer)
- **Backend:** Cloudflare Workers + D1 ([velovigil-fleet](https://github.com/velovigil/velovigil-fleet))
- **Site:** Cloudflare Pages ([velovigil.com](https://velovigil.com))

## License

MIT

## Links

- [velovigil.com](https://velovigil.com) - Project site
- [Install guide](https://velovigil.com/install/) - Step-by-step for non-developers
- [AI coach setup](https://velovigil.com/setup/) - Connect your Claude
- [Fleet backend](https://github.com/velovigil/velovigil-fleet) - Cloudflare Worker source
- [Karoo dev reference](KAROO_DEV_REFERENCE.md) - Hard-won knowledge from building this
