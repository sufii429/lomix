#!/usr/bin/env python3
"""Generate Lomix's lofi drum loops — v2, the "vibe fix" (v1.2.1).

Mazeen's verdict on the v1.2.0 loops: the auto-sync worked, but the
drums felt separate from the song. His three ingredients for the demo
mix's magic: SOFTNESS of sound, LIGHT AIRY beats, and REVERB (goonj) —
under one character: "peaceful rainy chill lofi mix". Measured target
from his own remix of Mann Ki Lagan: spectral centroid ~1290 Hz and
only ~5% of energy above 6 kHz (the v1 loops measured ~1560 Hz / 13%
— far too bright and hard).

So v2 is a different kit and a different attitude:
  - kick   : soft thump, attack ramped (no click), 95 -> 38 Hz body
  - snare  : a brush sweep (dark filtered noise) + soft body tone;
             rim pings for the sparse pattern
  - hats   : darker (5.4 kHz floor), quieter, mostly offbeat = airy
  - reverb : a generated decaying-noise IR convolved over the whole
             loop (goonj baked in, identical on every device), and a
             whisper of circular vinyl dust on top
  - swing 60%, small per-hit velocity/timing humanisation
  - everything lowpassed ~6.8 kHz, soft-clipped, gentle

Three seamless 2-bar loops at 80 BPM (6.000 s), one per fullness:
  beats_c.ogg - sparse  (Smooth style)
  beats_a.ogg - standard soft (Balanced / Warm / Bass Boost)
  beats_b.ogg - fuller soft (Dynamic) — fuller, never harder

Rendered as THREE copies and cut to the middle one so every decay and
reverb tail wraps seamlessly. Ogg Vorbis (gapless; mp3 is not).

Run:  python make_beats.py        (writes into app/src/main/assets/)
"""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, fftconvolve, sosfilt, sosfiltfilt

SR = 44100
BPM = 80.0
BEAT = 60.0 / BPM            # 0.75 s
BAR = 4 * BEAT               # 3.0 s
LOOP = 2 * BAR               # 6.0 s
STEP = BEAT / 4              # 16th note
SWING = 0.50                 # straight: his reference measured machine-tight
rng = np.random.default_rng(11)

ASSETS = Path(__file__).parent / "app" / "src" / "main" / "assets"


def bp_noise(dur, lo, hi, seed):
    r = np.random.default_rng(seed)
    n = r.standard_normal(int(SR * dur))
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfiltfilt(sos, n)


# ---------- voices measured against his reference (v3) ----------
# His exposed beats-only window (remix content 56-60 s, 68 BPM grid)
# measured hit by hit:
#   kick  = soft KNOCK, centroid ~1400-1650 Hz, ~50% of energy below
#           150 Hz, ~70% left at +0.1 s and ~30-45% at +0.2 s — a
#           woody knock with a mid tap, not a clean sub sine
#   brush = centroid ~1800 Hz, ~35% of energy in 900-3400 Hz, with a
#           long reverb tail (still ~90% energy at +0.35 s)
#   hats  = NO bright hats at all: no hit in the window has more than
#           15% of energy above 6 kHz — only a faint dark tick wash
#           (centroid ~2000 Hz, about 1/3 of the main hits' strength)
def kick_soft():
    dur = 0.30
    t = np.arange(int(SR * dur)) / SR
    freq = 44 + 86 * np.exp(-t * 22)
    phase = np.cumsum(freq) / SR * 2 * np.pi
    attack = 1 - np.exp(-t * 130)
    body = np.sin(phase) * np.exp(-t * 11) * attack * 0.85
    knock = bp_noise(dur, 280, 1600, 5) * np.exp(-t * 38) * 0.55
    return body + knock


def brush(gain=1.0, seed=6):
    dur = 0.26
    t = np.arange(int(SR * dur)) / SR
    noise = bp_noise(dur, 700, 3200, seed)
    env = (1 - np.exp(-t * 120)) * np.exp(-t * 11)
    tone = np.sin(2 * np.pi * 185 * t) * np.exp(-t * 28) * 0.35
    return (noise * env * 1.3 + tone) * 0.55 * gain


def tick_soft(seed=8):
    """The faint dark tick that replaces hats in his reference."""
    dur = 0.055
    t = np.arange(int(SR * dur)) / SR
    noise = bp_noise(dur, 1300, 3600, seed)
    return noise * np.exp(-t * 62) * 0.34


