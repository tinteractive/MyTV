#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# MyTV Launcher installer for macOS and Linux. Run in a terminal:
#   curl -fsSL https://raw.githubusercontent.com/tinteractive/MyTV/main/install/install.sh | bash
# It downloads Google's adb (into a temporary folder), finds the TV on the home network, installs the newest
# MyTV Launcher, gives it its extra permission and opens it. Nothing is installed on this computer.
set -euo pipefail
PKG=com.tinteractive.mytv
APK_URL=https://github.com/tinteractive/MyTV/releases/latest/download/MyTV-Launcher.apk
case "$(uname -s)" in
  Darwin) OS=darwin ;;
  Linux) OS=linux ;;
  *) echo "Use install/windows.ps1 on Windows."; exit 1 ;;
esac
WORK="${TMPDIR:-/tmp}/mytv-install"
ADB="$WORK/platform-tools/adb"

if [ -t 1 ]; then B=$'\e[1m'; C=$'\e[36m'; Y=$'\e[33m'; G=$'\e[32m'; N=$'\e[0m'; else B=; C=; Y=; G=; N=; fi
step() { printf '\n%s[%s/5] %s%s\n' "$C$B" "$1" "$2" "$N"; }
say()  { printf '      %s\n' "$*"; }
ask()  { local a; read -r -p "      $1 " a </dev/tty; printf '%s' "$a"; }
fail() { printf '\n%s%s%s\n' "$Y" "$1" "$N"; exit 1; }

printf '%sMyTV Launcher – installation%s\n' "$B" "$N"

step 1 "Prepare the TV"
say "On the TV, with the remote:"
say "  1. Settings > Device preferences (or System) > About > press OK on 'Build' seven times."
say "  2. Settings > Device preferences > Developer options > switch on 'USB debugging'"
say "     (newer Google TVs: 'Wireless debugging')."
say "Keep the TV on, on the same network as this computer."
ask "Press Enter when that is done." >/dev/null

step 2 "Getting Google's adb (temporary, nothing is installed)"
mkdir -p "$WORK"
if [ ! -x "$ADB" ]; then
  curl -fsSL -o "$WORK/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
  (cd "$WORK" && unzip -oq platform-tools.zip)
fi
"$ADB" start-server >/dev/null 2>&1 || true
say "Ready."

step 3 "Looking for the TV"
# TVs with Wireless debugging announce themselves on the network; adb lists them (address:port).
WIRELESS=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | sort -u || true)
PAIRING=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-pairing/ {print $NF}' | sort -u || true)
# TVs with network debugging often announce themselves too (_adb._tcp), also from another part of the network.
ANNOUNCED=$("$ADB" mdns services 2>/dev/null | awk '$2 == "_adb._tcp" {print $NF}' | sort -u || true)
# TVs with USB or network debugging listen on port 5555: look in this computer's part of the network (x.y.z.1-254).
if [ "$OS" = darwin ]; then
  ME=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
else
  ME=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
fi
FOUND=""
if [[ "$ME" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  NET=${ME%.*}; OUT="$WORK/scan.txt"; : > "$OUT"
  for i in $(seq 1 254); do
    ( exec 3<>"/dev/tcp/$NET.$i/5555" ) 2>/dev/null && echo "$NET.$i:5555" >> "$OUT" &
  done
  sleep 3; kill $(jobs -p) 2>/dev/null || true; wait 2>/dev/null || true
  FOUND=$(sort -u "$OUT")
fi
FOUND=$(printf '%s\n%s\n' "$ANNOUNCED" "$FOUND" | grep -v '^$' | sort -u || true)
CHOICES=()
for t in $WIRELESS; do CHOICES+=("$t wireless"); done
for t in $FOUND; do CHOICES+=("$t usb"); done
if [ ${#CHOICES[@]} -eq 0 ]; then
  say "No TV found by itself. Its address is under Settings > Network & Internet > your network."
  IP=$(ask "TV address (for example 192.168.1.23):")
  [[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || fail "That is not an address like 192.168.1.23."
  CHOICES=("$IP:5555 usb")
fi
if [ ${#CHOICES[@]} -eq 1 ]; then PICK=${CHOICES[0]}; say "Found: ${PICK% *}"
else
  say "Found these devices:"
  n=1; for c in "${CHOICES[@]}"; do say "  $n) ${c% *}$([ "${c#* }" = wireless ] && echo '  (wireless debugging)')"; n=$((n+1)); done
  k=$(ask "Which one is the TV? (number)")
  [[ "$k" =~ ^[0-9]+$ ]] && [ "$k" -ge 1 ] && [ "$k" -le ${#CHOICES[@]} ] || fail "Please run this again and type one of the numbers."
  PICK=${CHOICES[$((k-1))]}
fi
TARGET=${PICK% *}

step 4 "Connecting"
if [ "${PICK#* }" = wireless ]; then
  # A computer the TV does not know yet must pair once, with the code from the TV's own screen.
  if ! "$ADB" connect "$TARGET" 2>&1 | grep -q "connected to"; then
    say "On the TV: Wireless debugging > Pair device with pairing code."
    PAIR=$(printf '%s\n' "$PAIRING" | grep "^${TARGET%:*}:" | head -1 || true)
    [ -n "$PAIR" ] || PAIR="${TARGET%:*}:$(ask "Port shown on the pairing screen (after the colon):")"
    CODE=$(ask "Pairing code shown on the TV:")
    "$ADB" pair "$PAIR" "$CODE" >/dev/null || fail "Pairing did not work. Check the code, and run this again."
    "$ADB" connect "$TARGET" >/dev/null || true
  fi
else
  say "The TV asks 'Allow USB debugging?': choose 'Always allow from this computer' and OK."
  "$ADB" connect "$TARGET" >/dev/null || true
fi
for _ in $(seq 1 60); do
  [ "$("$ADB" -s "$TARGET" get-state 2>/dev/null || true)" = device ] && break
  sleep 1
done
[ "$("$ADB" -s "$TARGET" get-state 2>/dev/null || true)" = device ] || \
  fail "No connection. Check that debugging is on. If the TV never asked 'Allow USB debugging?': go to the TV's home screen, wait 10 seconds and run this again, or switch USB debugging off and on."
MODEL=$("$ADB" -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
say "Connected to ${MODEL:-the TV}."

step 5 "Installing MyTV Launcher"
curl -fsSL -o "$WORK/MyTV-Launcher.apk" "$APK_URL"
if ! OUT=$("$ADB" -s "$TARGET" install -r "$WORK/MyTV-Launcher.apk" 2>&1); then
  case "$OUT" in
    *INSUFFICIENT_STORAGE*) fail "The TV is short of space. Remove apps you don't use (or old downloads), then run this again." ;;
    *UPDATE_INCOMPATIBLE*|*SIGNATURE*) fail "A MyTV signed with another key is installed. Remove that first, then run this again." ;;
    *) fail "Installing failed: $OUT" ;;
  esac
fi
# The extra permission lets MyTV switch its Home-button helper on itself and manage its screensaver.
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
"$ADB" -s "$TARGET" shell am start -n "$PKG/com.mytv.launcher.MainActivity" >/dev/null
"$ADB" disconnect "$TARGET" >/dev/null 2>&1 || true
"$ADB" kill-server >/dev/null 2>&1 || true

printf '\n%sDone!%s MyTV Launcher is open on the TV; its welcome screen does the rest.\n' "$G$B" "$N"
say "You can switch debugging off again on the TV (Developer options)."
