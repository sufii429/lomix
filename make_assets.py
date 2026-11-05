#!/usr/bin/env python
"""
Asset builder for the Lomix Android app.

Run with the audio-analysis venv:
    ~/workspace/audio-analysis/venv/bin/python ~/workspace/rainy-lofi/make_assets.py

Outputs (into app/src/main/assets/):
    rain_gentle.mp3   - ~90 s seamless loop of the real gentle night-rain recording
    rain_heavy.mp3    - ~90 s seamless loop, same source + synthesized HF patter, +3 dB
    vinyl_crackle.mp3 - 60 s seamless generated vinyl crackle loop

Source: ~/workspace/audio-analysis/Rain_Bed_for_Lofi.mp3
(5:30 stereo 44.1 kHz - a real "Gentle Night Rain" field recording with a
very light crackle already mixed in; machine-checked: no thunder.)
"""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent
SRC = Path.home() / "workspace" / "audio-analysis" / "Rain_Bed_for_Lofi.mp3"
BUILD = ROOT / ".asset-build"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
BUILD.mkdir(exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)

FADE_S = 4.0          # loop crossfade length
rng = np.random.default_rng(42)


def seamless_loop(seg: np.ndarray, sr: int, target_s: float) -> np.ndarray:
    """Turn seg (target + fade seconds long) into a cleanly looping target-length clip.

    The last FADE seconds are an equal-power crossfade between the material
    that would naturally follow (seg tail) and the head of the segment, so the
    waveform flows into seg[0] exactly where the loop restarts.
    """
    T = int(target_s * sr)
    F = int(FADE_S * sr)
    assert len(seg) >= T + F, "segment too short for loop crossfade"
    loop = np.empty((T,) + seg.shape[1:], dtype=np.float64)
    loop[: T - F] = seg[F:T]
    w = np.linspace(0.0, 1.0, F).reshape(F, *([1] * (seg.ndim - 1)))
    loop[T - F:] = seg[T:T + F] * np.sqrt(1.0 - w) + seg[0:F] * np.sqrt(w)
    return loop


def fft_filter(x: np.ndarray, kind: str, cutoff: float, sr: int) -> np.ndarray:
    """Smooth FFT-domain filter applied per channel (same approach as make_rain.py)."""
    X = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(len(x), 1 / sr)
    if kind == "low":
        H = 1.0 / np.sqrt(1.0 + (freqs / cutoff) ** 4)
    else:  # high
        H = 1.0 / np.sqrt(1.0 + (cutoff / np.maximum(freqs, 1.0)) ** 4)
        H[0] = 0.0
    return np.fft.irfft(X * H.reshape(-1, *([1] * (x.ndim - 1))), len(x), axis=0)


def slow_lfo(n: int, sr: int, lo=0.4, hi=1.0) -> np.ndarray:
    t = np.arange(n) / sr
    g = np.zeros(n)
    for f, ph in zip(rng.uniform(0.015, 0.06, 4), rng.uniform(0, 2 * np.pi, 4)):
        g += np.sin(2 * np.pi * f * t + ph)
    g = (g - g.min()) / (g.max() - g.min())
    return lo + (hi - lo) * g


def encode_mp3(wav: Path, out: Path):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav),
         "-codec:a", "libmp3lame", "-b:a", "128k", str(out)],
        check=True,
    )
    print(f"  -> {out.name}: {out.stat().st_size / 1024:.0f} KiB")


def main():
    src, sr = sf.read(str(SRC), always_2d=True)
    src = src.astype(np.float64)
    print(f"source: {len(src) / sr:.0f}s, {src.shape[1]}ch, {sr} Hz")
    T = 90.0
    F = FADE_S

    # ---- 1. rain_gentle.mp3 : seconds 10-104 of the source, looped ----------
    seg = src[int(10 * sr): int((10 + T + F) * sr)]
    gentle = seamless_loop(seg, sr, T)
    wav = BUILD / "rain_gentle.wav"
    sf.write(wav, gentle.astype(np.float32), sr)
    encode_mp3(wav, ASSETS / "rain_gentle.mp3")

    # ---- 2. rain_heavy.mp3 : seconds 150-244 + HF patter, +3 dB ------------
    seg = src[int(150 * sr): int((150 + T + F) * sr)].copy()
    n = len(seg)
    rain_rms = np.sqrt(np.mean(seg ** 2))
    for ch in range(seg.shape[1]):
        patter = fft_filter(rng.standard_normal(n), "high", 4500, sr)
        patter *= slow_lfo(n, sr)[:, None].squeeze()
        pat_rms = np.sqrt(np.mean(patter ** 2))
        patter *= (0.35 * rain_rms) / pat_rms      # moderate patter layer
        seg[:, ch] += patter
    seg *= 10 ** (3 / 20)                           # +3 dB
    peak = np.max(np.abs(seg))
    if peak > 0.95:                                 # safety: keep peaks sane
        seg *= 0.95 / peak
    heavy = seamless_loop(seg, sr, T)
    wav = BUILD / "rain_heavy.wav"
    sf.write(wav, heavy.astype(np.float32), sr)
    encode_mp3(wav, ASSETS / "rain_heavy.mp3")

    # ---- 3. vinyl_crackle.mp3 : 60 s generated, circular (wraps seamlessly) -
    dur = 60.0
    n = int(dur * sr)
    crackle = np.zeros(n)
    n_clicks = rng.poisson(12.0 * dur)              # ~12 clicks/sec
    pos = rng.integers(0, n, n_clicks)
    for p in pos:
        big = rng.random() < 0.05                   # rare bigger "pop"
        amp = (rng.random() ** 2) * (0.8 if big else 0.3)
        length = int(sr * (rng.uniform(0.008, 0.018) if big else rng.uniform(0.0008, 0.003)))
        burst = rng.standard_normal(length) * np.exp(-np.linspace(0, 6, length))
        if big:
            burst = np.convolve(burst, np.ones(24) / 24, mode="same")
        idx = (p + np.arange(length)) % n           # wrap -> seamless loop
        crackle[idx] += burst * amp
    crackle *= (10 ** (-31 / 20)) / (np.sqrt(np.mean(crackle ** 2)) + 1e-12)

    def periodic_hiss() -> np.ndarray:
        # Random-phase FFT noise is periodic by construction -> wraps cleanly.
        X = np.fft.rfft(rng.standard_normal(n))
        freqs = np.fft.rfftfreq(n, 1 / sr)
        H = 1.0 / np.sqrt(1.0 + (freqs / 6000.0) ** 4)
        phases = np.exp(1j * rng.uniform(0, 2 * np.pi, len(X)))
        return np.fft.irfft(np.abs(X) * H * phases, n)

    vinyl = np.empty((n, 2))
    for ch in range(2):
        hiss = periodic_hiss()
        hiss *= (10 ** (-42 / 20)) / (np.sqrt(np.mean(hiss ** 2)) + 1e-12)
        vinyl[:, ch] = crackle + hiss
    peak = np.max(np.abs(vinyl))
    if peak > 0.9:
        vinyl *= 0.9 / peak
    wav = BUILD / "vinyl_crackle.wav"
    sf.write(wav, vinyl.astype(np.float32), sr)
    encode_mp3(wav, ASSETS / "vinyl_crackle.mp3")

    print("done.")


if __name__ == "__main__":
    main()
