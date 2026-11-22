# Development

MyTV Launcher is a native Android app in Kotlin with Jetpack Compose (foundation only: no Material and no TV library, to keep
the APK small and the home screen fast on older TVs). How it is put together: [ARCHITECTURE.md](ARCHITECTURE.md).

- **Application id:** `com.tinteractive.mytv`. The code package is `com.mytv.launcher`.
- **SDK levels:** minSdk 26 (Android 8.0), targetSdk 36, compileSdk 37.
- **Developed and tested on:** a Philips 2021/22 UHD Android TV (Android 11, 32-bit, 1920×1080 at density 320).

## Layout of the repository

| Path | What |
|---|---|
| `android/app` | The app. Shared code in `src/main`, GitHub-only code in `src/github`, Play Store stand-ins in `src/play`, unit tests in `src/test` and `src/testGithub` |
| `photos/` | The online photo set: `photos.json` and the photos MyTV downloads |
| `tools/strings.py`, `tools/strings/` | All texts in every language; writes the `strings.xml` files |
| `tools/check_subtitles.py` | Checks that every setting's explanation fits on one line |
| `tools/photos.py` | Add, check and publish photos of the online photo set |
| `tools/tv.sh` | Build, install, launch, screenshot and key helper for a TV on your network (reads `.env`) |
| `tools/release.sh`, `tools/play-bundle.sh`, `tools/releases.json` | Publishing a release and its notes; maintainer only |
| `install/` | The installers for Windows (`windows.ps1`) and macOS / Linux (`install.sh`) |
| `docs/` | This file, architecture, installing (`INSTALL.md`), privacy, features, recovery, credits, the install page (`index.html`) and `screenshots/` |
| `AGENTS.md` | Instructions for AI coding agents that install MyTV or work on the code |
| `.github/` | CI workflow, Dependabot, issue forms, pull request template |

## Build and check

You need JDK 17 or newer and the Android SDK (platform 37). Put the SDK path in `android/local.properties`
(`sdk.dir=…`) or set `ANDROID_HOME`. The Gradle wrapper and plugin versions are pinned in the project.

```
cd android
./gradlew :app:assembleGithubDebug        # debug build; no signing key needed
./gradlew :app:assembleGithubRelease      # release build (R8); unsigned without a key

# what CI runs on every pull request
./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
./gradlew :app:lintGithubDebug :app:lintPlayDebug
./gradlew detekt
```

- **Tests** are JVM unit tests (no device): parsing, validation and time rules, a check that every text exists in
  every language, and `HomeStateTest`, which runs the home screen's rules (overlay stack and focus, "who is
  watching?", PIN, profiles) on a simulated Android 11 with Robolectric. Add one for new logic that can be tested
  without a device.
- **Lint** fails on errors; deliberate exceptions are in `android/app/lint.xml`, each with its reason.
- **detekt** fails on new findings; the ones that existed when it was added are in `android/app/detekt-baseline.xml`.
- **Dependency verification:** every library and plugin Gradle downloads must match its SHA-256 in
  `android/gradle/verification-metadata.xml`, so a swapped file on a repository fails the build. After changing a
  version (also in a Dependabot pull request, which does not do this itself), write the checksums again and review
  the diff:
  ```
  ./gradlew --rerun-tasks --write-verification-metadata sha256 :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest     :app:lintGithubDebug :app:lintPlayDebug detekt :app:assembleGithubRelease :app:assemblePlayRelease
  ```
  Gradle only writes what your own computer downloads: after a new Android Gradle plugin, also add the `aapt2` jars
  of the other operating systems (`-linux`, `-osx`, `-windows`; SHA-256 of the files on dl.google.com).
  Fix a finding instead of adding it to the baseline.

## Two editions

Both come from one code base, with the same application id and signing key:

- **`github`**: everything, including the self-updater, the TV's own adb (Clean up, switching the TV's own home screen
  off), `WRITE_SECURE_SETTINGS` and the adb setup commands (`Provision`).
- **`play`**: the same app without those. `src/play` holds small stand-ins with the same names, so shared code compiles
  and the menus hide what is not there.

A change in `src/main` must compile in both. Use `EDITION=play tools/tv.sh build` (or `:app:assemblePlayRelease`) for
the Play edition.

