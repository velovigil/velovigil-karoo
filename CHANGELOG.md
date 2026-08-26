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

## 0.3.1 — 2026-08-26 (pre-release: Onewheel on the Karoo, the Player Zero recipe)

- **Onewheel GT-S connector brought up to Player Zero's verified recipe** (`player-zero-app/captures/PROTOCOL.md`, SOLVED 2026-08-22): token write → FM's 20-frame post-unlock setup burst once (150 ms spacing) → notification subscriptions → re-poke the token every **6 s** (was 15 s, which let the board soft-relock and stream zeros). Battery-verify read now fires only after the token write, not after every setup frame.
- Board data fields unchanged: `board_battery`, `motor_temp`, `safety_headroom`, `board_amps`, `board_speed`; `board` section in every telemetry point (battery, motor temp, headroom, amps, rpm, pitch, roll). Board name + token are Settings fields; nothing board-specific is in the APK.
- Untested on a Karoo until Rob's board is next to it; same cert as 0.2.4.

## 0.3.0 — 2026-08-25 (pre-release: the DH view's data)

- **Per-feature events.** `GForceProcessor` records a `jump` event on every landing: hang time, landing g (peak filtered g in the 300 ms after touchdown), lateral g; and a `crash` event when the 4 g/200 ms detector fires. `TelemetryBuffer` drains them into each payload as `events:[{type,t_offset_ms,hang_ms,landing_g,lateral_g,g,duration_ms}]`.
- **Per-window peak g** (`gforce.window_peak`, reset on every upload) and `gforce.vibration` (road-roughness RMS). Payload carries `"schema":3`; the worker's DH view (`/dashboard/<rider>/dh/<session>`) renders both shapes and says which it has.
- **HRV artifact rejection.** RR outside 300–2000 ms or a beat-to-beat jump > 400 ms is dropped; a May 2026 ride stored RMSSD 14,079 ms at HR 83, which no heart can do.
- Same signing cert as 0.2.3/0.2.4. Pre-release until a real ride confirms events on the dashboard; the Extension Library manifest stays on 0.2.4 until then.

## 0.2.4 — 2026-08-25 (release: pending karoo-ext package token)

- **Runtime permission request in SettingsActivity** (commit ee0acc8, 2026-04-19, never released). On Karoo 2 (API 30) ACCESS_FINE_LOCATION, on Karoo 3 (API 31+) BLUETOOTH_SCAN + BLUETOOTH_CONNECT, are *dangerous* permissions; without the runtime grant the Polar H10 BLE scan never runs. Fleet D1 receipt for why this matters: rider `rider_ccadecvi13w4` has 9 sessions (2026-04-23..25), every one `avg_hr 0 / max_hr 0`, last live point `hr 0, lat 0, lon 0` from `device_id karoo2`. Distance populated, sensors never granted.
- `scripts/pre-beta.sh` smoke gate (S1). Run before any invite.
- Signer: `velovigil-release-v2.keystore`, alias `velovigil` — same cert as v0.2.3 (`FD:6D:FB:A1…6E:3A`), so this installs as an upgrade.
- Build note: `io.hammerhead:karoo-ext:1.1.8` is on GitHub Packages and needs a token with `read:packages` (`gpr.key` / `GITHUB_TOKEN`); the tokens on Lares as of 2026-08-25 return 401.

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