def rim():
    dur = 0.08
    t = np.arange(int(SR * dur)) / SR
    ping = np.sin(2 * np.pi * 1560 * t) * np.exp(-t * 58) * 0.30
    tick = bp_noise(dur, 1500, 2400, 7) * np.exp(-t * 95) * 0.20
    return ping + tick


def hat_soft(open_hat=False, seed=8):
    dur = 0.38 if open_hat else 0.075
    t = np.arange(int(SR * dur)) / SR
    r = np.random.default_rng(seed)
    n = r.standard_normal(len(t))
    sos = butter(2, 5000, btype="high", fs=SR, output="sos")
    hp = sosfiltfilt(sos, n)
    decay = 10 if open_hat else 52
    return hp * np.exp(-t * decay) * (0.16 if open_hat else 0.20)


def shaker_soft():
    dur = 0.11
    t = np.arange(int(SR * dur)) / SR
    noise = bp_noise(dur, 4200, 8600, 9)
    env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2
    return noise * env * 0.11


KICK = kick_soft()
BRUSH = brush()
BRUSH_GHOST = brush(gain=0.42, seed=16)
RIM = rim()
TICK = tick_soft()
HAT_C = hat_soft()
HAT_O = hat_soft(open_hat=True)
SHAKER = shaker_soft()


# ---------- placement ----------
def place(buf, sample, start_s, pan=0.0):
    i0 = int(round(start_s * SR))
    trim = 0
    if i0 < 0:  # humanise jitter can push a step-0 hit slightly early
        trim = -i0
        i0 = 0
    i1 = min(buf.shape[1], i0 + (len(sample) - trim))
    if i1 <= i0:
        return
    seg = sample[trim: trim + (i1 - i0)]
    l = np.cos((pan + 1) * np.pi / 4)
    r = np.sin((pan + 1) * np.pi / 4)
    buf[0, i0:i1] += seg * l
    buf[1, i0:i1] += seg * r


def swung(step_idx):
    t = step_idx * STEP
    if (step_idx % 4) == 2:  # off-8th gets the swing delay
        t += (SWING - 0.5) * 2 * STEP
    return t


def humanize(events, seed):
    """Gentle velocity variation; timing stays (near-)grid — the
    reference's hits measured within ~5 ms of one uniform offset."""
    r = np.random.default_rng(seed)
    out = []
    for step, sample, gain, pan in events:
        out.append((step, sample, gain * float(r.uniform(0.94, 1.03)),
                    pan, float(r.uniform(-0.0015, 0.0015))))
    return out


# ---------- patterns (32 steps = 2 bars) ----------
# v1.2.2 "universal recipe" (Mazeen's workaround A): the v1.2.1 loops
# copied ONE song's own drums and its intricate arrangement, and on
# another song they fought that song's sur and groove ("bilkul bhi
# acha nahi laga"). Beats that sit under ANY song use the opposite
# recipe — a very simple, regular pattern and a neutral kit:
# kick on beat 1, brush on the 2 & 4 backbeat, one soft kick on the
# "&" of 3, tick kept faint and sparse (he heard marching ticks as a
# "1,2,3,4 exercise" count). Same both bars, almost no variation.
def pattern_standard():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev += [(o + 0, KICK, 1.0, 0.0), (o + 10, KICK, 0.88, 0.0)]
        ev += [(o + 4, BRUSH, 1.0, -0.04), (o + 12, BRUSH, 1.0, -0.04)]
        for h in (2, 6, 10, 14):
            ev.append((o + h, TICK, 0.38, 0.15))
    ev += [(16 + 7, KICK, 0.50, 0.0)]
    return humanize(ev, 22)


def pattern_sparse():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev.append((o + 0, KICK, 1.0, 0.0))
        ev += [(o + 4, BRUSH, 1.0, -0.04), (o + 12, BRUSH, 1.0, -0.04)]
        for h in (6, 14):
            ev.append((o + h, TICK, 0.30, 0.15))
    return humanize(ev, 21)


def pattern_fuller():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev += [(o + 0, KICK, 1.0, 0.0), (o + 7, KICK, 0.60, 0.0),
               (o + 10, KICK, 0.90, 0.0)]
        ev += [(o + 4, BRUSH, 1.0, -0.04), (o + 12, BRUSH, 1.0, -0.04),
               (o + 15, BRUSH_GHOST, 1.0, -0.04)]
        for h in range(0, 16, 2):
            ev.append((o + h, TICK, 0.42 if h % 4 == 0 else 0.30, 0.15))
    return humanize(ev, 23)


