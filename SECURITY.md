# Security policy

## Supported versions

Only the newest release of MyTV Launcher gets security fixes. Update through Settings → System → Updates, or install the newest
APK from the [Releases page](https://github.com/tinteractive/MyTV/releases/latest).

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue:

- Use GitHub's private report form: <https://github.com/tinteractive/MyTV/security/advisories/new>

Include, as far as you can:

- the MyTV version (Settings → System → About MyTV Launcher) and the edition (GitHub or Play Store);
- the TV or box and its Android version;
- what an attacker needs (same Wi-Fi network, another app on the TV, physical access) and what they gain;
- steps to reproduce, or a proof of concept.

You can expect a first reply within a week. MyTV is maintained by one person in spare time; a fix for a confirmed
problem is released as soon as it is ready, and the report is credited in the release notes unless you prefer otherwise.
Please give a reasonable time for a fix before publishing details.

## What MyTV exposes

This helps to judge whether something is a vulnerability.

- **Home-button helper** (accessibility service): receives only window-change events of the TV's own home screen and
  reads no screen content. It filters key presses all the time but acts on only three: the Sources button and the Apps
  button (Android's own codes or a button the viewer taught MyTV), and Home after the viewer opened the TV's own home
  screen from MyTV. While "Teach another button…" waits, it takes the next key that is not for moving around. Every
  other key is passed on unchanged; nothing is stored or sent. It also draws MyTV's Sources over another app (an
  accessibility overlay window).
- **Now playing** (notification access): used only to read what apps on the TV are playing (media sessions).
- **Phone page** (own photos, settings backup): a small web server on the home network, running only while its screen
  is open on the TV. It is protected by a one-time PIN and closes after five wrong PINs.
- **Updates** (GitHub edition): MyTV downloads `update.json` and the APK from this repository's releases over HTTPS,
  checks the size, the SHA-256, the package name and the signing key, and hands the file to Android's installer, which
  asks the viewer to confirm.
- **Setup commands** (GitHub edition): the `Provision` entry point can only be started by adb (it requires the `DUMP`
  permission).
- **The TV's own adb** (GitHub edition): used only for functions the viewer switches on, and only while network
  debugging is on.
- **Plex** (only when linked): the token is stored in MyTV's private storage on the TV. The server is found through
  plex.tv's list of its addresses, Plex's GDM search (UDP 32414) or a scan of the TV's /24 on port 32400, and is used
  only over https …plex.direct, never plain http or Plex's relay.
- **YouTube searches** (Continue watching): YouTube does not tell Android which video plays, so MyTV searches YouTube
  for the title and channel, over HTTPS. Thumbnails come from i.ytimg.com.
- **Devices on the home network** (speakers, Cast devices): MyTV talks to them over the local network only; their
  answers are treated as untrusted data.

Out of scope: problems that need a rooted TV, an attacker who already has adb access to the TV, or changes to the TV's
system software.
