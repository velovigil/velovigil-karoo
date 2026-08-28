# veloVigil

**Your ride. Your data. Your AI.**

Open-source Karoo 2/3 extension. It connects a **Polar H10** directly over Bluetooth, computes HRV on the Karoo from beat-to-beat RR intervals, records g-force from the H10's accelerometer, and sends ride telemetry to a Cloudflare backend you can read from your own Claude. We never analyze your rides.

Current release: **v0.2.4** (2026-08-25). Beta, invite-only.

## The strap already does this

| The H10 sends | Karoo shows | veloVigil reads |
|---|---|---|
| Heart rate | yes | yes |
| Beat-to-beat RR intervals | no | yes → HRV (RMSSD, SDNN, pNN50) computed on the Karoo |
| 3-axis accelerometer, 200 Hz | no | yes → filtered g-force, airborne time, on-device crash detector (no alert wired yet) |
| Single-lead ECG, 130 Hz | no | not yet |

## What it does today

- **Heart rate** from a Polar H10, shown on the Karoo as the sensor "veloVigil HR"
   - If **veloVigil HR** is not in the scan list, back out and scan again — the first scan after an install or reboot often does not show extension sensors; the second does.
   - Within ~30 s of pairing, swipe down from the top of the Karoo screen and tap **PAIR & CONNECT** on the Bluetooth pairing request. Once; the Karoo remembers it. Unanswered, the H10 link drops every ~37 s and HR reads 0.
- **HRV** — RMSSD, SDNN, pNN50, mean RR — computed on-device from H10 RR intervals
- **G-force** — filtered accelerometer (10-sample / 50 ms RMS window at 200 Hz); peak and average g, airborne time. A crash detector (>4 g sustained >200 ms) runs on-device and sets a flag; **no alert or upload is wired to it yet**
- **Karoo data** — speed, power, cadence, distance from the Karoo's own streams
- **Telemetry** every 5–10 s to the backend; a per-rider web dashboard with each ride's summary and a "Copy markdown" button
- **Post-ride coaching** — you paste that markdown into your own Claude and ask. Nothing is sent to us for analysis

## What it does not do (yet)

- Work with heart-rate straps other than the Polar H10. The extension connects only to Polar devices and does not read the Karoo's own HR sensor. Other straps still work with the Karoo as usual; veloVigil just won't record their HR
- Cardiac-drift, fatigue, or nutrition alerts. Those are questions you can ask Claude about a ride, not features in the extension
- Crash alerts or crash upload (detector only, see above)
- Data retention tiers, paid plans, FIT/GPX export, a companion phone app. None exist

## Install

Full guide with pictures: [velovigil.com/start](https://velovigil.com/start/)

1. Get an invite code (sign up at [velovigil.com/start](https://velovigil.com/start/); codes go out by email)
2. Download `velovigil-v0.2.4.apk` from [Releases](https://github.com/velovigil/velovigil-karoo/releases/latest) and sideload it:
   - **Karoo 2:** USB → the Karoo's `Download` folder → open the file → Install.
   - **Karoo 3:** the `Download` → open → Install path is blocked, and Hammerhead's Companion-app sideload currently errors ([#4](https://github.com/velovigil/velovigil-karoo/issues/4)). Use **`adb install velovigil-v0.2.4.apk`** from a computer with USB debugging on (Settings → System → Developer options → USB debugging). A one-tap store install is in progress with Hammerhead.
   - `adb install` works on both.
3. Open the veloVigil extension → **Settings**. **Allow the permission it asks for** (Karoo 2: Location; Karoo 3: Nearby devices + Location). Without it the H10 scan never runs and every ride records HR 0 — this was the April beta failure, fixed in 0.2.4
4. Paste your invite code → **Register with Invite Code**. Your **Rider ID** appears on that screen; you'll use it for your dashboard
5. If your H10 is paired to the Karoo directly, remove it from Settings → Sensors first. Then Settings → Sensors → Scan → pair **veloVigil HR**
6. Ride

## Coaching

After a ride: `https://velovigil-fleet.robert-chuvala.workers.dev/dashboard/<your Rider ID>` → open the ride → **Copy markdown** → paste into a fresh Claude conversation → "Coach me on this ride." The paste contains the whole summary; Claude does not need to fetch anything (its web tool cannot reach the API from claude.ai).

## Architecture

```
Polar H10 ──BLE──▶ Karoo 2/3: veloVigil ext ──POST 5–10 s──▶ Cloudflare Worker + D1
                    HR · HRV · g-force                          per-rider dashboard
                    + Karoo speed/power/cadence/distance        ride export (JSON / markdown)
                                                                        │ copy/paste
                                                                        ▼
                                                                  your Claude
```

Backend source (`velovigil-fleet`) is currently a private repo. The API is documented at [velovigil.com/docs](https://velovigil.com/docs/).

## Build from source

```bash
git clone https://github.com/velovigil/velovigil-karoo.git && cd velovigil-karoo
# karoo-ext is on GitHub Packages: needs a token with read:packages
export GITHUB_USER=<you> GITHUB_TOKEN=<token-with-read:packages>
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK 34, JDK 17, Gradle 8.7 (wrapper included). Release builds are signed with `apksigner` after `assembleRelease`.

## Tech

- `io.hammerhead:karoo-ext:1.1.8`
- `com.github.polarofficial:polar-ble-sdk:5.5.0` (HR, RR intervals, accelerometer)
- Cloudflare Workers + D1 backend; Cloudflare Pages site

## Links

- [velovigil.com/start](https://velovigil.com/start/) — the one path: sign up → install → permission → pair → ride → coach
- [Docs / API reference](https://velovigil.com/docs/)
- [KAROO_DEV_REFERENCE.md](KAROO_DEV_REFERENCE.md) — what we learned building on Karoo
- [CHANGELOG.md](CHANGELOG.md)

MIT License.
