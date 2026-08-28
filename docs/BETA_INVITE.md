# veloVigil beta invite — canonical email template

Source of truth for beta-invite emails. **Copy the version numbers and the install steps from here** so every invite points at the current release and the install path that actually works on the recipient's Karoo. Do not hand-edit version numbers per send — bump them here once per release (see checklist at the bottom).

**Current release:** v0.3.2 (versionCode 9). Ships the notification-shade pairing fix that keeps HR off zero.

> ⚠️ **Match the install path to the recipient's model.** Our instructions were originally written against a Karoo 2 (what we own) and silently failed on Karoo 3 (what most testers have) — see [issue #4](https://github.com/velovigil/velovigil-karoo/issues/4) and `KAROO_DEV_REFERENCE.md`.

---

## Template

> [Name] —
>
> You signed up on [date]. Here's your invite and everything you need to get riding.
>
> Your invite code: [vv_invite_...]
>
> **1. Install the extension.**
>
> - **Karoo 2:** download `velovigil-v0.3.2.apk` from https://github.com/velovigil/velovigil-karoo/releases/latest → USB it to the Karoo's `Download` folder → open the file → Install.
> - **Karoo 3:** the download → open → Install path is blocked on the Karoo 3, and the one-tap store install isn't live yet (verification with Hammerhead in progress). Until it is, install with adb from a computer:
>     1. Karoo: Settings → System → Developer options → turn on USB debugging (tap the build number 7× first if Developer options is hidden).
>     2. Plug the Karoo into the computer by USB and accept the "Allow USB debugging?" prompt on the Karoo.
>     3. Download the APK from the link above and run: `adb install velovigil-v0.3.2.apk` (adb is in Android "platform-tools", a small free download).
>
> **2. Grant the permission.** Open veloVigil → Settings and allow what it asks for (Karoo 2: Location; Karoo 3: Nearby devices + Location). Without it the H10 never connects and every ride records HR 0.
>
> **3. Accept the pairing request.** Within ~30 s of pairing, swipe down from the top of the Karoo screen and tap **PAIR & CONNECT** on the Bluetooth request. Once — the Karoo remembers it. Unanswered, the H10 link drops every ~37 s and HR reads 0. (This is the 0.3.2 fix.)
>
> **4. Register.** Paste your invite code → **Register with Invite Code**. Your Rider ID appears on that screen.
>
> **5. Pair the sensor.** If your H10 is paired to the Karoo directly, remove it (Settings → Sensors), then Settings → Sensors → Scan → pair **veloVigil HR**.
>
> **6. Ride.** Dashboard: `https://velovigil-fleet.robert-chuvala.workers.dev/dashboard/<your Rider ID>` → open the ride → **Copy markdown** → paste into your own Claude → "Coach me on this ride."
>
> Polar H10 only for now — HR + HRV + g-force. Free, open source, beta. Reply with a photo of the screen if anything sticks; that's the support channel and it's read.
>
> — Rob

---

## Per-release checklist (run this when you cut a new release)

1. Bump **Current release** above and every `velovigil-vX.Y.Z.apk` in the template.
2. Bump `README.md` ("Current release" line + install step).
3. Confirm the store manifest (`releases/latest/download/manifest.json`) reports the new `latestVersion` / `latestVersionCode`.
4. **Karoo 3 store install stays broken until the extension is Hammerhead-verified.** Keep the adb path in the Karoo 3 instructions until verification lands, then switch it to the one-tap store install and email the cohort.
