#!/usr/bin/env python3
"""Makes the first launch's two sounds, so they are reproducible instead of opaque binaries.

    python3 scripts/brand/make_intro_audio.py

Writes app/src/main/res/raw/vinz_intro_space.ogg (the 15 s bed) and vinz_intro_welcome.ogg (the sting). Both are
synthesised: a sub sweep, filtered air, glass partials, and a convolution reverb made from decaying noise. Nothing is
sampled, so there is no third-party audio in the APK and the sound can be changed by changing these numbers.

The frequency ratios are the brand's own light read as a chord: E, G#, B (a major third and a fifth), which is the most
"open" three-note shape there is - it is the sound of a wide space rather than a key.
"""
import subprocess
import sys
from pathlib import Path

import numpy as np
from scipy.signal import fftconvolve, sosfilt, butter

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/res/raw"


def env_ad(n, attack, decay, curve=1.0):
    """Attack/decay envelope, in samples."""
    t = np.arange(n) / SR
    a = 1.0 - np.exp(-t / max(attack, 1e-4))
    d = np.exp(-np.maximum(t - attack, 0.0) / max(decay, 1e-4))
    return (a * d) ** curve


def sine(freq, n, phase=0.0):
    t = np.arange(n) / SR
    return np.sin(2 * np.pi * freq * t + phase)


def sweep(f0, f1, n, curve=1.0):
    """Exponential frequency sweep, phase-continuous."""
    t = np.arange(n) / n
    freq = f0 * (f1 / f0) ** (t ** curve)
    return np.sin(2 * np.pi * np.cumsum(freq) / SR)


def air(n, seed, lo=180.0, hi=3200.0, open_at=0.18, close_at=0.75):
    """Noise through a lowpass whose cutoff opens and then settles: the 'vacuum' of the scene."""
    rng = np.random.default_rng(seed)
    noise = rng.normal(0.0, 1.0, n)
    t = np.arange(n) / n
    open_curve = np.clip((t - open_at) / 0.30, 0, 1)
    close_curve = np.clip((t - close_at) / 0.30, 0, 1)
    cutoff = lo + (hi - lo) * (open_curve - 0.55 * close_curve)
    # One filter per block would click; a fixed middle cutoff plus an amplitude follow sounds the same and is cheap.
    sos = butter(2, min(hi * 1.4, SR * 0.45) / (SR / 2), btype="lowpass", output="sos")
    shaped = sosfilt(sos, noise)
    return shaped * (0.35 + 0.65 * np.clip(cutoff / hi, 0, 1))


def glass(n, notes, seed, bright=0.5):
    """A cluster of detuned partials: the 'glass chord' that lands with the planet."""
    rng = np.random.default_rng(seed)
    out = np.zeros(n)
    for i, f in enumerate(notes):
        for detune, gain in ((0.999, 0.6), (1.001, 0.4), (2.002, 0.18 * bright), (3.006, 0.07 * bright)):
            ph = rng.uniform(0, 2 * np.pi)
            out += gain * sine(f * detune, n, ph) * env_ad(n, 0.01 + 0.02 * i, 1.6 + 0.5 * i)
    return out / max(len(notes), 1)


def reverb(x, seconds=1.7, seed=7, wet=0.34):
    """A synthetic room: decaying, lowpassed noise as an impulse response."""
    rng = np.random.default_rng(seed)
    m = int(seconds * SR)
    ir = rng.normal(0.0, 1.0, m) * np.exp(-np.arange(m) / (0.34 * SR))
    sos = butter(2, 5200 / (SR / 2), btype="lowpass", output="sos")
    ir = sosfilt(sos, ir)
    ir /= np.sqrt(np.sum(ir ** 2))
    wet_sig = fftconvolve(x, ir)[: len(x)]
    return (1 - wet) * x + wet * wet_sig


def stereo(x, seed, spread=0.012):
    """Two decorrelated sides: the same sound, arriving a hair apart."""
    rng = np.random.default_rng(seed)
    d = int(spread * SR)
    left = np.concatenate([np.zeros(d), x[:-d]])
    right = x
    # A touch of different noise on each side keeps the middle from collapsing.
    left = left + 0.02 * rng.normal(0, 1, len(x)) * np.abs(x)
    right = right + 0.02 * rng.normal(0, 1, len(x)) * np.abs(x)
    return np.stack([left, right], axis=1)


def finish(x, peak=0.89, fade_in=0.03, fade_out=1.0):
    x = np.tanh(x * 1.1) / np.tanh(1.1)
    n = len(x)
    fi = int(fade_in * SR)
    fo = int(min(fade_out, n / SR * 0.4) * SR)
    e = np.ones(n)
    if fi:
        e[:fi] = np.linspace(0, 1, fi) ** 2
    if fo:
        e[-fo:] = (np.linspace(1, 0, fo) ** 1.6)
    x = x * e[:, None]
    x *= peak / max(np.max(np.abs(x)), 1e-9)
    return x