# ---------- reverb (goonj) ----------
def make_ir(seed):
    """2.2 s decaying-noise stereo IR, darker as it decays (his
    reference's brush tails are long — energy still ~90% at +0.35 s
    in the exposed window, most of which is this goonj)."""
    dur = 2.2
    n = int(SR * dur)
    r = np.random.default_rng(seed)
    noise = r.standard_normal(n)
    out = np.zeros(n)
    seg = n // 6
    for s in range(6):
        cutoff = [4800, 3800, 2900, 2200, 1650, 1250][s]
        sos = butter(1, cutoff, btype="low", fs=SR, output="sos")
        part = sosfilt(sos, noise[s * seg:(s + 1) * seg])
        out[s * seg:(s + 1) * seg] = part
    t = np.arange(n) / SR
    out *= np.exp(-t * 2.8)
    out *= (1 - np.exp(-t * 90))           # 11 ms bloom, keeps hits clear
    return out / (np.max(np.abs(out)) + 1e-9)


IR_L = make_ir(31)
IR_R = make_ir(47)
WET = 0.32


def dust_ring():
    """Loop-length circular vinyl dust: periodic by construction."""
    n = int(SR * LOOP)
    ring = np.zeros(n)
    r = np.random.default_rng(53)
    sos = butter(1, 4800, btype="low", fs=SR, output="sos")
    ring += sosfiltfilt(sos, r.standard_normal(n)) * 0.006
    for _ in range(110):
        pos = int(r.integers(0, n))
        ln = int(SR * r.uniform(0.001, 0.0035))
        amp = float(r.uniform(0.004, 0.02)) * (1 if r.random() > 0.5 else -1)
        click = amp * np.exp(-np.arange(ln) / (ln / 3))
        idx = (pos + np.arange(ln)) % n
        ring[idx] += click
    return ring


DUST = dust_ring()


def render(events):
    total = LOOP * 3 + 0.6
    buf = np.zeros((2, int(SR * total)), dtype=np.float64)
    for rep in range(3):
        base = rep * LOOP
        for step, sample, gain, pan, jitter in events:
            place(buf, sample * gain, base + swung(step) + jitter, pan)
    # soft glue + darkness
    buf = np.tanh(buf * 1.08) / 1.08
    sos = butter(1, 6400, btype="low", fs=SR, output="sos")
    dry = sosfiltfilt(sos, buf, axis=1)
    # baked reverb
    wet = np.vstack([
        fftconvolve(dry[0], IR_L)[: dry.shape[1]],
        fftconvolve(dry[1], IR_R)[: dry.shape[1]],
    ]) * WET
    mix = dry + wet
    peak = np.max(np.abs(mix)) + 1e-9
    mix *= 0.80 / peak
    mid = mix[:, int(SR * LOOP): int(SR * 2 * LOOP)].copy()
    mid[0] += DUST
    mid[1] += DUST * 0.9
    return mid.astype(np.float32)


def stats(audio):
    mono = audio.mean(axis=0)
    mag = np.abs(np.fft.rfft(mono * np.hanning(len(mono))))
    freqs = np.fft.rfftfreq(len(mono), 1 / SR)
    centroid = float((freqs * mag).sum() / mag.sum())
    hf = float(mag[freqs > 6000].sum() / mag.sum() * 100)
    wrap = abs(float(mono[0]) - float(mono[-1]))
    typical = float(np.percentile(np.abs(np.diff(mono)), 99))
    return centroid, hf, wrap, typical


def save_ogg(audio, name):
    wav = ASSETS / (name + ".wav")
    ogg = ASSETS / (name + ".ogg")
    sf.write(wav, audio.T, SR)
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", str(wav),
         "-c:a", "libvorbis", "-q:a", "5", str(ogg)],
        check=True,
    )
    wav.unlink()
    c, hf, wrap, typical = stats(audio)
    print(f"{name}: centroid {c:.0f} Hz, >6kHz {hf:.1f}%  (target ~1290 Hz / ~5%)")
    print(f"   seam wrap-step {wrap:.4f} (typical max {typical:.4f}) -> {ogg} ({ogg.stat().st_size} bytes)")


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    save_ogg(render(pattern_sparse()), "beats_c")
    save_ogg(render(pattern_standard()), "beats_a")
    save_ogg(render(pattern_fuller()), "beats_b")
    print("done.")


if __name__ == "__main__":
    main()
