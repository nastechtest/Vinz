# Contributing to Vinz

Thanks for wanting to help. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](../../issues/new/choose) and pick **Bug report**. The most useful things to include:

- Vinz version (Settings → About) and your Android version and phone
- Whether you're signed in
- What you did, what you expected, and what happened instead
- For playback or lyric problems: the song (a link is perfect)

Logs help a lot with playback problems. With USB debugging on, `adb logcat` while it happens and attach the part around the error. Please look through it first and remove anything personal.

## Building

You need JDK 17+ and the Android SDK (compile SDK 37). On Windows use `.\gradlew.bat`.

```bash
./gradlew :app:assembleDebug
```

To try your build without touching an installed Vinz, build a side-by-side copy. It installs as `com.vinz.music.dev` with its own data:

```bash
./gradlew :app:assembleDebug -PsideBySide
```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Some tests call live YouTube Music and lyric endpoints, so they need a network connection and can occasionally fail when those services are slow or change their results. If one of those fails, run it again on its own before assuming your change broke it.

If you change a screen background, run `BackdropRenderTest` and look at the contact sheets it writes to `app/build/backdrop-previews/`.

## Pull requests

Open pull requests against `main`. CI runs Android Lint, the deterministic unit tests and a debug build on
every push, so you will know within a few minutes whether your change compiles and passes the tests.

A successful build also produces an installable APK (`Vinz-PR-<number>-build-<n>.apk`), linked from the
workflow summary. It is signed with the debug key, so uninstall Vinz before installing it, or build locally
with `-PsideBySide` to install a copy alongside the real app.

- Keep each pull request to one change, and say what it fixes or adds and how you checked it (which phone or emulator, light and dark mode for UI changes).
- Match the surrounding code: Kotlin, Jetpack Compose, the same naming and comment style. Dependencies are wired by hand in `AppContainer.kt` (no Hilt/Dagger).
- Don't enable R8/minification. NewPipe Extractor and Rhino rely on reflection.
- New libraries need a license compatible with GPL-3.0, and an entry in `ui/screens/LicensesPage.kt` (plus its license text in `app/src/main/assets/licenses/` if it isn't one already there).

## Releases

Maintainers cut releases from the `stable` branch with a manually approved workflow. The full procedure,
including the branch strategy, versioning rules and rollback steps, is in
[docs/RELEASING.md](docs/RELEASING.md).

## Ground rules

- **Privacy.** Vinz has no server and no telemetry, and that's not going to change. Listening history, Replay and settings stay on the phone. Pull requests that add analytics, crash reporting services or tracking won't be merged.
- **Your YouTube session stays in Vinz.** Never send Vinz's YouTube cookies or account details to any third-party service.
- Vinz is unofficial and not affiliated with Google or YouTube. Keep it that way in names, icons and wording.

## License

Vinz is licensed under the [GNU General Public License v3.0 or later](LICENSE). By sending a contribution you agree that it's licensed the same way.
