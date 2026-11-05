#!/usr/bin/env python
"""
v1.1 asset builder for the Lomix Android app.

Run with the audio-analysis venv:
    ~/workspace/audio-analysis/venv/bin/python ~/workspace/rainy-lofi/make_assets_v11.py

Outputs (into app/src/main/assets/):
    rain_tin.mp3   - ~90 s seamless loop: real rain (a later slice of the
                     same field recording) + a dense metallic patter layer,
                     for the "Tin Wali Chhat" (tin-roof rain) choice.
    tape_hiss.mp3  - 45 s seamless loop of soft tape hiss for the Cassette
                     effect (periodic FFT noise, wraps cleanly).

Reuses the loop/filter helpers and conventions of make_assets.py.
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

FADE_S = 4.0
rng = np.random.default_rng(1234)  # different seed from v1 assets


def seamless_loop(seg: np.ndarray, sr: int, target_s: float) -> np.ndarray:
    T = int(target_s * sr)
    F = int(FADE_S * sr)
    assert len(seg) >= T + F, "segment too short for loop crossfade"
    loop = np.empty((T,) + seg.shape[1:], dtype=np.float64)
    loop[: T - F] = seg[F:T]
    w = np.linspace(0.0, 1.0, F).reshape(F, *([1] * (seg.ndim - 1)))
    loop[T - F:] = seg[T:T + F] * np.sqrt(1.0 - w) + seg[0:F] * np.sqrt(w)
    return loop


def fft_filter(x: np.ndarray, kind: str, cutoff: float, sr: int) -> np.ndarray:
    X = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(len(x), 1 / sr)
    if kind == "low":
        H = 1.0 / np.sqrt(1.0 + (freqs / cutoff) ** 4)
    else:
        H = 1.0 / np.sqrt(1.0 + (cutoff / np.maximum(freqs, 1.0)) ** 4)
        H[0] = 0.0
    return np.fft.irfft(X * H.reshape(-1, *([1] * (x.ndim - 1))), len(x), axis=0)


def band_emphasis(x: np.ndarray, lo: float, hi: float, gain: float, sr: int) -> np.ndarray:
    """Multiply the spectrum by (1 + gain * smooth band mask between lo..hi)."""
    X = np.fft.rfft(x, axis=0)
    freqs = np.fft.rfftfreq(len(x), 1 / sr)
    mask = np.exp(-0.5 * ((np.log(np.maximum(freqs, 1.0)) - np.log(np.sqrt(lo * hi)))
                          / np.log(hi / lo)) ** 2)
    H = 1.0 + gain * mask
    return np.fft.irfft(X * H.reshape(-1, *([1] * (x.ndim - 1))), len(x), axis=0)


def slow_lfo(n: int, sr: int, lo=0.4, hi=1.0) -> np.ndarray:
    t = np.arange(n) / sr
    g = np.zeros(n)
    for f, ph in zip(rng.uniform(0.015, 0.06, 4), rng.uniform(0, 2 * np.pi, 4)):
        g += np.sin(2 * np.pi * f * t + ph)
    g = (g - g.min()) / (g.max() - g.min())
    return lo + (hi - lo) * g


def encode_mp3(wav: Path, out: Path, bitrate="128k"):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav),
         "-codec:a", "libmp3lame", "-b:a", bitrate, str(out)],
        check=True,
    )
    print(f"  -> {out.name}: {out.stat().st_size / 1024:.0f} KiB")


def seam_check(name: str, y: np.ndarray):
    """Report the waveform jump across the loop seam (last -> first sample)."""
    jump = float(np.max(np.abs(y[0] - y[-1])))
    rms = float(np.sqrt(np.mean(y ** 2)))
    print(f"  {name}: seam jump {jump:.5f} (rms {rms:.5f}, ratio {jump / max(rms, 1e-9):.3f})")


def main():
    src, sr = sf.read(str(SRC), always_2d=True)
    src = src.astype(np.float64)
    print(f"source: {len(src) / sr:.0f}s, {src.shape[1]}ch, {sr} Hz")
    T = 90.0

    # ---- 1. rain_tin.mp3 : seconds 205-299 + metallic patter --------------
    seg = src[int(205 * sr): int((205 + T + FADE_S) * sr)].copy()
    n = len(seg)
    rain_rms = np.sqrt(np.mean(seg ** 2))
    for ch in range(seg.shape[1]):
        # dense bright patter, stronger than rain_heavy's, with a tinny
        # resonance emphasis around 2.6 kHz
        patter = fft_filter(rng.standard_normal(n), "high", 3000, sr)
        patter = band_emphasis(patter, 1800, 4800, 0.8, sr)
        # fast random envelope so it crackles like drops instead of a steady hiss:
        # rectified short-window noise, smoothed at ~30 ms then ~8 ms square-ish
        env = np.abs(rng.standard_normal(n))
        k = max(1, int(sr * 0.03))
        env = np.convolve(env, np.ones(k) / k, mode="same") ** 2
        patter *= env * slow_lfo(n, sr)
        pat_rms = np.sqrt(np.mean(patter ** 2))
        patter *= (0.55 * rain_rms) / pat_rms
        seg[:, ch] += patter
    seg = band_emphasis(seg, 1800, 4800, 0.35, sr)  # a little tin ring on the whole mix
    seg *= 10 ** (2 / 20)                            # +2 dB
    peak = np.max(np.abs(seg))
    if peak > 0.95:
        seg *= 0.95 / peak
    tin = seamless_loop(seg, sr, T)
    seam_check("rain_tin", tin)
    wav = BUILD / "rain_tin.wav"
    sf.write(wav, tin.astype(np.float32), sr)
    encode_mp3(wav, ASSETS / "rain_tin.mp3")

    # ---- 2. tape_hiss.mp3 : 45 s periodic soft hiss ------------------------
    dur = 45.0
    n = int(dur * sr)

    def periodic_hiss() -> np.ndarray:
        X = np.fft.rfft(rng.standard_normal(n))
        freqs = np.fft.rfftfreq(n, 1 / sr)
        H = 1.0 / np.sqrt(1.0 + (freqs / 4500.0) ** 4)
        phases = np.exp(1j * rng.uniform(0, 2 * np.pi, len(X)))
        return np.fft.irfft(np.abs(X) * H * phases, n)

    hiss = np.empty((n, 2))
    for ch in range(2):
        h = periodic_hiss()
        h *= (10 ** (-40 / 20)) / (np.sqrt(np.mean(h ** 2)) + 1e-12)
        hiss[:, ch] = h
    seam_check("tape_hiss", hiss)
    wav = BUILD / "tape_hiss.wav"
    sf.write(wav, hiss.astype(np.float32), sr)
    encode_mp3(wav, ASSETS / "tape_hiss.mp3", bitrate="96k")

    print("done.")


if __name__ == "__main__":
    main()
