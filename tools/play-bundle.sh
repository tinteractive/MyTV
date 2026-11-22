#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Build the Play Store edition of MyTV as an app bundle (dist/mytv-<version>-play.aab), for upload in Play Console.
#   tools/play-bundle.sh
# Same version and signing key as the GitHub release. Stops if the Play edition contains anything that belongs
# to the GitHub edition only (own updater, adb setup commands, install or secure-settings permissions).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a
VER=$(sed -n 's/.*val mytvVersion = "\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)
[ -n "$VER" ] || { echo "version not found"; exit 1; }

echo "Testing and building the Play edition of MyTV $VER…"
(cd "$ROOT/android" && ./gradlew${OS:+.bat} --no-daemon -q :app:testPlayDebugUnitTest :app:assemblePlayRelease :app:bundlePlayRelease)

# Check the matching APK (same manifest and code as the bundle).
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/android/local.properties" | tr -d '\\\r')
AAPT=$(ls -d "${ANDROID_HOME:-$SDK}"/build-tools/*/aapt2* | sort -V | tail -1)
PLAY_APK="$ROOT/android/app/build/outputs/apk/play/release/app-play-release.apk"
BAD=$("$AAPT" dump xmltree --file AndroidManifest.xml "$PLAY_APK" \
  | grep -o 'REQUEST_INSTALL_PACKAGES\|WRITE_SECURE_SETTINGS\|com.mytv.launcher.Provision\|InstallResult' | sort -u || true)
[ -z "$BAD" ] || { echo "Play edition contains GitHub-only parts: $BAD"; exit 1; }

mkdir -p "$ROOT/dist"
cp "$ROOT/android/app/build/outputs/bundle/playRelease/app-play-release.aab" "$ROOT/dist/mytv-$VER-play.aab"
echo "Ready: dist/mytv-$VER-play.aab (upload in Play Console; never to the GitHub release)"