## Signing and your own builds

A release build is signed only when a key is configured: either `private/keys/signing.properties` (git-ignored) with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`, or the `MTV_STORE_FILE`, `MTV_STORE_PASSWORD`,
`MTV_KEY_ALIAS` and `MTV_KEY_PASSWORD` environment variables. Create a key once and keep it; every later update must
be signed with the same one:

```
keytool -genkeypair -keystore private/keys/mytv-release.jks -storetype PKCS12 -alias mytv \
  -keyalg RSA -keysize 3072 -validity 10000
```

A build with your own key cannot update an installed official MyTV (Android refuses a different key): uninstall that
first.

The self-updater reads releases of the repository in `UPDATE_REPO` (default `tinteractive/MyTV`). For a fork, build
with `-PupdateRepo=owner/name`, or `-PupdateRepo=` to switch updates off.

## Running on a TV

1. On the TV: **Settings → Device preferences → About → Build**, press OK seven times; then **Developer options →
   USB debugging** and, if present, **Network debugging**.
2. On the computer: `cp .env.example .env`, set `TV_HOST` (the TV's address) and `JAVA_HOME`. `adb` is taken from
   `.bin/platform-tools/adb` when present, otherwise from your `PATH` (`ADB=…` overrides).
3. `tools/tv.sh connect`, and accept **"Allow USB debugging?"** on the TV.

```
tools/tv.sh build        # release build of the GitHub edition
tools/tv.sh install
tools/tv.sh launch
tools/tv.sh shot         # screenshot to private/shots/screencap.png
tools/tv.sh key DPAD_DOWN DPAD_CENTER
```

Do not `am force-stop` MyTV while testing: Android then also switches off its accessibility service (the Home-button
helper), and it has to be switched on again by hand.

### Setup commands (GitHub edition)

MyTV's `Provision` entry point takes setup commands. Only adb may start it (it needs the `DUMP` permission, which the
adb shell has and apps do not), so other apps on the TV cannot send them.

```
P="adb shell am start -n com.tinteractive.mytv/com.mytv.launcher.Provision"
$P --es mtv_cities "Lisbon"               # weather place (city level); several: "Lisbon;Porto"
$P --es mtv_bg coast                      # background photo
$P --es mtv_autostart <package|none>      # start-up app
$P --es mtv_dock_order "pkg1,pkg2"        # dock order
$P --ez mtv_welcome true                  # show the welcome screen
$P --es mtv_language en                   # any of the 34 language tags, or auto
$P --es mtv_demo 'pkg1,pkg2|City'         # screenshots: see below
$P --es mtv_demo 'pkg1,pkg2|City|cards'   # the same, with example cards
```

**Screenshots for the project page** must not show anyone's own apps, photos or place. `mtv_demo` shows only the
given apps in the dock and that city's weather, without Now playing and Continue watching content. With `|cards` it
shows example Now playing and Continue watching cards instead: Blender Studio films, with pictures from Wikimedia
Commons (credited in [CREDITS.md](CREDITS.md)). It changes nothing on the TV: only what MyTV shows until it restarts.

## Texts

The source texts are in one table in `tools/strings.py` (English, Dutch, French, German, Spanish and Portuguese);
every other language is one file in `tools/strings/<language tag>.json` with the same keys. `python tools/strings.py`
checks that every language has every text with the same placeholders, and writes
`android/app/src/main/res/values*/strings.xml`. A new language also needs its tag and name in `LANGUAGES` and
`LANGUAGE_NAMES` (`ui/Format.kt`). Commit both. A setting's explanation must fit on one line on a
1080p TV, in every language (`python tools/check_subtitles.py`, needs Pillow).

## Photos

`tools/photos.py` (needs Pillow) fetches a Wikimedia Commons photo with its author and licence, crops it to 16:9
around the subject and adds it to `photos/photos.json`; `check` writes previews with the home screen's fixed parts
drawn over them, to see that the subject stays clear of the clock, the weather, the buttons and the dock. Only photos
with an open licence. Publishing the set is for the maintainer.

## Private files

Machine-specific data (the TV's address, the signing key, screenshots, logs) belongs in `private/` and `.env`, which
are git-ignored. Never commit them.
