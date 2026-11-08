# Community Looks

Looks people made and shared, listed in Vinz under **Settings → Appearance → Looks → From the community**. Vinz
downloads this folder's `index.json` only when someone opens that list, and a Look's file only when someone uses it.
Every Look shows as a preview first, and a Look can only change how Vinz looks: never accounts, playback or anything
else.

## Nastech Research collection

Ten original, code-only Looks are credited to **Nastech Research**. Their editable profiles live in
[`nastech-research.json`](nastech-research.json); no external photos, fonts or additional renderer are needed.

- **True-black AMOLED:** Obsidian AMOLED, Black Jade AMOLED, Carbon Gold AMOLED. Pages, player and both lyrics
  modes use black; no moving background or glass layer is requested.
- **Black-base / glass:** Noctilucent uses subdued drifting midnight fields over a black base; Crystal Tide uses
  clear liquid glass over ocean light fields; Glacier Frost is a still, frosted mist-blue Look.
- **Water / polished motion:** Ripple Lagoon, Pelagic Flow, Opal Silk and Ember Prism use Vinz's existing animated
  ripples, waves, silk ribbons and light fields. They do not add a separate animation engine.

The six moving Looks honor **Reduce motion** and **Performance mode**. Applying, importing or resetting a Look
must not change the listener's Reduce motion preference; legacy codes carrying `reduce_motion` ignore that key.
All presets leave playback and experimental navigation opt-in untouched. They use Vinz's default icon style.

After editing a profile, regenerate its gallery code with `python3 scripts/generate-nastech-looks.py`. Reviewers
and CI can verify reproducibility without writing files using `python3 scripts/generate-nastech-looks.py --check`.
The generator preserves the other gallery entries. `NastechResearchLooksTest`, `LookTest` and `ThemeGalleryTest`
check the generated entries against the real Android settings and Look decoder. Device appearance review is still
required; automated tests do not establish visual approval or authorize release.

## Add yours

1. Make your Look in Vinz, then save it: **Appearance → Looks → Save as a Look**.
2. On its card, tap **⋯ → Share → Send as text**, and copy the code (it starts with `VINZLOOK1:`).
3. Add an entry to the end of `looks` in [`index.json`](index.json):

   ```json
   {
     "id": "your-look-name",
     "name": "Your Look's Name",
     "by": "your-github-name",
     "note": "What it is, in a few words",
     "code": "VINZLOOK1:…"
   }
   ```

   - `id`: lowercase letters, digits and hyphens, up to 40 characters, not used by another Look.
   - `name`: up to 40 characters. `note`: up to 80.
4. Open a pull request. Each Look is looked at before it's added.

### A Look with its own photos

Codes leave out background photos. To include them, use **Share → Send as a file** with **Include its photos** on,
name the file `<id>.vinzlook`, add it to this folder, and add `"file": "<id>.vinzlook"` to your entry (keep the
`code` too: Vinz draws the Look's card from it). Files can be up to 8 MB. Only include photos you took or may share.
Fonts can't be added here.

### Checks

`ThemeGalleryTest` (in `app/src/test`) reads this folder: every entry needs a unique, valid id, a code that reads as a
Look holding only look settings, and its file when it names one. Run `./gradlew :app:testDebugUnitTest --tests
com.vinz.music.ThemeGalleryTest` before opening the pull request.
