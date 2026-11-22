#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# MyTV helper for a TV on your network. Reads TV_HOST/TV_ADB_PORT/JAVA_HOME from ../.env (git-ignored).
#   tools/tv.sh connect|build|install|launch|shot [file]|key KEYCODE...|uninstall|home-status
#   EDITION=play tools/tv.sh build|install   (default: github)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a
# adb: ADB=…, else the copy in .bin/platform-tools, else the one on the PATH.
if [ -z "${ADB:-}" ]; then
  ADB="$ROOT/.bin/platform-tools/adb"; [ -x "$ADB.exe" ] && ADB="$ADB.exe"; [ -x "$ADB" ] || ADB=adb
fi
DEV="${TV_HOST:?set TV_HOST in .env}:${TV_ADB_PORT:-5555}"
A() { "$ADB" -s "$DEV" "$@"; }
PKG=com.tinteractive.mytv
ED="${EDITION:-github}"; APK="$ROOT/android/app/build/outputs/apk/$ED/release/app-$ED-release.apk"
[ -f "$APK" ] || APK="${APK%.apk}-unsigned.apk"    # no signing key configured
case "${1:-}" in
  connect)  "$ADB" connect "$DEV"; "$ADB" devices -l ;;
  build)    (cd "$ROOT/android" && ./gradlew${OS:+.bat} --no-daemon -q ":app:assemble${ED^}Release") && ls -la "$ROOT/android/app/build/outputs/apk/$ED/release/" ;;
  install)  A install -r "$APK" ;;
  launch)   A shell am start -W -n $PKG/com.mytv.launcher.MainActivity ;;
  shot)     OUT="${2:-$ROOT/private/shots/screencap.png}"; mkdir -p "$(dirname "$OUT")"; A exec-out screencap -p > "$OUT" && echo "$OUT" ;;
  key)      shift; for k in "$@"; do A shell input keyevent "$k"; sleep 0.4; done ;;
  uninstall) A uninstall $PKG ;;
  home-status)
    A shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
    A shell settings get secure enabled_accessibility_services ;;
  *) sed -n '3,5p' "$0"; exit 1 ;;
esac
