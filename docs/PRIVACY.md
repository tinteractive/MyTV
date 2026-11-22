# Privacy and permissions

MyTV Launcher has no account, no tracking, no ads and no analytics. This page lists everything that leaves the TV,
and every permission with what it is used for. The source code is here to check every line.

## What leaves the TV

- **Weather:** your place (city level) goes to [Open-Meteo](https://open-meteo.com), or to
  [MET Norway](https://api.met.no) when Open-Meteo cannot be reached.
- **Approximate place,** in the welcome screen and the weather settings: your internet address is looked up once at
  [ipapi.co](https://ipapi.co) (city level).
- **Updates:** only when you check (or weekly, if you switch that on), from GitHub.
- **Background photos:** once a day MyTV reads the photo list from GitHub and downloads new photos. Nothing is sent.
- **Continue watching for YouTube:** YouTube does not tell Android which video plays, so MyTV looks up the title and
  channel on YouTube to find it again. Your watching history itself stays on the TV.
- **YouTube thumbnails:** the picture of a YouTube video in Continue watching comes from YouTube's picture server
  (i.ytimg.com).
- **Pictures that apps and speakers publish** (covers, posters) as https links are downloaded from where they point.
- **Plex** (only if you link it, Settings → Home screen → Continue watching → Plex; shown only when the Plex app is
  installed): you link it with a 4-character code at plex.tv/link. [plex.tv](https://plex.tv) is contacted to link, to
  find your server's addresses, to list your Plex Home users and to switch to the Plex user of a MyTV profile (with that
  user's PIN if it has one). MyTV finds the server among plex.tv's addresses, by Plex's own search on the home network
  (GDM, UDP port 32414) or by trying port 32400 on the devices of the TV's own part of the network (its /24). It talks
  to the server only over its secure address (https …plex.direct): never plain http, never Plex's relay. What you
  watch is read from your own Plex server at home; the Plex token is stored on the TV. Unlink: Settings → Home screen
  → Continue watching → Plex → *Unlink Plex*; MyTV then forgets the link.

Speakers, Cast devices and your own photos stay on your home network. Settings stay on the TV; a settings backup goes
only to your own phone, over your home network. With **Other TVs**, a TV you linked yourself (with a code shown on
the other TV) can fetch this TV's settings and own photos over your home network, encrypted with a key only the two TVs have. A TV
that is not linked with another one does not listen for this and does not announce itself on the network.

## Permissions

A home screen that takes over the Home button needs a few permissions that deserve an explanation.

| Permission | What MyTV does with it | What it cannot do |
|---|---|---|
| **Accessibility service** "MyTV Home button" | Notices when the TV's own home screen comes to the front and shows MyTV instead; opens MyTV's Sources when you press the remote's Sources button, also drawn over the app that is playing (an accessibility overlay window, so it keeps playing); opens MyTV's All apps for the Apps button; puts the TV in standby when the sleep timer ends; keeps the volume below the maximum during quiet hours; opens the start-up app when the TV is switched on. | It cannot read what is on the screen (`canRetrieveWindowContent="false"`) and only hears window events from the TV's own home screen app. It filters key presses all the time, but uses only three: the Sources button and the Apps button (Android's own codes, or a button you taught under Settings → Remote control; each can be set to "As the TV does"), and, after you open the TV's own home screen from MyTV, the next Home press. While "Teach another button…" waits, it takes the next key that is not for moving around. While the pairing-code box for Wireless debugging is on screen (only after you chose **Pair with a code**), it takes the number keys, arrows, OK and Back for that box. Every other key is passed on unchanged; nothing is stored or sent. See [home_watch.xml](../android/app/src/main/res/xml/home_watch.xml). |
| **Notification access** | Android shows what other apps play (title, play/pause) only to apps with this access. Used for Now playing and Continue watching. | MyTV reads no notifications. |
| **WRITE_SECURE_SETTINGS** (optional) | Switches MyTV's own Home-button helper and notification access on, sets MyTV as the screensaver and its start time (and puts that time back when the TV resets it to "never"), and switches network debugging off when you ask. | Android does not give this to ordinary apps: you grant it yourself, once, and it disappears when MyTV is uninstalled. |
| **Network debugging / Wireless debugging** (optional) | Clean up, switching the TV's own home screen off and on, and installing an update you chose without Android's question talk to the TV's own debugging connection. | Nothing leaves the TV; the functions that need it are hidden while network debugging is off. An update goes this way only when you allowed MyTV once before; it never asks the TV by itself. Wireless debugging: MyTV pairs once with the code you type; its key stays in the Android Keystore, and it looks up the TV's port on this TV only. |
| **Install unknown apps** (optional) | Installing an update you chose; Android asks you to confirm, except on Android 12 and later once MyTV installed itself before (**Update without asking**, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`). | MyTV installs nothing else, only after you chose Install, and only a verified release of itself. |
| **TV listings** | Reads the "continue watching" row that apps publish, and the TV's inputs for Sources. | |
| **Modify system settings** (optional) | Sets the screensaver's start time when network debugging is not available; you allow it once in Android's own screen, on TVs that have it. | It changes no other setting. |
| **Bluetooth** | Connects and disconnects paired headphones and speakers, pairs new ones, and reads their battery level for the low-battery notice. | |

Security problems: please report them privately, see [SECURITY.md](../SECURITY.md).
