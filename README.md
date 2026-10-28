# Vinz

Vinz is an unofficial GPL-3.0-or-later Android client for YouTube Music, based on open-source work by Yigder. It is not affiliated with Google or YouTube.

## Attribution and license

Original copyright notices remain with their respective authors. Copyright attribution to Yigder and all component notices are retained. Vinz is distributed under the GNU General Public License, version 3 or later; see [LICENSE](LICENSE) and [FORK_NOTICE.md](FORK_NOTICE.md).

## Build locally

Requirements: JDK 17 or newer, Android SDK platform 37, and the Android SDK command-line tools.

```bash
./gradlew :app:assembleDebug
```

Run the full local unit suite with:

```bash
./gradlew :app:testDebugUnitTest
```

Some test classes call live YouTube Music or lyric endpoints and need a network connection; CI excludes those integration tests from its required offline test set.

The install package and Kotlin namespace are both `com.vinz.music`.

## Continuous integration and releases

Pull requests and pushes to `main` run the Android build and unit-test workflow. A `v*` tag triggers the signed APK release workflow. Before the first release, configure the repository Actions secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` with the project's persistent release key. The workflow intentionally fails if these secrets are absent; never publish a debug-signed production APK.
