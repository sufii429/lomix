#!/usr/bin/env python3
"""Lomix beats v4 — built from drum one-shots CUT OUT of Mazeen's own
reference (the exposed beats-only window of his Chill Rainy Lofi
remix, content 55-63 s). He pointed at drum-pad apps (Super Pads DJ,
Drum Pads Go): those beats are RECORDED sample kits, which pure
synthesis kept missing. So the kick and brush below are literally his
reference's drums, re-assembled into the measured 2-bar pattern:
brush on bar steps 4 & 12; kicks bar1 2,3,6,8,11,14 / bar2 8,11,14;
only a faint dark synthesized tick on top (his loop has no hats).
The hits carry a little of the reference's own rain/reverb with them
— that dust is part of why they blend. A gentle extra goonj is added
at loop level. 3 loops at 80 BPM, Ogg, seamless middle-cut render.
"""
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf
import librosa
from scipy.signal import butter, fftconvolve, sosfilt, sosfiltfilt

SR = 44100
BPM = 80.0
BEAT = 60.0 / BPM
STEP = BEAT / 4
LOOP = 8 * BEAT
# His reference measured MACHINE-TIGHT: every strong hit sits within
# ~5 ms of the same +35 ms laid-back offset — straight 8ths, no swing,
# no jitter. (The swing in Muse's synthesized replica was the "taal"
# difference Mazeen heard.) The laid-back feel comes from the app's
# +30 ms lag, not from timing inside the loop.
SWING = 0.50
ASSETS = Path(__file__).parent / "app" / "src" / "main" / "assets"
REF = "/home/hatch/workspace/user/files/Mann_Ki_Lagan_-_Chill_Rainy_Lofi_22_xs72.mp3"
W0 = 55.0  # window start in the reference


# ---------- extract one-shots from the reference ----------
y, _ = librosa.load(REF, sr=SR, mono=False, offset=W0, duration=9.0)
ym = y.mean(axis=0)


def onset_refine(clip_t, lo, hi):
    """Walk back from the flux peak to where the band envelope rises."""
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    b = sosfiltfilt(sos, ym)
    i_pk = int(clip_t * SR)
    win = b[max(0, i_pk - int(0.09 * SR)): i_pk + int(0.02 * SR)]
    env = np.abs(win)
    thr = env.max() * 0.12
    idx = np.where(env > thr)[0]
    start = max(0, i_pk - int(0.09 * SR)) + (idx[0] if len(idx) else 0)
    return start / SR


def cut(clip_t, lo, hi, dur):
    t0 = onset_refine(clip_t, lo, hi)
    i0 = int(t0 * SR)
    i1 = i0 + int(dur * SR)
    seg = y[:, i0:i1].copy()
    fade = int(0.06 * SR)
    seg[:, -fade:] *= np.linspace(1, 0, fade)
    seg = seg / (np.abs(seg).max() + 1e-9)
    print(f"cut at clip {t0:.3f}s dur {dur}s peak-ok")
    return seg


KICK = cut(0.81, 40, 150, 0.34)     # strongest isolated kick
KICK2 = cut(3.90, 40, 150, 0.34)
BRUSH = cut(6.99, 900, 3400, 0.46)  # strongest brush, 0.88 s clear after
BRUSH2 = cut(3.46, 900, 3400, 0.42)


