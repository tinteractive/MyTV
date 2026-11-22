# Changelog

All notable changes to MyTV Launcher are listed here, newest first. Versions are `<year>.<month>.<build>`.
The notes the app itself shows (in six languages) are in [tools/releases.json](tools/releases.json).

## 2026.11.6

- **Updates with one button:** with network debugging on and MyTV allowed there once, Install is the
  only press. MyTV checks the download as before, installs it through the TV's own debugging connection and opens
  again by itself. On Android 12 and later Android's question is skipped too, from the second update MyTV installs
  itself. After an update the home screen says "Updated to …" once.
- **Network debugging:** when the TV never answers "Allow debugging?" (some TVs keep an earlier question open), MyTV
  says so and suggests restarting the TV, instead of "Debugging was not allowed".
- **Connection to the TV** (Settings → System, GitHub edition): says in one line why MyTV cannot use the TV's own
  debugging connection (developer options off, USB debugging off, only wireless debugging, port closed after a restart,
  no answer, not allowed) and what to do. **Check the connection** tries it once.
- **Wireless debugging** (Android 11 and later, GitHub edition): TVs without Network debugging can pair MyTV once
  with the six-digit code (Connection to the TV → Pair with a code). A box over the TV's pairing window takes the code
  from the remote (number keys, or arrows), so no phone or computer is needed. Clean up, updates with one button and
  the other functions that need the TV's own debugging then work over Wireless debugging.
- **Home button:** when the TV stops MyTV and its Home-button helper with it, the helper comes back by itself within
  15 minutes (with the extra right), not only at the next start or boot.
- **Other TVs** (Settings → System): keep two TVs in the house the same. Link them once with a code shown on the
  other TV; then one TV takes over all settings and own photos of the other, over the home network and encrypted.
  A TV can also follow another (**Keep this TV the same**): changes there come over by themselves.
- **Backup:** also the screensaver (MyTV as screensaver and its start time) and the Apps and Sources button choices.
  Buttons taught to MyTV stay with the TV whose remote they belong to.
