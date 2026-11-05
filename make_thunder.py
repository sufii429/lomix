#!/usr/bin/env python3
"""Thunder layer for Lomix's Mahaul tab (v1.5.0): a 90 s seamless loop
of near-silence with a few distant, rolling thunder rumbles placed on
a ring (indices wrap, envelopes are smooth), over a faint low bed.
Synthesized like the other ambience loops; mono 80 kbps mp3.
"""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
DUR = 90.0
N = int(SR * DUR)
ASSETS = Path(__file__).parent / "app" / "src" / "main" / "assets"
rng = np.random.default_rng(99)
FREQS = np.fft.rfftfreq(N, 1 / SR)


def periodic_white():
    phases = rng.uniform(0, 2 * np.pi, N // 2 + 1)
    X = np.exp(1j * phases)
    X[0] = 0
    x = np.fft.irfft(X, N)
    return x / (np.std(x) + 1e-9)


def shape_lp(x, fc, order=3):
    resp = 1.0 / np.sqrt(1.0 + (FREQS / fc) ** (2 * order))
    resp[0] = 0.0
    return np.fft.irfft(np.fft.rfft(x) * resp, N)


def place_ring(out, body, pos):
    idx = (pos + np.arange(len(body))) % N
    out[idx] += body


out = shape_lp(periodic_white(), 80) * 0.045  # faint low bed
starts = [4.0, 23.0, 41.0, 58.0, 76.0]
for s in starts:
    dur = float(rng.uniform(3.2, 5.5))
    ln = int(SR * dur)
    t = np.arange(ln) / SR
    # rolling rumble: slow attack, long decay, inner swells
    env = (np.exp(-t / (dur * 0.42)) * (1 - np.exp(-t / 0.55))
           * (0.75 + 0.25 * np.sin(2 * np.pi * t / rng.uniform(1.4, 2.2))))
    burst = shape_lp(periodic_white(), int(rng.uniform(95, 150)))[:ln]
    burst = burst / (np.abs(burst).max() + 1e-9)
    place_ring(out, burst * env * rng.uniform(0.5, 0.75), int(s * SR))
    # a softer echo roll a moment later, half the time
    if rng.random() < 0.6:
        place_ring(out, burst * env * 0.3, int((s + rng.uniform(1.0, 1.8)) * SR))

peak = np.abs(out).max()
out = out / peak * 0.85
wav = ASSETS / "amb_thunder.wav"
mp3 = ASSETS / "amb_thunder.mp3"
sf.write(wav, out.astype(np.float32), SR)
subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(wav),
                "-c:a", "libmp3lame", "-b:a", "80k", str(mp3)], check=True)
wav.unlink()
wrap = abs(float(out[0]) - float(out[-1]))
print(f"amb_thunder: {mp3.stat().st_size} bytes, seam step {wrap:.4f}, rms {np.sqrt(np.mean(out**2)):.3f}, peak {peak:.2f}")
