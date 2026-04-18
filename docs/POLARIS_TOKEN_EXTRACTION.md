# Polaris Token Extraction — GT-S Board

Goal: obtain the 20-byte static auth token that the Future Motion app writes to the Onewheel UART on connect. Once captured, this token unlocks your board's telemetry characteristics over BLE without your phone in the loop — making it usable from the Karoo extension, Leroy directly, or anywhere.

**One-time procedure. The token is board-specific and persists across firmware updates.**

## What you need

- Android phone with the Onewheel FM app installed and able to connect to your board
- USB cable + a laptop
- ADB installed (`platform-tools`), or a recent Android Studio
- Your board nearby, charged, not currently connected to any other BLE master

## Step 1 — Enable HCI snoop log on Android

1. On your Android phone: **Settings → About phone → tap "Build number" 7 times** (enables Developer options)
2. **Settings → System → Developer options**
3. Scroll to **"Enable Bluetooth HCI snoop log"** → toggle **ON**
4. Scroll to **"Bluetooth HCI snoop log"** (the detail pane) → ensure enabled
5. **Restart Bluetooth**: Settings → Connected devices → Bluetooth → toggle OFF, wait 3s, toggle ON
   *(the snoop log only starts capturing after a BT restart following enablement)*

## Step 2 — Capture an unlock

1. Close the FM app (force-stop it so the connection fully drops)
2. Open the FM app fresh
3. Let it discover and connect to your board — wait until battery % shows (that confirms unlock)
4. **Within 10 seconds of unlock**, stop the capture:
   - If you need a clean cut: back out of FM app, disable BT on phone
   - (the shorter the capture, the faster the decode)

## Step 3 — Pull the snoop log to your laptop

**Option A — Via ADB (preferred):**

```bash
# Connect phone via USB, enable USB debugging in Developer options
adb devices   # should show your phone

# Different Android vendors put the log in different paths. Try these in order:
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log ~/Downloads/btsnoop.log
adb pull /sdcard/Android/data/com.android.bluetooth/files/btsnoop_hci.log ~/Downloads/btsnoop.log
adb pull /sdcard/btsnoop_hci.log ~/Downloads/btsnoop.log

# If none worked, use the bug report method (always works):
adb bugreport ~/Downloads/bugreport.zip
# Inside the zip: FS/data/misc/bluetooth/logs/btsnoop_hci.log
```

**Option B — Via Android bug report (no-ADB-needed fallback):**

1. Dial **`*#*#284#*#*`** on your phone keypad (some OEMs; see alt below)
2. A bug report starts generating, then prompts to share/save
3. Save the zip, extract, find `btsnoop_hci.log` inside

**Option C — Manufacturer-specific:**
- Samsung: Settings → Developer options → "Take bug report" → share to Drive/email
- Pixel: Settings → System → Developer options → "Take bug report"

## Step 4 — Drop the log to Leroy's inbox and decode

```bash
# From your laptop, copy to Drive inbox or scp to Lares
# Then from Leroy:
cd /root/projects/velovigil-karoo/scripts
python3 decode_polaris_token.py ~/Downloads/btsnoop.log
```

Expected output:

```
[*] Parsing btsnoop: ~/Downloads/btsnoop.log
[*] Found Onewheel service: e659f300-ea98-11e3-ac10-0800200c9a66
[*] Found 2 GATT write packets to UART Write char (f3ff)
[*] First 20-byte write: 8b4f3ce9...  ← candidate unlock token
[✓] TOKEN (hex, 40 chars): 8b4f3ce9a2d7b14c5f8e0192af3c6d7b82e91405
[✓] Saved to: /root/.claude/.secrets/ow_token_ow452500
```

## Step 5 — Verify

From Leroy, re-run the probe script with the token:

```bash
python3 /tmp/ow_probe.py --token "$(cat /root/.claude/.secrets/ow_token_ow452500)"
```

Expected: battery %, motor temp, amps, headroom all return real values instead of `0000`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `btsnoop_hci.log` doesn't exist | BT not restarted after enable | Toggle BT off/on, repeat capture |
| Empty log file | Snoop enabled but filter blocks capture | Some OEMs require `adb shell svc bluetooth disable` / `enable` cycle |
| Token decode finds no writes to `f3ff` | FM app never actually reached unlock (board didn't respond) | Close FM app fully, reboot board, try again. Make sure board shows green ready-state before opening app |
| Multiple candidate tokens | FM app did multiple handshake attempts | First one after CCCD subscribe on `f3fe` is the real unlock write |

## Security note

This token is not a universal key — it's specific to your board's pairing identity. It's what the FM app has been using all along; you're just mirroring what it already knows. Compliance with the DMCA interoperability exemption (see `skills/Domain/Onewheel/SKILL.md` line 114).

Keep the token at `/root/.claude/.secrets/ow_token_ow452500` — gitignored, never commit.

## What happens after

Once the token is stored:

1. **Leroy can pull board telemetry any time** via `python3 /tmp/ow_probe.py` — authenticated read of battery, temp, amps, headroom, RPM, pitch, roll
2. **Karoo extension** reads the token from `SettingsActivity` field (pasted once) → `OnewheelConnector.kt` runs the static-token auth path → Karoo display fields show real board state + uploads to fleet worker
3. **Player Zero phone app** stores it in AsyncStorage keyed by board name → no re-capture needed on reinstalls

The token does not expire or rotate. Capture once, use forever (unless FM firmware changes the auth scheme — currently stable since Polaris fw 6215).
