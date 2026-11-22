# Recovery: switching things back

MyTV Launcher is an ordinary app. It removes and replaces nothing on the TV; everything it switches can be switched back.

## Remove MyTV completely
On the TV: Settings → Apps → MyTV Launcher → Uninstall. Or from a computer:
```
adb uninstall com.tinteractive.mytv
```
The TV's own home screen and all apps are unaffected. If you switched the TV's own home screen off in MyTV, switch it
back on first (see below).

## Home button
How Home reaches MyTV depends on the TV:

- **MyTV is the default home** (TVs that allow it): undo in the TV's settings under Apps → Default apps → Home app.
- **Home-button helper**: on TVs that always open their own home screen, MyTV's accessibility service "MyTV Home
  button" (`HomeWatch`) notices that home screen coming to the front and opens MyTV instead. It receives only
  window-change events of that home screen, no screen content. It filters key presses but uses only the Sources and
  Apps buttons (opening MyTV's Sources and All apps) and, after you open the TV's own home screen from MyTV, the next
  Home press, to bring you back; every other key is passed on unchanged. It also shows MyTV's Sources over another
  app, ends the sleep timer, keeps quiet hours and opens the start-up app.
  - Off: TV settings → Device preferences → Accessibility → *MyTV Home button* → off. Or:
    ```
    adb shell settings put secure enabled_accessibility_services '""'
    ```
    (this switches off every accessibility service on the TV).
- **Sources and Apps buttons back to the TV:** MyTV → Settings → Remote control → *Sources button* (or *Apps
  button*) → *As the TV does*.
- Open the TV's own home screen once: MyTV → Settings → System → *Open the Google TV home screen* (the helper stands
  aside for 10 seconds).
- If MyTV fails to start twice within three minutes, the helper stands aside for five minutes, so the TV's own home
  screen stays usable.

`adb shell am force-stop com.tinteractive.mytv` also switches the helper off (Android does that for every app that is
force-stopped); switch it on again in the accessibility settings.

## Optional system access (GitHub edition)
- **WRITE_SECURE_SETTINGS** lets MyTV keep its Home-button helper on and manage its screensaver and "Now playing"
  access. Granted with `adb shell pm grant com.tinteractive.mytv android.permission.WRITE_SECURE_SETTINGS`, or from the
  TV with Settings → System → *Set up extra permissions*. Undo: `adb shell pm revoke com.tinteractive.mytv
  android.permission.WRITE_SECURE_SETTINGS`.
- **MyTV's own debugging connection** (Clean up, switching the TV's own home screen off): MyTV connects to the TV's
  own debug bridge on 127.0.0.1:5555 with a key kept in the Android Keystore. The TV asks once "Allow USB
  debugging?". Undo: Developer options → *Revoke USB debugging authorisations* (this also removes the approval of your
  computer), or switch network debugging off; the functions then disappear from MyTV.
- **The TV's own home screen off/on:** MyTV → Settings → System → *Google TV home screen*. By hand:
  `adb shell pm disable-user --user 0 com.google.android.tvlauncher` and `adb shell pm enable
  com.google.android.tvlauncher`.
  **Switch it back on before uninstalling MyTV**, otherwise Home shows only Android's empty fallback screen
  (fix: Settings → Apps → show system apps → Google TV Home → Enable, or the adb command above).
- **Clean up:** MyTV → Settings → System → Clean up only switches pre-installed apps off (`pm disable-user`); it never
  uninstalls. *Switch everything back on* re-enables all of them. By hand: `adb shell pm enable <package>`.

## Now playing: notification access
"Apps on this TV" (Settings → Home screen → Now playing) gives MyTV notification access. It is used only to read what
apps on the TV are playing (title, cover, play/pause); notifications themselves are not read or stored. Undo: switch
the source off in MyTV and remove the access in the TV's settings (Apps → Special app access → Notification access), or:
```
adb shell cmd notification disallow_listener com.tinteractive.mytv/com.mytv.launcher.data.MediaListener
```

## Modify system settings
On TVs that offer this switch, MyTV can get "Modify system settings" to set the screensaver's start time without
network debugging. Undo in Android's own screen for it (Apps → Special app access → Modify system settings → MyTV
Launcher → off), or uninstall MyTV.

## Plex
MyTV → Settings → Home screen → Continue watching → Plex → *Unlink Plex*. MyTV forgets the link and its Plex token.

## Debugging access for your computer
When you no longer need it: Developer options → *Revoke USB debugging authorisations*, and switch network debugging off.

## Stuck screen
Back closes any MyTV panel. Home always returns to the home screen.
