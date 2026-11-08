# Experimental split-player navigation

Original Vinz navigation remains the default. In Settings → Layout & navigation → Navigation bar, enable **Split-player navigation (experimental)** to opt in. Floating style and Shrink while scrolling must also be enabled. A playing song is required. Switching the experiment off restores the original layout and pre-scroll shrink behaviour.

## Fit and accessibility

Fit uses actual available Compose constraints and the active Compose font scale, including Vinz's own text-size setting. Font scales over 1.3, unsupported tab counts, narrow layouts, Reduce motion, docked/minimal navigation, and wide-window rails retain original navigation. Looks and Reset look cannot change the explicit experimental opt-in.

Each side footprint reserves `max(left tabs, right tabs) × 48 dp`; the centred compact player reserves 64 dp with a 4 dp gap on each side. Four tabs fit at 320 dp and five at 360 dp. All bounds use pixel-relative fractions, avoiding density conversion errors. Odd tab counts remain centred. Compact content retains the song's accessible name, opening action, gestures, and the user's play-button preference. Skip controls retire before the player narrows enough to crowd them.

## Motion and playback

Only child-consumed vertical scroll drives the experimental bounded 0..1 morph; overscroll is ignored. A single Layout measures the two tab groups and one MiniPlayer. The playback controller, playback state, full-player navigation, and route callbacks are unchanged. Original navigation keeps the established scroll threshold/spring path.

## Default launcher icon

Launcher-icon selection and its previews are removed from Settings and Settings search. Startup restores the default launcher entry before disabling enabled legacy launcher aliases. Legacy aliases remain in the manifest for upgrade compatibility. Media-service aliases are deliberately not disabled, so migration does not interrupt music.

## Verification status

ChromeGeometryTest covers fit, fallback, density scaling, centring, bounds, scroll sanitisation, and endpoints. ExperimentalNavigationTest covers default-off persistence and exclusion from Looks/reset. AppIconCrashTest now covers default-icon migration rather than obsolete preview composition. Standalone geometry harness passed 22 checks; full Gradle/Compose, lint and device validation must be reported separately. This batch is review/debug only. PR32 remains draft; no merge or official release is authorised.
