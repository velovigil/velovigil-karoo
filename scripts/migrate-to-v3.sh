#!/usr/bin/env bash
# migrate-to-v3.sh — one-time cert migration: v2-signed installs → v3-signed APKs.
#
# WHY: the v2 release keystore was lost 2026-08-28 (re-clone dropped the untracked file).
# v3 (cert 63:E3:9D:05…5C:94) cannot update over a v2-signed install — Android requires
# uninstall/reinstall on a signing-cert change. This script does that, preserving what
# it can: it pulls external-files config before uninstall and pushes it back after.
#
# WHAT IT DOES (per app, only if the app is present on the device):
#   1. record installed versionName (receipt)
#   2. pull /sdcard/Android/data/<pkg>/files/ → ./migrate-backup-<serial>/<pkg>/
#   3. adb uninstall <pkg>          ← internal-storage config is LOST here; veloVigil
#      re-imports config.json from the files dir (0.3.2 feature), so push covers it
#   4. adb install -r <new apk>
#   5. push the backed-up files dir back
#   6. print dumpsys versionName as the completion receipt
#
# The H10 BLE bond is OS-level and SURVIVES uninstall — no re-pairing needed.
# Rider keys are in the pushed config; if veloVigil shows no rider after migration,
# re-import config via Settings (config.json is in the app files dir).
#
# USAGE (explicit serial REQUIRED — this never picks a device for you):
#   scripts/migrate-to-v3.sh <device-serial> <velovigil-apk> [player-zero-apk]
# Serials:  adb devices   (Rob's Karoo 2s: KAROO20ALC061506370, KAROO20ALC061503273)
set -euo pipefail

ADB="${ADB:-/mnt/c/platform-tools/adb.exe}"
SERIAL="${1:?usage: migrate-to-v3.sh <device-serial> <velovigil-apk> [player-zero-apk]}"
VV_APK="${2:?path to v3-signed veloVigil APK required}"
PZ_APK="${3:-}"

"$ADB" devices | grep -q "^$SERIAL[[:space:]]*device$" || { echo "device $SERIAL not connected/authorized" >&2; exit 2; }

BACKUP="./migrate-backup-$SERIAL"
mkdir -p "$BACKUP"

migrate() {
  local pkg="$1" apk="$2"
  if ! "$ADB" -s "$SERIAL" shell pm list packages | tr -d '\r' | grep -q "^package:$pkg$"; then
    echo "== $pkg not installed on $SERIAL — fresh install"
  else
    echo "== $pkg installed: $("$ADB" -s "$SERIAL" shell dumpsys package "$pkg" | grep versionName | head -1 | tr -d ' \r')"
    echo "== backing up files dir"
    "$ADB" -s "$SERIAL" pull "/sdcard/Android/data/$pkg/files" "$BACKUP/$pkg" 2>/dev/null || echo "   (no files dir or empty — continuing)"
    echo "== uninstalling $pkg"
    "$ADB" -s "$SERIAL" uninstall "$pkg"
  fi
  echo "== installing $(basename "$apk")"
  "$ADB" -s "$SERIAL" install -r "$apk"
  if [ -d "$BACKUP/$pkg" ]; then
    echo "== restoring files dir"
    "$ADB" -s "$SERIAL" push "$BACKUP/$pkg/." "/sdcard/Android/data/$pkg/files/" || echo "   push failed — restore config manually from $BACKUP/$pkg"
  fi
  echo "== RECEIPT: $("$ADB" -s "$SERIAL" shell dumpsys package "$pkg" | grep versionName | head -1 | tr -d ' \r')"
}

migrate com.velovigil.karoo "$VV_APK"
[ -n "$PZ_APK" ] && migrate com.northwoods.playerzero.karoo "$PZ_APK"

echo
echo "Done. Backups kept in $BACKUP (delete after a good ride)."
echo "H10 bond survives; open veloVigil Settings once to confirm rider config imported."
