# Instructions for AI coding agents

This file is for an AI agent (Claude Code, Codex, Gemini CLI, …) that a person asks to **install MyTV Launcher on
their TV**, or to work on its code.

## Installing MyTV Launcher on a TV

Work with the person step by step. Change nothing on the TV before they have said yes to the plan.

1. **Ask** which TV or box it is (brand, model) and whether it is Android TV or Google TV. Explain that the TV needs
   developer options, and that MyTV can be removed again at any time ([docs/ROLLBACK.md](docs/ROLLBACK.md)).
2. **Let them prepare the TV** (they do this with the remote; you cannot):
   - Settings → Device preferences (or System) → About → press OK on **Build** seven times.
   - Developer options → **USB debugging** on, and **Network debugging** / **ADB over network** where it exists.
     Newer Google TVs (Android 12 and up) have **Wireless debugging** instead (it uses a pairing code); Google TVs
     with Android 11 only have USB debugging, which works over Wi-Fi on port 5555.
   - Only if the installer cannot find the TV: read its address from Settings → Network & Internet.
3. **Use the installer** in this folder rather than typing the steps yourself; it downloads Google's adb into a
   temporary folder, installs the newest release, grants the extra permission and opens the app:
   - Windows: `powershell -ExecutionPolicy Bypass -File install/windows.ps1`
   - macOS / Linux: `bash install/install.sh`
   It finds the TV on the network by itself (a numbered list when there are several) and asks for the address only
   when it cannot. With Wireless debugging it needs only the pairing code shown on the TV. The person must accept
   **"Allow USB debugging?"** on the TV. If you run adb yourself instead, these are the steps:
   ```
   adb connect <tv-address>:5555                     # or: adb pair … then adb connect … (wireless debugging)
   adb install -r MyTV-Launcher.apk                  # https://github.com/tinteractive/MyTV/releases/latest/download/MyTV-Launcher.apk
   adb shell pm grant com.tinteractive.mytv android.permission.WRITE_SECURE_SETTINGS
   adb shell am start -n com.tinteractive.mytv/com.mytv.launcher.MainActivity
   ```
4. **Hand over to the app.** MyTV's welcome screen asks for the language, the place for the weather, the Home
   button, a start-up app, Continue watching and profiles. With the permission from step 3 it switches its
   Home-button helper on by itself; on TVs that allow it, it can also become the default home screen. Let the person
   do this with the remote.
5. **Finish:** suggest switching network debugging off again in Developer options. Nothing else needs to stay on.

Rules while doing this:

- Never `am force-stop` MyTV: Android then also switches off its Home-button helper.
- Never disable or uninstall the TV's own home screen or other system apps. MyTV's own Clean up does that safely,
  with a way back, if the person wants it.
- Do not accept prompts on the TV for the person, and do not change TV settings they did not ask for.
- Do not copy the person's TV address, apps or photos into issues, logs or commits.

## Working on the code

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) first. In short:

- Build and check from `android/`: `./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
  :app:lintGithubDebug :app:lintPlayDebug detekt`. All must pass.
- Shared code is in `android/app/src/main` and must compile in both editions (`github` and `play`).
- Texts: change them in `tools/strings.py` and `tools/strings/<language>.json` (every language has every text) and
  run `python tools/strings.py`; never edit
  `strings.xml` by hand.
- Everything must work with a remote (arrows, OK, Back) and stay generic: look at what Android reports, not at a brand.
- Nothing new may be sent off the TV without discussion; no secrets in the code.
- Commits are signed off (`git commit -s`).
