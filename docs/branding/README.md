# Vinz transparent branding

The canonical artwork is `design/branding/vinz-logo.png`: 1024×1024, 8-bit RGBA with a transparent background, transparent interior spaces, and transparent outer padding. Preserve its gold note, headphones, and patterned frame. Do not substitute playback or navigation symbols.

## Static derivatives

- Android full logo and documentation/web preview: 1024px.
- White alpha-only notification/themed-icon mark: 512px, simplified headphones and double note for status-bar readability.
- Legacy launcher PNGs: mdpi 48px, hdpi 72px, xhdpi 96px, xxhdpi 144px, xxxhdpi 192px.
- Adaptive launcher: 64dp centered foreground on the existing 108dp canvas, transparent background. Individual launchers can supply their own system background.
- Browser PNG favicons: 16px, 32px, 48px.
- ICO favicon: PNG-backed 16px, 24px, 32px, 48px, 64px, 128px, 256px frames.
- Web home-screen icons: 192px and 512px; purpose `any`, not `maskable`.
- Apple touch icon: 180px. iOS can apply its own background and corner mask.

Generate the committed static files with `python3 scripts/generate-brand-assets.py` (Pillow is a development-only requirement). This adds no app dependency. SHA-256 values and exact dimensions are recorded in `design/branding/asset-manifest.json`.

Validate with:

```sh
python3 scripts/ci/check_branding.py
python3 -m unittest discover -s scripts/tests -p 'test_brand_assets.py' -v
```

Validation checks RGBA pixel data, transparent edges, nonempty foreground, white-only notification pixels, each ICO frame, exact manifest coverage, hashes, and resource references. Visually review on light/dark backgrounds and an actual launcher before approval. Small favicons necessarily lose the patterned detail.

This asset-only work does not implement animated lyrics credits, alter playback, or change navigation. It is separate from the lyrics-branding work.
