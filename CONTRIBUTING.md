# Contributing to MyTV Launcher

Thank you for wanting to help. MyTV Launcher ("MyTV" for short) is a small project with one maintainer; these few rules keep it manageable.

## Ways to help

You do not have to write code.

- **Try it on your TV, box or stick** and report what works and what does not. MyTV is developed on one Philips Android
  TV (Android 11), so reports from other brands, Google TV and newer Android versions are the most useful thing you can
  give. Say which device and Android version.
- **Report a problem or suggest an idea:** open an [issue](https://github.com/tinteractive/MyTV/issues/new/choose).
- **Improve a translation**, or check one you speak well: most of the 34 languages were translated without a native
  speaker checking them yet.
- **Suggest a background photo** with an open licence.
- **Fix something or build something:** see below.

Security problems do not go in a public issue: see [SECURITY.md](SECURITY.md).

## Wanted

Good places to start, roughly from small to large:

- Testing and fixes for other brands: the Home-button helper, inputs (Sources), the screensaver and "Make MyTV the
  default home" differ per TV.
- Accessibility: the chosen section, photo and profile are not announced to a screen reader yet; nothing has been
  tested with TalkBack on a real TV.
- Smaller clean-ups: fixed pixel values that should scale with the screen density, work that can be remembered instead
  of redone on every redraw, state written during composition (look for `state.close()` inside composables).
- Smoothness: during a photo change and the night-mode fade the whole home screen is redrawn every frame.
- "Now playing" for more speaker brands.
- Tests for the home screen state (focus, profiles, PIN): this needs a test setup with a simulated Android.

For anything larger than a small fix, open an issue first and wait for a reply, so nobody does work that cannot be
merged.

## What fits MyTV

MyTV is a calm home screen: no account, no ads, no tracking, no recommendations. Changes should keep it that way.

- Everything works with a remote: arrows, OK and Back. No touch-only or pointer-only controls.
- Solutions are generic: look at what Android reports (roles, settings, installed packages), not at a brand or model.
- Nothing new is sent off the TV without discussion, and whatever is sent is listed in [docs/PRIVACY.md](docs/PRIVACY.md).
- No API keys or other secrets in the code.
- New dependencies need a good reason: the APK is about 3 MB and should stay small.

## Making a change

Building, the two editions, running on a TV and the tools are described in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md);
how the app is put together in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

1. Fork the repository and make a branch from `main`.
2. Keep a pull request to one subject. Small pull requests are reviewed sooner.
3. Run what CI runs, from the `android` folder:
   ```
   ./gradlew :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest :app:lintGithubDebug :app:lintPlayDebug detekt
   ```
   Add a unit test for new logic that can be tested without a device (parsing, sorting, time and date rules).
4. In the pull request, say what you tested and on which device.

Some things have their own place:

- **Texts** the viewer sees are in the table in `tools/strings.py` (English and five more languages) and in one file per
  other language in `tools/strings/`. Change them there and run `python tools/strings.py`, which writes the
  `strings.xml` files and stops when a language misses a text; commit both. If you do not speak a language, add your
  best translation and say so. A setting's explanation must fit on one line on a 1080p TV
  (`python tools/check_subtitles.py`).
- **Photos** need an open licence (CC0, CC BY, CC BY-SA or public domain) with author and source, and the subject must
  stay clear of the clock, the weather, the buttons and the dock. Use `tools/photos.py`.
- **Screenshots** must not show your own apps, photos or place: use the demo mode in
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
- **Code style:** Kotlin official style, English names and comments. Comments explain why, not what.

## Sign your work (DCO)

Contributions are accepted under the project's licence, [GPL-3.0-only](LICENSE). Each commit must carry a
`Signed-off-by` line, which states that you wrote the change or have the right to submit it
([Developer Certificate of Origin 1.1](https://developercertificate.org/)):

```
git commit -s -m "Weather: show gusts"
```

This adds `Signed-off-by: Your Name <you@example.com>` using your git name and e-mail address.

## Conduct

Be kind and to the point; assume the other person means well. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Releases

Only the maintainer publishes releases and adds photos to the online photo set. Release tags (`v*`) and the `main`
branch are protected.
