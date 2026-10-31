#!/usr/bin/env python3
"""Makes the first launch's spoken welcome, so what the entrance says can be changed in one place.

    python3 scripts/brand/make_intro_voice.py

Writes `app/src/main/res/raw/vinz_intro_voice.ogg`: one 15.5 s track holding the whole script, with each line sitting
exactly where the picture expects it. The file is committed, so the build needs no network and no speech service - this
script is only needed to change the words.

The timing is the point. Each line is rendered on its own, trimmed of the silence a synthesiser pads it with, and then
placed at its beat, so the words and the captions arrive together. `Intro.WORD`, `Intro.LINE_ONE`, `Intro.LINE_TWO`,
`Intro.LINE_THREE` and `Intro.DOCK` in `ui/intro/IntroScene.kt` are the same numbers: this table and that object have to
agree, and `IntroVoiceTest` fails if the file's length can't hold the last line.

Unlike the bed and the sting (`make_intro_audio.py`), this is a recorded voice: it is rendered here with Microsoft's
neural text-to-speech (the `edge-tts` package) rather than synthesised from oscillators. The rendering is checked in,
and it is the app's own welcome - nothing third-party is sampled into it.
"""
import asyncio
import subprocess
import sys
from pathlib import Path

import numpy as np

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/res/raw"

VOICE = "en-US-AndrewNeural"
RATE = "-6%"

#: (beat in seconds, what it says). The beats are the picture's, not the audio's: see `Intro`.
SCRIPT = [
    (0.55, "Welcome to Vinz."),
    (7.95, "Your music. Your universe."),
    (9.90, "Every song is a star."),
    (11.40, "Hold the sky, and it is yours."),
    (13.60, "Hold to begin."),
]

#: How long the track is, in seconds. Must be past the last line, and at least `Intro.DOCK`.
LENGTH = 15.5


def render(text, path):
    """One line, rendered to mp3 by the neural voice."""
    import edge_tts

    async def go():
        for attempt in range(5):
            try:
                await edge_tts.Communicate(text, VOICE, rate=RATE).save(str(path))
                return
            except Exception as e:  # the service drops requests now and then
                if attempt == 4:
                    raise
                await asyncio.sleep(1.5 + attempt)

    asyncio.run(go())


def pcm(path):
    """Decoded mono float32 at 44.1 kHz, trimmed of the silence on both ends."""
    raw = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", str(SR),
         "-f", "f32le", "-"],
        capture_output=True, check=True,
    ).stdout
    x = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    # 40 ms either side at -46 dBFS: enough to drop the synthesiser's padding without clipping a consonant.
    floor = 10 ** (-46 / 20)
    loud = np.where(np.abs(x) > floor)[0]
    if len(loud):
        pad = int(0.02 * SR)
        x = x[max(0, loud[0] - pad): min(len(x), loud[-1] + pad)]
    return x


def place(buf, x, at, gain=1.0):
    start = int(at * SR)
    end = min(len(buf), start + len(x))
    if start >= len(buf):
        return
    buf[start:end] += gain * x[: end - start]


def main():
    if subprocess.run(["ffmpeg", "-version"], capture_output=True).returncode != 0:
        sys.exit("ffmpeg is needed to encode the ogg file")
    tmp = Path("/tmp/vinz-intro-voice")
    tmp.mkdir(parents=True, exist_ok=True)

    n = int(LENGTH * SR)
    voice = np.zeros(n)
    ends = []
    for i, (at, text) in enumerate(SCRIPT):
        f = tmp / f"line{i}.mp3"
        if not f.exists():
            print(f'rendering "{text}"')
            render(text, f)
        x = pcm(f)
        # Normalised per line, then placed: a short line should not be quieter than a long one.
        x = x / max(np.max(np.abs(x)), 1e-9) * 0.72
        place(voice, x, at)
        ends.append((at, at + len(x) / SR, text))

    last = max(e for _, e, _ in ends)
    if last > LENGTH - 0.15:
        sys.exit(f"the last line ends at {last:.2f} s, past the {LENGTH} s track: lengthen LENGTH or move a beat")

    print("\n  start    end   line")
    for a, b, t in ends:
        print(f"  {a:5.2f}  {b:5.2f}   {t}")
    print(f"\n  track: {LENGTH:.2f} s\n")

    # A little air and a gentle low shelf cut, so the voice sits above the bed rather than fighting it.
    from scipy.signal import butter, sosfilt
    sos = butter(2, 110 / (SR / 2), btype="highpass", output="sos")
    voice = sosfilt(sos, voice)
    voice = np.tanh(voice * 1.06) / np.tanh(1.06)

    # Stereo: the same voice both sides, a hair apart, so it sits in the same room as the bed.
    d = int(0.008 * SR)
    left = np.concatenate([np.zeros(d), voice[:-d]])
    pcm_out = (np.clip(np.stack([left, voice], axis=1), -1, 1) * 32767).astype("<i2")

    import wave
    wav = tmp / "voice.wav"
    with wave.open(str(wav), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm_out.tobytes())

    OUT.mkdir(parents=True, exist_ok=True)
    ogg = OUT / "vinz_intro_voice.ogg"
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav),
         "-c:a", "libvorbis", "-q:a", "2", "-ar", "44100", str(ogg)],
        check=True,
    )
    print(f"{ogg.name}: {ogg.stat().st_size / 1024:.0f} KB, {len(voice) / SR:.2f} s")


if __name__ == "__main__":
    main()
