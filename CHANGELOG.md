# velovigil-karoo CHANGELOG

Karoo 2/3 Android extension for veloVigil. Captures biometric telemetry (Polar H10 HRV, GPS, power, cadence), board data (Onewheel Polaris), runs on-device signal processing, posts to velovigil-fleet.

## Working reference

- **Build:** `./gradlew assembleRelease` (requires keystore per setup)
- **Keystore:** local, `velovigil-release.jks` — **NOT in repo**. Signing config in `app/build.gradle` reads from environment.
- **APK output:** `app/build/outputs/apk/release/app-release.apk`
- **Sideload:** via USB debugging to Karoo (Settings > System > Developer Options > USB Debugging), `adb install`
- **Telemetry endpoint:** baked into extension, configurable in Settings (defaults to production velovigil-fleet)
- **Rider API key storage:** Android SharedPreferences, masked as password-input in UI
- **GitHub releases:** https://github.com/velovigil/velovigil-karoo/releases
- **Public repo:** https://github.com/velovigil/velovigil-karoo

## Sensor stack

| Source | Integration | Notes |
|---|---|---|
| Polar H10 HR strap | `PolarConnector.kt` via Polar BLE SDK | RR intervals for HRV (RMSSD, SDNN, pNN50) |
| Onewheel (Polaris fw) | `OnewheelConnector.kt` direct BLE | Static-token + legacy MD5 auth paths |
| Karoo built-in GPS | `karoo-ext` SDK | Speed, distance, elevation |
| Karoo built-in power meter | `karoo-ext` SDK | If paired externally |

## 0.2.3 — 2026-04-18 (local commit, not released)

### Added
- **OnewheelConnector.kt** — direct BLE to Onewheel boards. Polaris static-token + legacy MD5 auth paths. 15s keep-alive, auto-reconnect. ~400 lines.
- **BoardDataTypes.kt** — typed board state (battery %, motor temp, safety headroom, current amps, rpm, pitch, roll, connected).
- **TelemetryBuffer board fields** — nested `board` object in JSON payload (raw_json blob extraction by Worker).
- **Inactivity watchdog** in VeloVigilExtension — RideState consumer via `addConsumer<RideState>`. 120s no motion + no board → IDLE. Fixes stuck-RECORDING case.
- **SettingsActivity fields:**
  - Polaris Token (40 hex chars) — `KEY_BOARD_TOKEN`
  - Board Name (e.g. `ow452500`) — `KEY_BOARD_NAME`
- **AndroidManifest** — `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` permissions for Android 12+ (Karoo 3 baseline)
- **docs/POLARIS_TOKEN_EXTRACTION.md** — btsnoop + ADB extraction guide
- **scripts/decode_polaris_token.py** — btsnoop decoder
- **setup.sh** + **SETUP.md** — quick-start

### Fixed
- RideState stuck at RECORDING after manual save (confirmed in D1 during validation run)
- Test-connection uses IDLE ride_state instead of invalid TEST (commit `390bf94`)

### Local commits (not pushed)
- `ed851bf` — feat: Onewheel integration + stuck-RECORDING watchdog + settings UI. 12 files, 1582 insertions.

## 0.2.2 — 2026-03-29 (GitHub release)

- Invite code field in Settings
- Connection testing + upload status reporting
- API key masked in UI (password input type)
- HTTPS enforcement on endpoint

## 0.2.0 — 2026-03-29 (first signed public release)

- Initial 6-class skeleton: VeloVigilExtension, HRVProcessor, PolarConnector, GForceProcessor, TelemetryBuffer, SettingsActivity
- RSA-2048 signed, v2+v3 schemes, 6.8 MB
- HR-derived G-force + cardiac drift + fatigue alert logic

## Validated state

- **Session 1 (2026-04-16 19:07-19:47 UTC):** 40 min, 2.36 km, avg HR 120, max HR 159, avg speed 10 kph, max 26.7 kph, HRV RMSSD avg 2.1, peak G 1.46. Clean capture confirmed real data was flowing — earlier "never clean data" framing was a dashboard-auth visibility bug, not a capture bug.
- **Board detection via Leroy's nRF52840 dongle:** `ow452500` at `60:B6:E1:B0:ED:2C` confirmed Polaris firmware (pre-auth reads return zeros). Polaris auth flow documented in docs/.

## Open decisions / notes

- **Unsigned debug APK in the wild** — new OnewheelConnector build is debug-signed. Need keystore-signed release build before v0.2.3 GitHub release.
- **Board voltage estimation** — OnewheelConnector uses fixed 63V nominal for power calc. Real pack voltage varies 50.4V–67.2V. Add BMS voltage characteristic if available for accurate watts.
- **Virtual sensor registration** — OnewheelConnector doesn't yet register as a Karoo virtual sensor via `scansDevices`. Currently shadow-feeds telemetry buffer; doesn't emit OnDataPoint for speed/power as native Karoo sensor source. Decide: keep shadow-feed or promote to visible virtual sensor.
- **GitHub PAT in local.properties** — not committed, gitignored, but live on disk. Needs rotation.
