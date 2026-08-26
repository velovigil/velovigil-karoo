#!/usr/bin/env bash
# release.sh — build, sign, align and verify a veloVigil release APK.
#
# ALWAYS cleans first. Kotlin constant-folds BuildConfig.VERSION_NAME into classes that read it;
# an incremental build shipped 0.3.1 with a "v0.2.4" banner (2026-08-26). `clean` is the fix.
#
# Needs:
#   ANDROID_HOME (default /opt/android-sdk), build-tools with zipalign + apksigner
#   GITHUB_TOKEN with read:packages for the karoo-ext GPR dependency (or `gh auth token`)
#   VELOVIGIL_KEYSTORE_PASS  (or ~/.claude/.secrets/velovigil-keystore-pass)
#
# Usage: scripts/release.sh            → app/build/outputs/apk/release/velovigil-v<version>.apk
#        OUT_DIR=/some/dir scripts/release.sh   also copies the APK there
set -euo pipefail
cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export GITHUB_USER="${GITHUB_USER:-NorthwoodsSentinel}"
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}"
[ -n "$GITHUB_TOKEN" ] || { echo "no GITHUB_TOKEN (read:packages) — karoo-ext dependency will 401" >&2; exit 2; }

KS="velovigil-release-v2.keystore"
ALIAS="velovigil"
PASS="${VELOVIGIL_KEYSTORE_PASS:-$(cat "$HOME/.claude/.secrets/velovigil-keystore-pass" 2>/dev/null || true)}"
[ -n "$PASS" ] || { echo "no keystore password (VELOVIGIL_KEYSTORE_PASS or ~/.claude/.secrets/velovigil-keystore-pass)" >&2; exit 2; }

VERSION=$(grep -E 'versionName = "' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
O=app/build/outputs/apk/release

echo "== clean + assembleRelease ($VERSION)"
./gradlew clean assembleRelease --no-daemon -q

echo "== zipalign"
"$BT/zipalign" -f -p 4 "$O/app-release-unsigned.apk" "$O/velovigil-v$VERSION-aligned.apk"

echo "== sign ($KS / $ALIAS)"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$O/velovigil-v$VERSION.apk" "$O/velovigil-v$VERSION-aligned.apk"

echo "== verify"
"$BT/apksigner" verify --print-certs "$O/velovigil-v$VERSION.apk" | grep -E "SHA-256|Verifies" || true
# The banner must carry the same version as the manifest — the exact defect this script exists for.
for d in $(unzip -l "$O/velovigil-v$VERSION.apk" | grep -o 'classes[0-9]*\.dex'); do
  unzip -p "$O/velovigil-v$VERSION.apk" "$d" | strings | grep -E "^v?0\.[0-9]+\.[0-9]+$" || true
done | sort -u | tr '\n' ' '; echo
if unzip -p "$O/velovigil-v$VERSION.apk" classes.dex | strings | grep -qE "^v0\.[0-9]+\.[0-9]+$" ; then
  BAD=$(for d in $(unzip -l "$O/velovigil-v$VERSION.apk" | grep -o 'classes[0-9]*\.dex'); do unzip -p "$O/velovigil-v$VERSION.apk" "$d" | strings | grep -E "^v0\.[0-9]+\.[0-9]+$"; done | grep -v "^v$VERSION$" || true)
  [ -z "$BAD" ] || { echo "STALE VERSION LITERAL IN APK: $BAD" >&2; exit 3; }
fi

ls -la "$O/velovigil-v$VERSION.apk"
if [ -n "${OUT_DIR:-}" ]; then cp "$O/velovigil-v$VERSION.apk" "$OUT_DIR/" && echo "copied to $OUT_DIR"; fi
