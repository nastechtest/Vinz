# Vinz

DEV

Vinz is an unofficial local rebrand/fork of [Prism](https://github.com/Yigder/Prism),
created by Yigder. It is based on Prism's Android YouTube Music client; this fork
changes the displayed app brand and Android application ID. It is not affiliated
with Yigder, Google, or YouTube.

## Upstream and license

- Original project and full feature documentation: <https://github.com/Yigder/Prism>
- Original author: Yigder
- Original copyright: Copyright (C) 2026 Yigder.
- License: GNU GPL version 3 or later; see [`LICENSE`](LICENSE).
- Original copyright remains with its respective holders. This rebrand does not
  transfer ownership of Prism or remove any upstream attribution. Historical
  entries in [`CHANGELOG.md`](CHANGELOG.md) retain the original Prism name.

Vinz inherits the upstream project's features, including YouTube Music playback,
word-by-word lyrics, offline downloads, recommendations, karaoke, DJ mode, Replay,
Android Auto, widgets, and local-network playback controls. Consult the upstream
README and documentation for detailed setup and feature notes.

## Build

Requirements and Android SDK versions are documented in the upstream project's
[contributing guide](https://github.com/Yigder/Prism/blob/main/CONTRIBUTING.md).

```bash
./gradlew :app:assembleDebug
```

This fork uses Android application ID `com.vinz.music`. The Kotlin namespace stays
`com.prism.music` to preserve the upstream source structure and avoid an unsafe,
mechanical package migration. Existing internal `prism://` links are likewise
retained for compatibility.
