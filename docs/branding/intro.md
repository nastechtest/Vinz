# The entrance

What plays once per install, before the welcome screen, on pure black. It is not a splash that waits on a network or a
first frame: it is an intro, and it is the app's own first frame.

`ui/intro/VinzIntro.kt` is the experience (layout, type, the hold, the sound). `ui/intro/IntroScene.kt` is the picture
(planet, sky, orbits, spark, ring) — a function of one clock, drawn, never composed.

## The beats

All times are seconds from the first frame. They live in `Intro` and the tests check they stay in order.

| Time | What happens |
|---|---|
| 0.00 | Pure black. The window the system shows before the first frame is `#000000` too, so nothing flips. |
| 0.15 | A point of light at the centre; the bed's sub sweep opens underneath it. |
| 0.85 | The planet arrives filling the frame — more than a screen wide, so it reads as a surface you are standing on rather than a sticker. |
| 1.90 | The camera starts pulling back: an exponential, so the planet leaves fast and then hangs as a disc. The sky fades in behind it as it goes. |
| 2.90 | Three orbits draw themselves on, each by a travelling head, around what is now a small world. |
| 5.30 | What is left of the planet becomes a four-point spark. |
| 6.60 | "Vinz" comes up out of the spark: 1.10 → 1.00 scale, one breath of overshoot. |
| 7.05 | One pass of light travels across the name — `SrcAtop` over an offscreen layer, so it exists only where the letters are. |
| 7.50 | `POWERED BY NASTECH RESEARCH`, the second half in the brand's own gradient, cut out of the white with `SrcAtop`. |
| 8.10 / 9.20 / 10.30 | Three lines, one at a time: *Your music. Your universe.* / *Every song is a star.* / *Hold the sky, and it is yours.* |
| 11.10 | The ring arrives at the bottom and stops. Everything else stops with it: the entrance waits for a hand. |

Tapping anywhere during the cinematic lands on the ring — it does not skip the ring, because the hold is the entrance.
Back does the same while the cinematic is running.

## The hold

The ring fills in 1.7 s under a finger and drains in 0.45 s when let go, so a hand that slips loses the progress slowly
enough to feel fair. Each of the 924 points has its own moment in the hold (`collectAt`) and falls into the ring along a
bent path, with the near stars leaving first — the sky empties from the front, and the last one is away before the ring
closes. The ring ticks the haptics thirteen times as it fills.

When it closes: the ring collapses, the screen flares from the button, "Vinz" slashes once at 124 sp with one pass of
light across it, the sting plays, and it goes back to black. Then the app is handed the screen and the flag is written,
so the entrance is once per install — clearing Vinz's data brings it back with everything else.

## 4K

There is not a bitmap in the scene. The planet is a radius, the stars are circles, the glow is a radial gradient, and
every length is measured from the frame it is drawn into and the frame's own density — so 2160 × 3840 and 3840 × 2160
are the same drawing at different resolutions rather than the same dp at different scales. Type is sized from the
screen's height (clamped by its width), so the name takes the same share of a phone, a tablet, and a television.

`IntroRenderTest` draws eleven frames at 2160 × 3840 and four at 3840 × 2160 into `app/build/intro-previews/`, and fails
if the 4K frame comes back black. It is a preview harness: the scene is the shipping `drawIntro`, only the words are set
by hand so the preview needs no layout.

## The sound

Both files are generated, not sampled — `scripts/brand/make_intro_audio.py` rebuilds them byte for byte from the numbers
in it, and no third-party audio ships in the APK.

- `res/raw/vinz_intro_space.ogg` — 13.5 s, stereo, 44.1 kHz Vorbis, 157 KB. A sub sweep from 112 → 33 Hz with an
  octave-up body so a phone speaker still hears it; filtered air whose top opens to ~3 kHz and settles; a low impact as
  the planet lands; a glass chord (E, G♯, B) as the name does; one soft bell as the ring arrives; a convolution reverb
  made from decaying noise so the tail keeps travelling after the sound stops.
- `res/raw/vinz_intro_welcome.ogg` — 2.4 s, 29 KB. A reverse swell into the same chord an octave up, three rising notes
  (G♯5, D♯6, G♯6) that read as a welcome, and a sub drop under the hit. It is a sonic logo, not a spoken line: the words
  are carried by the wordmark. To put a recorded voice in its place, replace this one file.

A phone in silent mode gets silence; any failure to make a player means a quiet entrance, never a failed one. The bed is
ducked out underneath a beat of silence when the cinematic is skipped, so the sting never lands on top of a pad.

## When motion is off

`Reduce motion`, or animator duration scale 0, draws the resting frame — sky still, no travel, the name and the ring up —
and a tap stands in for the hold. The ring is one merged semantics node with a button action labelled *Welcome to Vinz*,
so TalkBack can open the app without a finger; the scene itself is decorative and says nothing.

## Frames

`./gradlew :app:testDebugUnitTest --tests "com.vinz.music.IntroRenderTest"` writes the contact frames to
`app/build/intro-previews/`. `IntroSceneTest` covers the arithmetic — the beats in order, the pull-back never growing the
planet, the sky emptying completely by the end of the hold.
