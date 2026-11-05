#!/usr/bin/env python3
"""Generate Lomix's ambient loops (v1.4.0): fireplace, ocean waves,
night crickets + breeze, cafe murmur. All synthesized, all 60 s and
seamless BY CONSTRUCTION: noise beds are built in the frequency
domain (a DFT of the loop length, so they are exactly periodic),
one-shot sounds are placed on a ring (indices wrap), and envelopes
use whole cycles per loop. Written as mp3 into app assets.
"""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
DUR = 60.0
N = int(SR * DUR)
ASSETS = Path(__file__).parent / "app" / "src" / "main" / "assets"
rng = np.random.default_rng(77)
FREQS = np.fft.rfftfreq(N, 1 / SR)


def periodic_white():
    phases = rng.uniform(0, 2 * np.pi, N // 2 + 1)
    X = np.exp(1j * phases)
    X[0] = 0
    x = np.fft.irfft(X, N)
    return x / (np.std(x) + 1e-9)


def shape_lp(x, fc, order=2):
    resp = 1.0 / np.sqrt(1.0 + (FREQS / fc) ** (2 * order))
    resp[0] = 0.0
    return np.fft.irfft(np.fft.rfft(x) * resp, N)


def shape_bp(x, lo, hi):
    resp = np.zeros_like(FREQS)
    m = (FREQS >= lo) & (FREQS <= hi)
    # smooth band edges (raised-cosine over half an octave-ish)
    resp[m] = 1.0
    edge = 0.35
    lo_m = (FREQS >= lo * (1 - edge)) & (FREQS < lo)
    hi_m = (FREQS > hi) & (FREQS <= hi * (1 + edge))
    resp[lo_m] = 0.5 - 0.5 * np.cos(np.pi * (FREQS[lo_m] - lo * (1 - edge)) / (lo * edge))
    resp[hi_m] = 0.5 + 0.5 * np.cos(np.pi * (FREQS[hi_m] - hi) / (hi * edge))
    return np.fft.irfft(np.fft.rfft(x) * resp, N)


def norm(x, peak):
    return x / (np.abs(x).max() + 1e-9) * peak


def place_ring(out, body, pos):
    idx = (pos + np.arange(len(body))) % N
    out[idx] += body


def fireplace():
    out = shape_lp(periodic_white(), 200) * 0.55          # warm rumble bed
    t = 0.0
    raw = rng.standard_normal(int(SR * 0.2))
    while t < DUR:
        t += float(rng.exponential(0.14))
        pos = int((t % DUR) * SR)
        size = float(rng.random())
        if size < 0.86:
            ln = int(SR * rng.uniform(0.002, 0.008))
            amp = rng.uniform(0.10, 0.34)
            body = raw[:ln] * np.exp(-np.arange(ln) / (ln / 2.6))
            body = shape_lp(np.concatenate([body, np.zeros(N - ln)]), 3800)[:ln] if ln < 4000 else body
        elif size < 0.97:
            ln = int(SR * rng.uniform(0.01, 0.03))
            amp = rng.uniform(0.3, 0.55)
            body = raw[:ln] * np.exp(-np.arange(ln) / (ln / 2.2))
        else:
            ln = int(SR * rng.uniform(0.05, 0.11))
            amp = rng.uniform(0.4, 0.6)
            tt = np.arange(ln) / SR
            body = (np.sin(2 * np.pi * 120 * tt) * np.exp(-tt * 42)
                    + raw[:ln] * np.exp(-tt * 90) * 0.7)
        place_ring(out, body * amp, pos)
    return norm(out, 0.75)


def ocean():
    t = np.arange(N) / SR
    env = (0.55 + 0.30 * np.sin(2 * np.pi * 5 * t / DUR)
           + 0.18 * np.sin(2 * np.pi * 8 * t / DUR + 1.3)
           + 0.10 * np.sin(2 * np.pi * 13 * t / DUR + 4.0))
    noise = shape_bp(periodic_white(), 300, 950)
    wash = shape_lp(periodic_white(), 420)
    return norm(noise * env + wash * 0.35 * (0.6 + 0.4 * env), 0.7)


def crickets():
    t = np.arange(N) / SR
    breeze_env = 0.5 + 0.5 * np.sin(2 * np.pi * 3 * t / DUR + 2.0)
    out = shape_lp(periodic_white(), 900) * 0.045 * (0.4 + 0.6 * breeze_env)
    out += shape_lp(periodic_white(), 300) * 0.05
    for freq, start, gap_lo, gap_hi, gain in (
        (4300, 0.0, 0.9, 1.7, 0.16), (4550, 7.0, 1.2, 2.2, 0.11), (4150, 19.0, 1.5, 2.8, 0.09)
    ):
        tt = start
        while tt < DUR:
            pulses = int(rng.integers(3, 6))
            for p in range(pulses):
                pos = int((tt + p * 0.085) * SR)
                ln = int(SR * 0.045)
                seg_t = np.arange(ln) / SR
                tone = np.sin(2 * np.pi * freq * seg_t) * np.sin(np.pi * seg_t / (ln / SR)) ** 2
                place_ring(out, tone * gain, pos)
            tt += float(rng.uniform(gap_lo, gap_hi)) + pulses * 0.085
    return norm(out, 0.6)


def cafe():
    base = shape_bp(periodic_white(), 350, 1400)
    wiggle = np.abs(shape_lp(periodic_white(), 2.2))
    wiggle = wiggle / (wiggle.max() + 1e-9)
    out = base * (0.25 + 0.75 * wiggle) * 0.8
    out += shape_lp(periodic_white(), 500) * 0.10
    for _ in range(9):
        pos = int(rng.integers(0, N))
        freq = float(rng.uniform(2100, 3400))
        ln = int(SR * rng.uniform(0.05, 0.12))
        tt = np.arange(ln) / SR
        clink = np.sin(2 * np.pi * freq * tt) * np.exp(-tt * 55) * rng.uniform(0.03, 0.07)
        place_ring(out, clink, pos)
    return norm(out, 0.55)


def save(x, name):
    # Mono at 80 kbps: these are textures, not music — mono keeps each
    # loop near 600 KB so its base64 form stays push-friendly.
    wav = ASSETS / (name + ".wav")
    mp3 = ASSETS / (name + ".mp3")
    sf.write(wav, x.astype(np.float32), SR)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(wav),
                    "-c:a", "libmp3lame", "-b:a", "80k", str(mp3)], check=True)
    wav.unlink()
    wrap = abs(float(x[0]) - float(x[-1]))
    typ = float(np.percentile(np.abs(np.diff(x)), 99))
    print(f"{name}: {mp3.stat().st_size} bytes, seam step {wrap:.4f} vs typical {typ:.4f}, rms {np.sqrt(np.mean(x**2)):.3f}")


save(fireplace(), "amb_fire")
save(ocean(), "amb_ocean")
save(crickets(), "amb_crickets")
save(cafe(), "amb_cafe")
print("done.")
