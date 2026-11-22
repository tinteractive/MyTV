#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Publish a MyTV Launcher release on GitHub (maintainer only): the GitHub edition's APK and update.json.
# The Play Store edition is built separately: tools/play-bundle.sh
#   tools/release.sh
# Before running: set mytvVersion (<year>.<month>.<build>) in android/app/build.gradle.kts and add that version at the
# top of tools/releases.json (notes per tag: new / improved / fixed / security, in all six languages). Needs the
# signing key (see docs/DEVELOPMENT.md) and the GitHub CLI logged in with the right to create release tags.
# Every release has one APK with a fixed name, MyTV-Launcher.apk: .../releases/latest/download/MyTV-Launcher.apk is
# always the newest (the Downloader code and the installers use it). The TV reads update.json from the newest release
# and downloads the APK from that release's own folder. dist/ keeps a local copy with the version in its name.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${MTV_UPDATE_REPO:-tinteractive/MyTV}"
GH="gh"; command -v gh >/dev/null 2>&1 || GH="/c/Program Files/GitHub CLI/gh.exe"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a

VER=$(sed -n 's/.*val mytvVersion = "\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)
[ -n "$VER" ] || { echo "version not found"; exit 1; }
CODE=$(echo "$VER" | awk -F. '{ printf "%d", $1*1000000 + $2*10000 + $3 }')
APK="MyTV-Launcher.apk"
if "$GH" release view "v$VER" --repo "$REPO" >/dev/null 2>&1; then
  echo "v$VER already exists on $REPO: raise mytvVersion (<year>.<month>.<build>) first"; exit 1
fi
PY="$ROOT/.venv/Scripts/python"; [ -x "$PY" ] || PY=python3
# The notes of the new version must exist in all six app languages.
"$PY" -c "
import json,sys
r=json.load(open(sys.argv[1],encoding='utf-8'))
assert r[0]['version']==sys.argv[2], 'tools/releases.json must start with '+sys.argv[2]
miss=[(n['en'],l) for n in r[0]['notes'] for l in ('en','nl','fr','de','es','pt') if not n.get(l)]
assert not miss, 'notes missing a language: %r' % miss[:3]
" "$ROOT/tools/releases.json" "$VER"

echo "Testing and building MyTV $VER ($CODE)…"
# Unit tests of both editions + the GitHub release build (includes lint's fatal checks).
(cd "$ROOT/android" && ./gradlew${OS:+.bat} --no-daemon -q :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest :app:assembleGithubRelease)
BUILT="$ROOT/android/app/build/outputs/apk/github/release/app-github-release.apk"
[ -f "$BUILT" ] || { echo "No signed APK: configure the signing key first (docs/DEVELOPMENT.md)"; exit 1; }

# Budgets (see ticket): stop before publishing anything that grew past them.
APK_MAX_KB=${APK_MAX_KB:-8192}
SIZE_KB=$(( $(stat -c %s "$BUILT") / 1024 ))
echo "APK: ${SIZE_KB} KB (budget ${APK_MAX_KB} KB)"
[ "$SIZE_KB" -le "$APK_MAX_KB" ] || { echo "APK over budget"; exit 1; }
# Memory, only when the TV is reachable over adb and MyTV runs there.
ADB="$ROOT/.bin/platform-tools/adb"; [ -x "$ADB.exe" ] && ADB="$ADB.exe"
if [ -n "${TV_HOST:-}" ] && "$ADB" -s "$TV_HOST:5555" get-state >/dev/null 2>&1; then
  PSS_MAX_KB=${PSS_MAX_KB:-102400}
  PSS=$("$ADB" -s "$TV_HOST:5555" shell dumpsys meminfo com.tinteractive.mytv | grep "TOTAL PSS" | tr -s " " | cut -d" " -f4 || true)
  [ -n "$PSS" ] && { echo "Memory on the TV (current version): ${PSS} KB PSS (budget ${PSS_MAX_KB} KB)"; [ "$PSS" -le "$PSS_MAX_KB" ] || echo "WARNING: memory over budget"; }
fi
mkdir -p "$ROOT/dist"
cp "$BUILT" "$ROOT/dist/$APK"
cp "$BUILT" "$ROOT/dist/mytv-$VER.apk"      # local archive only
# update.json: what the TV reads to decide (a few KB): newest version, file name, size, SHA-256 and all notes.
# Release text on GitHub: always English.
"$PY" - "$ROOT/tools/releases.json" "$ROOT/dist/$APK" "$VER" "$CODE" "$ROOT/dist/update.json" "$ROOT/dist/notes.md" <<'PY'
import json, sys, hashlib, os
rel, apk, ver, code, out, notes = sys.argv[1:7]
history = json.load(open(rel, encoding='utf-8'))
data = open(apk, 'rb').read()
json.dump({"version": ver, "code": int(code), "apk": os.path.basename(apk), "size": len(data),
           "sha256": hashlib.sha256(data).hexdigest(), "releases": history}, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
heads = {"new": "New", "improved": "Improved", "fixed": "Fixed", "security": "Security"}
cur = history[0]
md = [f"MyTV Launcher {ver} ({cur.get('date','')})", ""]
for tag, head in heads.items():
    items = [n["en"] for n in cur["notes"] if n.get("tag") == tag]
    if items: md += [f"### {head}"] + [f"- {i}" for i in items] + [""]
md += ["Install: see docs/INSTALL.md in the repository. The TV app checks `update.json` of the latest release (only when you ask, or weekly if switched on)."]
open(notes, 'w', encoding='utf-8').write("\n".join(md))
PY

ASSETS=("$ROOT/dist/$APK" "$ROOT/dist/update.json")
"$GH" release create "v$VER" "${ASSETS[@]}" --repo "$REPO" --title "MyTV Launcher $VER" --notes-file "$ROOT/dist/notes.md"
echo "Published v$VER ($CODE) to https://github.com/$REPO/releases"