def bp_noise(dur, lo, hi, seed):
    r = np.random.default_rng(seed)
    n = r.standard_normal(int(SR * dur))
    sos = butter(2, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfiltfilt(sos, n)


def tick_soft(seed=8):
    dur = 0.055
    t = np.arange(int(SR * dur)) / SR
    return bp_noise(dur, 1300, 3600, seed) * np.exp(-t * 62) * 0.30


TICK = tick_soft()


def shaker_soft():
    dur = 0.11
    t = np.arange(int(SR * dur)) / SR
    noise = bp_noise(dur, 4200, 8600, 9)
    return noise * (np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 2) * 0.10


SHAKER = shaker_soft()


# ---------- placement ----------
def place(buf, sample, start_s, gain=1.0, pan=0.0):
    i0 = int(round(start_s * SR))
    if i0 < 0:
        return
    if sample.ndim == 1:
        sample = np.stack([sample, sample])
    i1 = min(buf.shape[1], i0 + sample.shape[1])
    if i1 <= i0:
        return
    l = np.cos((pan + 1) * np.pi / 4)
    r = np.sin((pan + 1) * np.pi / 4)
    buf[0, i0:i1] += sample[0, : i1 - i0] * gain * l * 1.414
    buf[1, i0:i1] += sample[1, : i1 - i0] * gain * r * 1.414


def swung(step_idx):
    t = step_idx * STEP
    if (step_idx % 4) == 2:
        t += (SWING - 0.5) * 2 * STEP
    return t


def humanize(events, seed):
    """Velocity breathes a little; timing stays exactly on the grid."""
    r = np.random.default_rng(seed)
    out = []
    for step, sample, gain, pan in events:
        out.append((step, sample, gain * float(r.uniform(0.96, 1.02)),
                    pan, 0.0))
    return out


def pattern_standard():
    ev = []
    kicks = {0: [(2, KICK, 0.90), (3, KICK2, 0.80), (6, KICK, 0.95),
                 (8, KICK, 0.90), (11, KICK2, 0.85), (14, KICK, 0.95)],
             1: [(8, KICK, 0.95), (11, KICK2, 0.85), (14, KICK, 0.95)]}
    for bar in range(2):
        o = bar * 16
        for s, smp, g in kicks[bar]:
            ev.append((o + s, smp, g, 0.0))
        ev += [(o + 4, BRUSH, 1.0, -0.03), (o + 12, BRUSH2, 1.0, -0.03)]
        for h in range(0, 16, 2):
            ev.append((o + h, TICK, 0.55, 0.15))
    return humanize(ev, 22)


def pattern_sparse():
    ev = []
    for bar in range(2):
        o = bar * 16
        ev.append((o + 8, KICK, 0.95, 0.0))
        if bar == 0:
            ev.append((o + 14, KICK, 0.90, 0.0))
        ev += [(o + 4, BRUSH, 1.0, -0.03), (o + 12, BRUSH2, 1.0, -0.03)]
        for h in (2, 6, 10, 14):
            ev.append((o + h, TICK, 0.45, 0.15))
    return humanize(ev, 21)


def pattern_fuller():
    ev = []
    kicks = {0: [(0, KICK, 0.80), (2, KICK, 0.90), (3, KICK2, 0.80),
                 (6, KICK, 0.95), (8, KICK, 0.90), (11, KICK2, 0.85), (14, KICK, 0.95)],
             1: [(0, KICK, 0.80), (8, KICK, 0.95), (11, KICK2, 0.85), (14, KICK, 0.95)]}
    for bar in range(2):
        o = bar * 16
        for s, smp, g in kicks[bar]:
            ev.append((o + s, smp, g, 0.0))
        ev += [(o + 4, BRUSH, 1.0, -0.03), (o + 12, BRUSH2, 1.0, -0.03)]
        for h in range(16):
            ev.append((o + h, TICK, 0.5 if h % 2 == 0 else 0.32, 0.15))
        for s in (0, 4, 8, 12):
            ev.append((o + s, SHAKER, 0.35, -0.22))
    ev += [(16 + 15, BRUSH2, 0.42, -0.03)]
    return humanize(ev, 23)


# ---------- gentle extra goonj ----------
def make_ir(seed):
    dur = 1.8
    n = int(SR * dur)
    r = np.random.default_rng(seed)
    noise = r.standard_normal(n)
    out = np.zeros(n)
    seg = n // 6
    for s in range(6):
        cutoff = [4600, 3600, 2750, 2100, 1600, 1250][s]
        sos = butter(1, cutoff, btype="low", fs=SR, output="sos")
        out[s * seg:(s + 1) * seg] = sosfilt(sos, noise[s * seg:(s + 1) * seg])
    t = np.arange(n) / SR
    out *= np.exp(-t * 3.2) * (1 - np.exp(-t * 90))
    return out / (np.max(np.abs(out)) + 1e-9)


IR_L = make_ir(31)
IR_R = make_ir(47)
WET = 0.22


def render(events):
    total = LOOP * 3 + 0.6
    buf = np.zeros((2, int(SR * total)), dtype=np.float64)
    for rep in range(3):
        base = rep * LOOP
        for step, sample, gain, pan, jitter in events:
            place(buf, sample, base + swung(step) + jitter, gain, pan)
    buf = np.tanh(buf * 1.05) / 1.05
    sos = butter(1, 6800, btype="low", fs=SR, output="sos")
    dry = sosfiltfilt(sos, buf, axis=1)
    wet = np.vstack([
        fftconvolve(dry[0], IR_L)[: dry.shape[1]],
        fftconvolve(dry[1], IR_R)[: dry.shape[1]],
    ]) * WET
    mix = dry + wet
    peak = np.max(np.abs(mix)) + 1e-9
    mix *= 0.80 / peak
    return mix[:, int(SR * LOOP): int(SR * 2 * LOOP)].astype(np.float32)


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
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(wav),
                    "-c:a", "libvorbis", "-q:a", "5", str(ogg)], check=True)
    wav.unlink()
    c, hf, wrap, typical = stats(audio)
    print(f"{name}: centroid {c:.0f} Hz, >6kHz {hf:.1f}%, seam {wrap:.4f}/{typical:.4f} -> {ogg.stat().st_size} bytes")


def main():
    save_ogg(render(pattern_sparse()), "beats_c")
    save_ogg(render(pattern_standard()), "beats_a")
    save_ogg(render(pattern_fuller()), "beats_b")
    print("done.")


if __name__ == "__main__":
    main()
