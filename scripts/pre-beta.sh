#!/usr/bin/env bash
# pre-beta.sh — gate before any new invite goes out.
#
# Catches the failure modes that bit the 2026-04-18 beta cohort:
#   - APK URL 404 (site pointed at wrong filename)
#   - Runtime permission request missing from SettingsActivity
#   - Manifest permission set incomplete for both API 30 and 31+
#   - Version skew between gradle and site download URL
#   - Worker dashboard endpoint not live
#
# Exit 0 if all green. Any red aborts. Run before `gh release create`
# or site deploy that touches /install or /get-coaching.
#
# Usage:
#   ./scripts/pre-beta.sh
#
# "I am done rehearsing the final meal — we eat here, or not." — (adapted)

set -euo pipefail

# Colors only if terminal supports
if [[ -t 1 ]]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; RESET=$'\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; CYAN=''; RESET=''
fi

FAILED=0
WARNS=0
pass() { echo "  ${GREEN}✓${RESET} $*"; }
fail() { echo "  ${RED}✗${RESET} $*"; FAILED=$((FAILED + 1)); }
warn() { echo "  ${YELLOW}⚠${RESET} $*"; WARNS=$((WARNS + 1)); }
info() { echo "  ${CYAN}·${RESET} $*"; }

header() { echo; echo "${CYAN}── $* ──${RESET}"; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

header "1. Gradle version"
if ! GRADLE_VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2); then
  fail "Could not read versionName from app/build.gradle.kts"
else
  pass "versionName = $GRADLE_VERSION"
fi

header "2. Android manifest permissions (API 30 + API 31+)"
MANIFEST="app/src/main/AndroidManifest.xml"
for PERM in "android.permission.BLUETOOTH" "android.permission.BLUETOOTH_ADMIN" \
            "android.permission.BLUETOOTH_SCAN" "android.permission.BLUETOOTH_CONNECT" \
            "android.permission.ACCESS_FINE_LOCATION" "android.permission.INTERNET"; do
  if grep -q "$PERM" "$MANIFEST"; then
    pass "$PERM declared"
  else
    fail "$PERM MISSING in $MANIFEST"
  fi
done

# Legacy BLUETOOTH* should have maxSdkVersion=30 to avoid API-31+ warnings
for PERM in "BLUETOOTH\"" "BLUETOOTH_ADMIN\""; do
  if grep -E "$PERM.*maxSdkVersion=\"30\"" "$MANIFEST" > /dev/null; then
    pass "Legacy $PERM has maxSdkVersion=30 (scopes to API<=30)"
  else
    warn "Legacy $PERM missing maxSdkVersion=30 — won't fail build but cleaner"
  fi
done

header "3. Runtime permission code in SettingsActivity.kt"
SA="app/src/main/kotlin/com/velovigil/karoo/SettingsActivity.kt"
for PATTERN in "requestPermissions" "ActivityCompat" "ContextCompat" \
               "onRequestPermissionsResult" "requiredPermissions"; do
  if grep -q "$PATTERN" "$SA"; then
    pass "$PATTERN found"
  else
    fail "$PATTERN MISSING — runtime perm code may be incomplete"
  fi
done

header "4. APK release URL canary"
APK_URL="https://github.com/velovigil/velovigil-karoo/releases/latest/download/velovigil-v${GRADLE_VERSION}.apk"
info "Checking: $APK_URL"
HTTP_CODE=$(curl -sI -L -o /dev/null -w "%{http_code}" "$APK_URL" || echo "000")
if [[ "$HTTP_CODE" == "200" ]]; then
  pass "APK URL returns 200"
else
  fail "APK URL returns $HTTP_CODE — site/install.html will 404 when rider clicks Download"
  info "  Fix: cut GitHub release v${GRADLE_VERSION} with asset named velovigil-v${GRADLE_VERSION}.apk"
fi

header "5. Site install page version alignment"
SITE_INSTALL="/root/projects/velovigil-site/public/install/index.html"
if [[ -f "$SITE_INSTALL" ]]; then
  SITE_APK=$(grep -oE "velovigil-v[0-9]+\.[0-9]+\.[0-9]+\.apk" "$SITE_INSTALL" | head -1 | sed 's/velovigil-v\(.*\)\.apk/\1/')
  if [[ "$SITE_APK" == "$GRADLE_VERSION" ]]; then
    pass "Site /install/ points at v$SITE_APK (matches gradle)"
  else
    fail "Site /install/ points at v$SITE_APK, gradle is v$GRADLE_VERSION — bump site or build"
  fi
else
  warn "velovigil-site/public/install/index.html not found — skipping"
fi

header "6. Worker live endpoints"
WORKER="https://velovigil-fleet.robert-chuvala.workers.dev"

HEALTH=$(curl -sS -o /dev/null -w "%{http_code}" "$WORKER/api/v1/health" || echo "000")
if [[ "$HEALTH" == "200" ]]; then
  pass "Worker /api/v1/health live (200)"
else
  fail "Worker /api/v1/health returns $HEALTH"
fi

# Dashboard — test against a known rider ID
DASH_CODE=$(curl -sS -o /dev/null -w "%{http_code}" "$WORKER/dashboard/rider_36crc62yt3hi" || echo "000")
if [[ "$DASH_CODE" == "200" ]]; then
  pass "Worker /dashboard/{rider_id} live (200)"
else
  fail "Worker /dashboard/{rider_id} returns $DASH_CODE — Cluster-B fix not live"
fi

# Beacon endpoint (probe with throwaway code)
BEACON_RESP=$(curl -sS -X POST "$WORKER/api/v1/beacon" \
  -H "Content-Type: application/json" \
  --data '{"code":"pre_beta_smoke","rider_id":"pre_beta","app_version":"'"$GRADLE_VERSION"'","context":"pre-beta.sh canary"}' \
  -o /dev/null -w "%{http_code}" || echo "000")
if [[ "$BEACON_RESP" == "200" ]]; then
  pass "Worker /api/v1/beacon live (200)"
else
  warn "Worker /api/v1/beacon returns $BEACON_RESP — silent-error signal may not land"
fi

header "7. claude.ai sandbox canary (manual)"
info "S1 cannot automate this. Before sending invites, in a fresh claude.ai"
info "browser tab, paste:"
info "  $WORKER/api/v1/riders/rider_36crc62yt3hi/rides"
info "Ask: 'fetch this and show me the JSON.' If Claude declines with"
info "'blocked by network restrictions,' the /get-coaching dashboard-first"
info "flow is the only viable path — confirm site points there."

header "Summary"
if (( FAILED > 0 )); then
  echo "${RED}PRE-BETA CHECK FAILED: $FAILED failure(s), $WARNS warning(s).${RESET}"
  echo "${RED}Do NOT send invites until every ✗ is resolved.${RESET}"
  exit 1
fi
if (( WARNS > 0 )); then
  echo "${YELLOW}PASSED WITH WARNINGS: $WARNS warning(s).${RESET}"
  echo "${YELLOW}Review above before sending.${RESET}"
else
  echo "${GREEN}ALL GREEN.${RESET} Safe to proceed with invite sends."
fi
