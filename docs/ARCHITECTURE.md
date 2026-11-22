# Architecture

MyTV Launcher is one Android app without a server: everything runs on the TV. It needs the internet only for the
weather (and finding your place once), the online photo set, finding YouTube videos for Continue watching and their
thumbnails, pictures that apps publish as links, Plex (only when linked) and (optionally) updates; the clock, the dock
and the bundled photos work offline. See [PRIVACY.md](PRIVACY.md).

```
android/app/src/main/kotlin/com/mytv/launcher/
  MainActivity      the home screen activity (HOME / LEANBACK_LAUNCHER); polls and refreshes only while in front
  HomeState         all state of the home screen and its sheets (Compose state), actions, overlay stack
  HomeWatch         accessibility service "Home button": brings MyTV back when the TV's own home screen appears;
                    the Sources and Apps buttons; also start-up app (PowerOn), sleep timer and quiet hours, because it
                    lives as long as the TV is on
  SourcesOverlay    MyTV's Sources drawn by HomeWatch over another app (accessibility overlay), so it keeps playing
  SourcesPopup      the same as a screen of its own, when the overlay cannot be shown
  QuietHours        a maximum volume in the evening (TV speakers, or a Sonos soundbar on HDMI)
  SleepTimer        standby after the chosen time, with a countdown in the last minute
  CrashGuard        two failed starts in three minutes: the Home-button helper stands aside for five minutes
  MyTvDream         the screensaver (DreamService)
  ui/               Compose screens: HomeScreen, Overlays (sheets and menus), SettingsScreen, Glass, Icons, Format
  data/             everything that talks to Android or the network (see below)
android/app/src/github/   GitHub edition only: Updates (self-updater), LocalAdb (the TV's own adb), Provision (adb setup)
android/app/src/play/     Play Store edition: stand-ins with the same names that do nothing
```

## Data sources (`data/`)

| File | What | Talks to |
|---|---|---|
| `Apps` | Installed, launchable apps; banners and icons; dock order | PackageManager |
| `Weather`, `MetNorway`, `Moon` | Forecast, backup source, sun and moon calculation | Open-Meteo, MET Norway (HTTPS) |
| `Backgrounds`, `OnlinePhotos` | Bundled photos, the online photo set, own photos | GitHub raw (HTTPS), private storage |
| `PhotoInbox`, `Backup` | The phone page (own photos, settings backup) | A small HTTP server on the home network, PIN-protected, only while its sheet is open |
| `TvLink` | Other TVs: take over all settings of a linked TV | `_mytv._tcp` on the home network; linked once with a code (ECDH), then one JSON line each way, answers sealed with AES-GCM; listens only while linked or while its sheet is open; a following TV compares a fingerprint ("version") when MyTV comes to the front |
| `Sonos`, `Cast`, `Speakers`, `Lan` | "Now playing" on speakers | mDNS/SSDP discovery; plain HTTP or TLS to devices on the home network only |
| `MediaApps`, `History`, `WatchNext` | "Now playing" and "Continue watching" for apps on the TV; `History` looks up YouTube videos | Media sessions (notification access), Android's Watch Next row; YouTube search and i.ytimg.com (HTTPS) |
| `Plex` | "Continue watching" from the viewer's own Plex server, Plex Home users per profile | plex.tv (linking, server addresses, users); the server only over https …plex.direct |
| `Sources`, `Bluetooth`, `Screensaver`, `SystemAccess`, `Cleanup` | Inputs, paired devices and their battery levels, system settings | Android system services |
| `Net` | Shared helpers: capped reads, sampled image decoding, XML snippets, home-network checks | |
| `UpdateUi` | What the Updates page shows (the updater itself is `Updates`, GitHub edition) | |
| `Prefs` | All settings | SharedPreferences (private) |

Rules that keep it safe and fast:

- Answers from the network and from other apps are untrusted: sizes are capped, images are decoded sampled, XML is read
  with regular expressions (no parser, so no external entities), URLs from devices must stay on the home network.
- Nothing blocks the main thread: I/O and decoding run on `Dispatchers.IO`/`Default`; the first frame needs no network.
- The home screen must survive anything: calls into Android and other apps are wrapped, stored settings are read
  fault-tolerantly, and a crash loop makes the Home-button helper stand aside (`CrashGuard`).
- While another app is in front MyTV does nothing: discovery stops in `onStop`, polling is tied to the lifecycle, and
  caches are released on `onTrimMemory`.

## User interface

- **Plain Compose foundation** (no Material, no TV library): small APK, fast start on older TVs.
- **Frosted glass:** Android 11 has no UI blur. Each photo is downscaled to 240×135 and box-blurred once; glass panels
  draw that copy position-aligned, with a tint that follows the photo's brightness. "Simple" glass uses a tint only;
  it is the default on slow TVs (Android's low-RAM flag, or 1.5 GB of memory or less) until the viewer chooses.
- **Remote input:** `tvClick` fires on key-up only when the same element saw the key-down, so the OK press that opened a
  sheet never clicks the newly focused item; long-press OK or Menu opens an item's menu. `OkPress` (`ui/Glass.kt`)
  recognises a held OK after about 600 ms, whatever the remote sends (repeat marks, unmarked repeats or none).
- **Focus:** every focusable item registers under a key (`focusKey`). Sheets form a stack; each entry remembers the key
  of what opened it, and Back pops the stack and restores focus. `pendingFocus` asks for focus on a key once it exists.
- **Right-to-left:** the manifest sets `supportsRtl`, so Compose mirrors the layout for Arabic, Hebrew and Persian.

## Editions

`github` and `play` are Gradle flavors with the same application id and signing key. Shared code calls
`Updates`, `LocalAdb` and `Provision`; the `play` source set provides stand-ins, so the menus simply hide what is not
there. See [DEVELOPMENT.md](DEVELOPMENT.md).
