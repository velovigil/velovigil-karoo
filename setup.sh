#!/usr/bin/env bash
# veloVigil — one-command Karoo setup
# Usage: curl -sL https://raw.githubusercontent.com/velovigil/velovigil-karoo/main/setup.sh | bash
# Or:    ./setup.sh [invite_code]

set -euo pipefail

REPO="velovigil/velovigil-karoo"
API="https://api.velovigil.com/api/v1"
PKG="com.velovigil.karoo"

echo ""
echo "  veloVigil — Your ride. Your data. Your AI."
echo "  ─────────────────────────────────────────────"
echo ""

# Check ADB
if ! command -v adb &>/dev/null; then
  echo "ERROR: adb not found. Install Android platform-tools first."
  echo "  macOS:   brew install android-platform-tools"
  echo "  Linux:   sudo apt install adb"
  echo "  Windows: https://developer.android.com/tools/releases/platform-tools"
  exit 1
fi

# Check Karoo connected
DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
  echo "ERROR: No device found. Plug in your Karoo and enable USB debugging."
  echo "  Karoo: Settings > System > Developer Options > USB Debugging"
  exit 1
fi
echo "  Found device: $DEVICE"

# Get invite code
INVITE_CODE="${1:-}"
if [ -z "$INVITE_CODE" ]; then
  echo ""
  read -p "  Enter your invite code: " INVITE_CODE
fi

if [[ ! "$INVITE_CODE" =~ ^vv_invite_ ]]; then
  echo "ERROR: Invalid invite code. Should start with vv_invite_"
  exit 1
fi

# Download latest APK
echo ""
echo "  Downloading latest veloVigil..."
APK_URL=$(curl -sL "https://api.github.com/repos/$REPO/releases/latest" | grep "browser_download_url.*\.apk" | head -1 | cut -d '"' -f 4)
if [ -z "$APK_URL" ]; then
  echo "ERROR: Could not find APK in latest release."
  exit 1
fi
VERSION=$(echo "$APK_URL" | grep -oP 'v[\d.]+')
TMPAPK="/tmp/velovigil-${VERSION}.apk"
curl -sL -o "$TMPAPK" "$APK_URL"
echo "  Downloaded: $VERSION ($(du -h "$TMPAPK" | cut -f1))"

# Uninstall old version if present
echo "  Removing old version (if any)..."
adb uninstall "$PKG" 2>/dev/null || true

# Install
echo "  Installing on Karoo..."
adb install "$TMPAPK" 2>&1 | grep -q "Success" && echo "  Installed." || { echo "ERROR: Install failed."; exit 1; }

# Register with invite code
echo "  Registering with invite code..."
REGISTER_RESPONSE=$(curl -s -X POST "$API/register" \
  -H "Content-Type: application/json" \
  -d "{\"invite_code\": \"$INVITE_CODE\"}")

RIDER_ID=$(echo "$REGISTER_RESPONSE" | grep -oP '"rider_id"\s*:\s*"\K[^"]+')
API_KEY=$(echo "$REGISTER_RESPONSE" | grep -oP '"api_key"\s*:\s*"\K[^"]+')

if [ -z "$RIDER_ID" ] || [ -z "$API_KEY" ]; then
  ERROR=$(echo "$REGISTER_RESPONSE" | grep -oP '"error"\s*:\s*"\K[^"]+')
  echo "ERROR: Registration failed — ${ERROR:-unknown error}"
  echo "  The invite code may be invalid or already used."
  echo "  Open veloVigil on the Karoo and register manually."
  exit 1
fi

echo "  Registered!"
echo "    Rider ID: $RIDER_ID"
echo "    API Key:  ${API_KEY:0:12}..."

# Launch app and inject credentials via ADB text input
echo "  Configuring app..."
adb shell am start -n "$PKG/.SettingsActivity" 2>/dev/null
sleep 2

# Write credentials to a temp file on device, then use am broadcast or settings content provider
# Since SharedPreferences aren't directly writable on release builds,
# we'll write a config file the app can read on next launch
adb shell "mkdir -p /sdcard/velovigil" 2>/dev/null
adb shell "echo '{\"rider_id\":\"$RIDER_ID\",\"api_key\":\"$API_KEY\",\"endpoint\":\"$API/telemetry\"}' > /sdcard/velovigil/config.json"

echo ""
echo "  ─────────────────────────────────────────────"
echo "  SETUP COMPLETE"
echo ""
echo "  Your credentials have been saved to /sdcard/velovigil/config.json"
echo "  Open veloVigil on your Karoo — if credentials don't auto-load,"
echo "  enter them manually:"
echo ""
echo "    Rider ID: $RIDER_ID"
echo "    API Key:  $API_KEY"
echo ""
echo "  Pair your Polar H10, start a ride, and go."
echo "  Your ride. Your data. Your AI."
echo ""

# Cleanup
rm -f "$TMPAPK"