def encode(x, name, quality=2):
    OUT.mkdir(parents=True, exist_ok=True)
    wav = OUT / (name + ".wav")
    ogg = OUT / (name + ".ogg")
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    import wave

    with wave.open(str(wav), "wb") as w:
        w.setnchannels(pcm.shape[1])
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav),
         "-c:a", "libvorbis", "-q:a", str(quality), "-ar", "44100", str(ogg)],
        check=True,
    )
    wav.unlink()
    print(f"{ogg.name}: {ogg.stat().st_size / 1024:.0f} KB, {len(x) / SR:.2f} s")


def bed():
    """16 s: the room opening, the planet arriving, the name landing, the ring waiting."""
    seconds = 16.0
    n = int(seconds * SR)
    out = np.zeros(n)

    # The floor: a sub sweep from 112 Hz down to 33 Hz, with an octave-up body so a phone speaker still hears it.
    sub = sweep(112, 33, n, curve=0.55)
    out += 0.42 * sub * env_ad(n, 0.55, 9.0)
    out += 0.10 * sweep(224, 66, n, curve=0.55) * env_ad(n, 0.75, 6.0)

    # Air, opening over the first three seconds.
    out += 0.055 * air(n, seed=11)

    # The planet arrives: a low impact with a long tail.
    hit = int(0.85 * SR)
    body_n = n - hit
    body = (0.55 * sweep(72, 27, body_n, curve=0.7) + 0.18 * air(body_n, seed=5, lo=90, hi=900)) * env_ad(body_n, 0.02, 3.4)
    out[hit:] += body

    # The name lands at 6.6 s: a glass chord, E major, wide and bright.
    chord_n = n - int(6.55 * SR)
    chord = glass(chord_n, [164.8, 207.7, 246.9, 329.6, 493.9], seed=3, bright=0.9)
    chord *= np.concatenate([np.linspace(0, 1, int(0.5 * SR)) ** 2, np.ones(max(0, chord_n - int(0.5 * SR)))])[:chord_n]
    out[int(6.55 * SR):] += 0.22 * chord

    # The ring arrives at 13.6 s: one soft bell, so the eye is told where to go.
    bell_n = n - int(13.6 * SR)
    bell = (sine(1320, bell_n) + 0.5 * sine(1980, bell_n) + 0.25 * sine(2640, bell_n)) * env_ad(bell_n, 0.004, 0.55)
    out[int(13.6 * SR):] += 0.075 * bell

    # Shimmer: the far, high dust of the scene.
    sh = air(n, seed=23, lo=3800, hi=9000)
    t = np.arange(n) / SR
    out += 0.035 * sh * (0.55 + 0.45 * np.sin(2 * np.pi * 0.11 * t)) * np.clip((t - 1.0) / 3.0, 0, 1)

    out = reverb(out, seconds=2.1, seed=9, wet=0.42)
    # Sits under the spoken welcome: -4 dB under the voice, so the words are never competing with the pad.
    return finish(stereo(out, seed=17), peak=0.56, fade_out=1.6)


def sting():
    """1.6 s + tail: a reverse swell into a bright major chord, and three rising notes that read as 'welcome'."""
    seconds = 2.4
    n = int(seconds * SR)
    out = np.zeros(n)

    # Reverse swell: air and a rising fifth arriving at the hit.
    swell_n = int(0.55 * SR)
    swell = air(swell_n, seed=31, lo=300, hi=6000) * (np.linspace(0, 1, swell_n) ** 2.2)
    out[:swell_n] += 0.20 * swell
    out[:swell_n] += 0.16 * sweep(220, 660, swell_n, curve=1.4) * (np.linspace(0, 1, swell_n) ** 2.5)

    # The hit: E5, G#5, B5, and their octaves - the same shape as the bed's chord, an octave up and much brighter.
    hit = int(0.55 * SR)
    hit_n = n - hit
    chord = glass(hit_n, [659.3, 830.6, 987.8, 1318.5], seed=41, bright=1.0)
    chord *= env_ad(hit_n, 0.004, 1.1)
    out[hit:] += 0.34 * chord
    out[hit:] += 0.30 * sweep(90, 40, hit_n, curve=0.8) * env_ad(hit_n, 0.004, 0.45)

    # "Welcome": three notes, rising, each a pluck with a long tail.
    for i, (start, f) in enumerate(((0.56, 987.8), (0.66, 1244.5), (0.76, 1661.2))):
        at = int(start * SR)
        seg = (sine(f, n - at) + 0.35 * sine(f * 2, n - at) + 0.12 * sine(f * 3.01, n - at)) * env_ad(n - at, 0.003, 0.5 - i * 0.06)
        out[at:] += 0.16 * seg

    out = reverb(out, seconds=2.0, seed=13, wet=0.40)
    return finish(stereo(out, seed=19, spread=0.006), fade_out=0.9)


if __name__ == "__main__":
    if subprocess.run(["ffmpeg", "-version"], capture_output=True).returncode != 0:
        sys.exit("ffmpeg is needed to encode the ogg files")
    encode(bed(), "vinz_intro_space")
    encode(sting(), "vinz_intro_welcome")
