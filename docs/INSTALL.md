# Installing MyTV Launcher

MyTV Launcher runs on **Android TV and Google TV** with Android 8.0 or newer: TVs, boxes and sticks. It takes about five
minutes and you can remove it again at any time.

- [On the TV only](#on-the-tv-only-recommended) (recommended)
- [The Home button](#the-home-button)
- [With a computer](#with-a-computer)
- [Updates](#updates) · [Removing MyTV](#removing-mytv) · [If something goes wrong](#if-something-goes-wrong)
- [For advanced users](#for-advanced-users)

## On the TV only (recommended)

You need only the TV and its remote.

1. Open the **Google Play Store** on the TV, search for **Downloader** (by AFTVnews) and install it. Or install it from
   [its page on the Play Store website](https://play.google.com/store/apps/details?id=com.esaba.downloader), where you
   can choose your TV.
2. Open **Downloader**, choose the address field and type the code **3805347**, then **Go**.
3. The TV asks whether Downloader may install apps: choose **Settings**, switch **Downloader** on, and press **Back**.
4. Choose **Install**, then **Open**.

MyTV's welcome screen takes it from there: language, your town for the weather, the Home button, a start-up app,
Continue watching and profiles. Every step can be skipped and changed later in Settings.

> Google TV may say **"App blocked to protect your device"**. Choose **More details → Install anyway**. MyTV asks for
> two permissions that Google is careful about with apps from outside the Play Store (see
> [privacy](PRIVACY.md#permissions)).

## The Home button

On many TVs the Home button keeps opening the TV's own home screen. MyTV has a small helper for that, which brings
MyTV to the front instead. The welcome screen asks you to switch it on:

**Settings → Device preferences → Accessibility → MyTV Home button → On.**

The helper only notices which app comes to the front and reads nothing on the screen. Of the remote's keys it uses
only the Sources and Apps buttons, and Home after you opened the TV's own home screen; all other keys are passed on
unchanged. See [privacy](PRIVACY.md). On
TVs that let you choose the home screen, the welcome screen also offers **Make MyTV the default home**.

## With a computer

Handy when you set up a TV for someone else, or when Downloader is not available. The installer also gives MyTV one
extra permission, so it switches the Home-button helper on by itself.

**First, on the TV:**

1. Settings → Device preferences (or System) → About → press OK on **Build** seven times.
2. Settings → Device preferences → **Developer options** → switch on **USB debugging** (on newer Google TVs:
   **Wireless debugging**).
3. Keep the TV on. The installer looks for it on your network by itself; if it cannot find it, it asks for the TV's
   address (Settings → Network & Internet → your network, for example 192.168.1.23).

**Then, on a computer on the same network:**

| Computer | Run this |
|---|---|
| Windows | In PowerShell: `irm https://raw.githubusercontent.com/tinteractive/MyTV/main/install/windows.ps1 \| iex` |
| Mac or Linux | In a terminal: `curl -fsSL https://raw.githubusercontent.com/tinteractive/MyTV/main/install/install.sh \| bash` |

The installer finds the TV (if there are several devices, you choose from a numbered list), shows its model name, and
the TV asks **"Allow USB debugging?"**: choose **Always allow** and **OK**. With Wireless debugging you only type the
pairing code the TV shows. Nothing is installed on the computer. Afterwards you can switch debugging off again.

Using an AI coding agent such as Claude Code? Open this repository and say "install MyTV Launcher on my TV"; it follows
[AGENTS.md](../AGENTS.md).

## Updates

Settings → System → **Updates** → **Check now**, or switch on **Check automatically** (once a week). MyTV shows what is
new, in your language, and installs it when you choose **Install**. With network debugging on and MyTV allowed once,
that is the only press: MyTV installs the update through the TV's own debugging connection and opens again by itself.
On Android 12 and later the same happens without network debugging from the second update on. Otherwise Android asks
you to confirm. Downloader works too: the code always gives the newest version.

## Removing MyTV

Settings → Apps → **MyTV Launcher** → **Uninstall**. The TV's own home screen comes back by itself.

If you switched the TV's own home screen off, or switched apps off with Clean up, switch them back on in MyTV first
(Settings → System). Everything MyTV can change, and how to undo it by hand: [ROLLBACK.md](ROLLBACK.md).

## If something goes wrong

- **"App not installed":** the TV is usually short of space. Remove apps you don't use. Downloader keeps every file it
  downloaded: in Downloader, open **Files** and delete old MyTV-Launcher files, then type the code again.
- **"App blocked to protect your device":** choose **More details → Install anyway**, or pause **Scan apps with Play
  Protect** in the Play Store (Settings → Play Protect) while installing.
- **The Home button still opens the TV's own home screen:** switch on the helper (see [The Home button](#the-home-button)).
- **The computer installer says "No connection":** check the TV's address and that the computer is on the same network.
  If the TV never asks "Allow USB debugging?", go to the TV's home screen and wait 10 seconds, or switch USB debugging
  off and on again.

Still stuck? [Ask in an issue](https://github.com/tinteractive/MyTV/issues/new/choose). Does MyTV work on your TV,
box or stick? [Tell us](https://github.com/tinteractive/MyTV/issues/new?template=device_report.yml) (two minutes, also
when everything simply works).

## For advanced users

<details>
<summary>Installing by hand with adb</summary>

This is what the installers do, with Google's [platform tools](https://developer.android.com/tools/releases/platform-tools):

```
adb connect <tv-address>:5555
adb install -r MyTV-Launcher.apk          # https://github.com/tinteractive/MyTV/releases/latest/download/MyTV-Launcher.apk
adb shell pm grant com.tinteractive.mytv android.permission.WRITE_SECURE_SETTINGS
adb shell monkey -p com.tinteractive.mytv 1
```

The permission lets MyTV switch its Home-button helper and notification access on by itself and set the screensaver.
Without a computer, the same can be done from the TV: switch on network debugging and choose Settings → System →
**Set up extra permissions** in MyTV.
</details>

<details>
<summary>Extras that use network debugging</summary>

**Clean up** (switching pre-installed apps off) and switching the **TV's own home screen** off use the TV's own
debugging connection, and are only shown while **network debugging** is on in Developer options. The TV asks "Allow USB
debugging?" for MyTV once. With network debugging on, devices on your home network can ask to connect: only accept that
question for computers you know.
</details>