- **Google TV:** the Home button opens MyTV again (MyTV could not see Google TV's own home screen).
- **28 more languages**, 34 in all: Arabic, Bulgarian, Chinese (Simplified and Traditional), Croatian, Czech, Danish,
  Finnish, Greek, Hebrew, Hindi, Hungarian, Indonesian, Italian, Korean, Norwegian, Persian, Polish, Portuguese
  (Brazil), Romanian, Russian, Serbian, Slovak, Swedish, Thai, Turkish, Ukrainian and Vietnamese. Arabic, Hebrew and
  Persian are shown right-to-left.
- **Now playing:** the card itself can be selected, like the weather; OK opens the app. Right goes on to play/pause and
  next. The separate "open app" button is gone.
- **Continue watching** moved to the right, under the weather. It has its own settings page (Settings → Home screen →
  Continue watching: show it or not, and which sources: Plex and each app on or off) and a step in the welcome screen.
  Any card can be removed. Pictures are better and are tried again when they fail; one card per title; an app's own
  link is used to continue. A YouTube video that could not be found is looked up again later.
- **Plex:** Continue watching from your own Plex server. Link it with a code at plex.tv/link; each profile can use its
  own Plex Home user.
- **Settings per profile:** each profile has its own home-screen settings (weather places, photos, clock, glass, Continue
  watching, Now playing, buttons, language…); a new profile starts as a copy. Settings for the whole TV (Home button,
  Sources and Apps buttons, screensaver, sleep timer, quiet hours, maintenance, profiles) are only in the first profile.
  A settings backup includes every profile.
- **A faster TV:** Clean up recommends switching off Google's recommendations service when it is on: on some TVs it
  stalls Android every 32 seconds (the remote reacts late, then all at once). Clean up now knows safe parts of Google,
  Philips, Sony, TCL, Hisense, Xiaomi and Nvidia Shield devices (from CleanYourTV's lists). MyTV lets go of its photo
  and picture caches when it has been out of sight for 30 seconds.
- **Own photos:** the phone says which photos were not added, and why; portrait photos are no longer refused as too
  small; own photos can be removed in Settings → Background (hold OK).
- **Smaller fixes:** the TV-listings permission is asked on the Continue watching step, not over the welcome screen;
  popups opened from Settings or the welcome screen keep it in view; Down reaches the welcome screen's buttons; the Plex
  link closes by itself once linked; moving an app to either end of a long dock scrolls along; profiles show their
  icon; "Who is watching?" hides the home screen behind it.
- **Settings in a clearer order:** Remote control is its own section (with the Home button); the sleep timer and quiet
  hours are under Screensaver; Language & region and Bluetooth are under System; "Settings backup" is now "Back up and
  restore". The welcome screen is quicker: a choice goes on by itself, and it ends with three tips.
- **Remote control** page (Settings → Remote control): the Sources and Apps buttons open MyTV's Sources and All apps (or do
  what the TV does, or teach another button); red, green, yellow and blue get the same choices as Button 1 and 2 (on
  MyTV's home screen only); the number buttons 1–9 open the apps in the dock.
- **Sources button:** the remote's Sources button opens MyTV's Sources: over the app or source that is playing (with
  MyTV itself as one of the choices), or in MyTV. Over another app it is drawn by the Home-button helper, so that app
  keeps playing.
- **Screensaver:** some TVs put its start time back to "never" by themselves; MyTV now puts the time you chose back.
- **Low battery:** a notice on the home screen when a connected Bluetooth device drops below 15 %.
- **Simple glass** is the default on slow TVs (1.5 GB of memory or less), until you choose.
- **Code and security review (21 November):** speaker discovery really stops while another app plays (it kept
  running, and after a few returns new discoveries failed); focus stays on a Continue watching card when the row
  reorders; the sleep timer button counts down; the weather page no longer closes another window right after start;
  the TV-listings question only appears while MyTV is in front. Home-network checks never look names up (no DNS
  rebinding through album-art addresses). A settings backup no longer carries what Clean up switched off: on
  another TV MyTV would have switched those apps off without asking. Tests for the home screen's rules (profiles,
  PIN, "who is watching?") and checksums for every library the build downloads.
- **Installers** find the TV on the network by themselves (the address is asked only when they cannot) and name the
  model they connected to; with several TVs you pick one from a numbered list. Wireless debugging needs only the
  pairing code.
- **Remote:** holding OK opens the menu on every TV, however the remote reports a held key; Down does nothing in the
  dock (it jumped between tiles), and Down into the dock returns to the tile you left.
- **Back where you were:** after an app MyTV comes back on the element you left; after switching the TV on it starts on
  the first app in the dock.
- **Updates:** "Not enough space on the TV" is said as such, checked before downloading.
- **Glass** fades along with a new background photo instead of changing at once.
- **Fixed:** the Now playing card of an app (such as Spotify) disappeared for a moment at every next song.

## 2026.11.5

First public release.

- **Home screen:** a large clock, the weather, and a dock with your apps in your own order.
- **Now playing:** what plays on Sonos, Google Cast, other speakers and apps on the TV. Hold OK to open the app, hide
  the card or stop.
- **Continue watching:** on its own panel, also for apps that do not publish it themselves, such as YouTube.
- **Background photos:** open-licence photos that change by themselves, or your own photos sent from your phone.
- **Weather:** the next hours and eight days, with sun and moon. MET Norway is used as a backup source.
- **Comfort:** a screensaver, night mode, a sleep timer with a countdown, and quiet hours (a maximum volume in the
  evening, also on a Sonos soundbar).
- **Profiles** for parents and children, with a PIN.
- **Settings backup** to your phone, and restore from it.
- **Setup without a computer:** extra permissions from the TV itself, and MyTV Launcher as the default home screen on
  TVs that allow it.
- **Six languages:** English, Dutch, French, German, Spanish and Portuguese.
- **Sources:** HDMI devices and live TV in one press.
- **Private:** no account, no ads, no tracking.
