#!/usr/bin/env python3
"""Generate Lomix's lofi drum loops (v1.2 beats update).

Two seamless 2-bar loops at a fixed base tempo of 80 BPM (6.000 s):
  beats_a.ogg - standard boom-bap lofi pattern (most styles)
  beats_b.ogg - busier "dynamic" pattern (ghost notes, extra 16ths)

The app time-stretches these to the detected song tempo at runtime, so
the base tempo only sets the stretch centre. Loops are rendered as
THREE consecutive copies of the pattern and the middle copy is cut
out, which makes every decay tail wrap perfectly = a truly seamless
loop. Output is Ogg Vorbis (gapless, sample-exact length, unlike mp3).

Run:  python make_beats.py        (writes into app/src/main/assets/)
"""
import subprocess
from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfiltfilt

SR = 44100
BPM = 80.0
BEAT = 60.0 / BPM            # 0.75 s
BAR = 4 * BEAT               # 3.0 s
LOOP = 2 * BAR               # 6.0 s
STEP = BEAT / 4              # 16th note
SWING = 0.57                 # off-8th swing
rng = np.random.default_rng(42)

ASSETS = Path(__file__).parent / "app" / "src" / "main" / "assets"


# ---------- one-shot drum voices (mono float32) ----------
def kick(dur=0.30):
    t = np.arange(int(SR * dur)) / SR
    freq = 43 + 75 * np.exp(-t * 26)
    phase = np.cumsum(freq) / SR * 2 * np.pi
    body = np.sin(phase) * np.exp(-t * 10.5)
    click = rng.standard_normal(int(SR * 0.004)) * np.exp(-np.arange(int(SR * 0.004)) / 40.0)
    out = body.copy()
    out[: len(click)] += click * 0.35
    return out * 0.95


def snare(dur=0.16, gain=1.0):
    t = np.arange(int(SR * dur)) / SR
    noise = rng.standard_normal(len(t))
    sos = butter(2, [1400, 5200], btype="band", fs=SR, output="sos")
    bp = sosfiltfilt(sos, noise) * np.exp(-t * 26)
    tone = np.sin(2 * np.pi * 186 * t) * np.exp(-t * 34) * 0.55
    return (bp * 1.6 + tone) * 0.5 * gain


def hat(open_hat=False, gain=1.0):
    dur = 0.30 if open_hat else 0.05
    t = np.arange(int(SR * dur)) / SR
    noise = rng.standard_normal(len(t))
    sos = butter(2, 7600, btype="high", fs=SR, output="sos")
    hp = sosfiltfilt(sos, noise)
    decay = 11 if open_hat else 68
    return hp * np.exp(-t * decay) * 0.32 * gain


def shaker(gain=1.0):
    t = np.arange(int(SR * 0.10)) / SR
    noise = rng.standard_normal(len(t))
    sos = butter(2, [4800, 9800], btype="band", fs=SR, output="sos")
    bp = sosfiltfilt(sos, noise)
    env = np.sin(np.pi * np.clip(t / 0.10, 0, 1)) ** 2
    return bp * env * 0.20 * gain


KICK, SNARE, HAT_C, HAT_O, SHAKER = kick(), snare(), hat(), hat(True), shaker()
GHOST = snare(gain=0.34)


def place(buf, sample, start_s, pan=0.0):
    """Add a mono sample into the stereo buffer with constant-power pan."""
    i0 = int(round(start_s * SR))
    i1 = min(buf.shape[1], i0 + len(sample))
    if i0 >= buf.shape[1]:
        return
    seg = sample[: i1 - i0]
    l = np.cos((pan + 1) * np.pi / 4)
    r = np.sin((pan + 1) * np.pi / 4)
    buf[0, i0:i1] += seg * l
    buf[1, i0:i1] += seg * r


def swung(step_idx):
    """Time (s) of a 16th step inside one 2-bar loop, with 8th swing."""
    t = step_idx * STEP
    if (step_idx % 4) == 2:  # the off-8th inside each beat pair
        t += (SWING - 0.5) * 2 * STEP
    return t


def render(pattern):
    """pattern: list of (step, sample, gain, pan) over 32 steps (2 bars)."""
    total = LOOP * 3 + 0.5
    buf = np.zeros((2, int(SR * total)), dtype=np.float64)
    for rep in range(3):
        base = rep * LOOP
        for step, sample, gain, pan in pattern:
            place(buf, sample * gain, base + swung(step), pan)
    # gentle lofi glue: soft saturation + top rolloff
    buf = np.tanh(buf * 1.15) / 1.15
    sos = butter(1, 9800, btype="low", fs=SR, output="sos")
    buf = sosfiltfilt(sos, buf, axis=1)
    peak = np.max(np.abs(buf)) + 1e-9
    buf *= 0.82 / peak
    mid = buf[:, int(SR * LOOP): int(SR * 2 * LOOP)]
    return mid.astype(np.float32)


def pattern_a():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev += [(o + 0, KICK, 1.0, 0.0), (o + 10, KICK, 0.9, 0.0)]
        ev += [(o + 4, SNARE, 1.0, 0.0), (o + 12, SNARE, 1.0, 0.0)]
        for h in range(0, 16, 2):  # 8th hats, alternating accents
            ev.append((o + h, HAT_C, 1.0 if h % 4 == 0 else 0.62, 0.18))
        ev += [(o + 7, HAT_C, 0.4, 0.18), (o + 15, HAT_C, 0.4, 0.18)]
    ev += [(16 + 7, KICK, 0.8, 0.0)]        # bar-2 pickup kick
    ev += [(16 + 14, HAT_O, 0.8, 0.18)]     # bar-2 open hat
    return ev


def pattern_b():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev += [(o + 0, KICK, 1.0, 0.0), (o + 7, KICK, 0.85, 0.0), (o + 10, KICK, 0.95, 0.0)]
        ev += [(o + 4, SNARE, 1.0, 0.0), (o + 12, SNARE, 1.0, 0.0)]
        ev += [(o + 15, GHOST, 1.0, -0.08)]
        for h in range(0, 16, 2):
            ev.append((o + h, HAT_C, 1.0 if h % 4 == 0 else 0.6, 0.18))
        ev += [(o + 3, HAT_C, 0.38, 0.18), (o + 11, HAT_C, 0.38, 0.18),
               (o + 14, HAT_O, 0.75, 0.18)]
        for s in range(0, 16, 4):  # quiet shaker on the beats
            ev.append((o + s, SHAKER, 0.55, -0.22))
    ev += [(16 + 3, GHOST, 1.0, -0.08)]     # bar-2 ghost snare
    return ev


def save_ogg(audio, name):
    wav = ASSETS / (name + ".wav")
    ogg = ASSETS / (name + ".ogg")
    import soundfile as sf
    sf.write(wav, audio.T, SR)
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", str(wav),
         "-c:a", "libvorbis", "-q:a", "5", str(ogg)],
        check=True,
    )
    wav.unlink()
    # seam check: sample-to-sample step across the wrap point
    mono = audio.mean(axis=0)
    wrap = abs(float(mono[0]) - float(mono[-1]))
    typical = float(np.percentile(np.abs(np.diff(mono)), 99))
    print(f"{name}: {len(mono)/SR:.3f}s  wrap-step {wrap:.4f} (typical max {typical:.4f})")
    print(f"   -> {ogg} ({ogg.stat().st_size} bytes)")


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    save_ogg(render(pattern_a()), "beats_a")
    save_ogg(render(pattern_b()), "beats_b")
    print("done.")


if __name__ == "__main__":
    main()
