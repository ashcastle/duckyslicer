#!/usr/bin/env python3
"""Synthesizes the promo soundtrack: a 120 BPM groove whose bars line up with the
scene cuts in index.html, plus UI sound effects placed from out/sfx.json (exported
by render.mjs from window.SFX). Writes out/audio.wav (44.1 kHz, 16-bit stereo).

Requires numpy and scipy. Everything is generated procedurally; no samples."""
import json
import os
import wave

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
SR = 44100
cues = json.load(open(os.path.join(OUT, "sfx.json")))
DUR = cues["duration"] + 0.6
N = int(DUR * SR)
rng = np.random.default_rng(7)

BEAT = 0.5          # 120 BPM
DOWN = 3.5          # first downbeat of the main groove (logo reveal)
GROOVE_END = 31.5   # final hit
MIDI = lambda m: 440.0 * 2 ** ((m - 69) / 12)

music = np.zeros((2, N))
fx = np.zeros((2, N))
send = np.zeros((2, N))  # reverb send


def mx(*xs):
    """sum arrays of different lengths (zero-padded)."""
    out = np.zeros(max(len(x) for x in xs))
    for x in xs:
        out[: len(x)] += x
    return out


def tt(d):
    return np.arange(int(d * SR)) / SR


def add(bus, t0, x, g=1.0, pan=0.0, rev=0.0):
    i = int(round(t0 * SR))
    if i >= N or i + len(x) <= 0:
        return
    if i < 0:
        x, i = x[-i:], 0
    x = x[: N - i]
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    bus[0, i:i + len(x)] += x * g * l * 1.414
    bus[1, i:i + len(x)] += x * g * r * 1.414
    if rev:
        send[0, i:i + len(x)] += x * g * rev
        send[1, i:i + len(x)] += x * g * rev


def filt(x, kind, f, order=2):
    sos = signal.butter(order, f, btype=kind, fs=SR, output="sos")
    return signal.sosfilt(sos, x)


def noise(d):
    return rng.standard_normal(int(d * SR))


def saw(f, d, detune=0.0):
    t = tt(d)
    ph = (f * (1 + detune) * t + rng.random()) % 1.0
    return 2 * ph - 1


def env(d, a=0.005, r=0.2, curve=1.0):
    n = int(d * SR)
    e = np.ones(n)
    na = max(1, int(a * SR))
    e[:na] = np.linspace(0, 1, na)
    nr = min(n, int(r * SR))
    e[n - nr:] *= np.linspace(1, 0, nr) ** curve
    return e


# ---------------------------------------------------------------- drums
def kick(g=1.0):
    t = tt(0.45)
    f = 48 + 110 * np.exp(-t * 32)
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = np.sin(ph) * np.exp(-t * 7.5)
    x[: int(0.004 * SR)] += filt(noise(0.004), "highpass", 2000) * 0.5
    return np.tanh(x * 1.6) * g


def clap():
    x = np.zeros(int(0.35 * SR))
    for k, o in enumerate([0, 0.011, 0.022]):
        n = filt(noise(0.3), "bandpass", [900, 2600]) * np.exp(-tt(0.3) * (60 if k < 2 else 14))
        i = int(o * SR)
        x[i:i + len(n)] += n[: len(x) - i]
    return x * 0.55


def hat(open_=False):
    d = 0.22 if open_ else 0.05
    return filt(noise(d), "highpass", 7500) * np.exp(-tt(d) * (18 if open_ else 90)) * 0.5


def impact(g=1.0):
    t = tt(2.2)
    f = 28 + 70 * np.exp(-t * 9)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 2.2)
    crash = filt(noise(2.2), "highpass", 3000) * np.exp(-t * 3.2) * 0.35
    body = filt(noise(2.2), "lowpass", 400) * np.exp(-t * 6) * 0.8
    return np.tanh((boom * 1.4 + crash + body) * 1.2) * g


# ---------------------------------------------------------------- tonal
CHORDS = [  # vi – IV – I – V in C
    ([57, 60, 64, 69], 33),  # Am
    ([57, 60, 65, 69], 29),  # F
    ([55, 60, 64, 67], 36),  # C
    ([55, 59, 62, 67], 31),  # G
]


def pad_note(m, d, g=1.0):
    f = MIDI(m)
    x = sum(saw(f, d, dt) for dt in (-0.006, 0.0, 0.0065)) / 3
    x = filt(x, "lowpass", 1500 + 0 * f)
    return x * env(d, 0.25, 0.6) * g


def pluck(m, d=0.35, g=1.0):
    t = tt(d)
    f = MIDI(m)
    bright = filt(saw(f, d), "lowpass", 4200) * np.exp(-t * 26)
    body = np.sin(2 * np.pi * f * t) * np.exp(-t * 9)
    return (bright * 0.6 + body * 0.7) * env(d, 0.002, 0.05) * g


def bass_note(m, d, g=1.0):
    t = tt(d)
    f = MIDI(m)
    x = filt(saw(f, d), "lowpass", 380) * 1.3 + np.sin(2 * np.pi * f * t) * 0.8
    return np.tanh(x * 1.5) * env(d, 0.004, 0.05) * g


def bell(f=1318.5, d=1.2, g=1.0):
    t = tt(d)
    x = (np.sin(2 * np.pi * f * t) * np.exp(-t * 5)
         + 0.5 * np.sin(2 * np.pi * f * 2.0 * t) * np.exp(-t * 8)
         + 0.25 * np.sin(2 * np.pi * f * 3.01 * t) * np.exp(-t * 12))
    return x * env(d, 0.002, 0.1) * g


def sweep(d, f0, f1, g=1.0, q=0.35, shape="hann"):
    """band-passed noise whose centre moves from f0 to f1 (block-wise)."""
    n = int(d * SR)
    src = noise(d)
    out = np.zeros(n)
    blk = 1024
    zi = None
    for i in range(0, n, blk):
        k = i / max(1, n - 1)
        fc = f0 * (f1 / f0) ** k
        lo, hi = max(40, fc * (1 - q)), min(SR / 2 - 100, fc * (1 + q))
        sos = signal.butter(2, [lo, hi], btype="bandpass", fs=SR, output="sos")
        if zi is None:
            zi = signal.sosfilt_zi(sos) * 0
        seg, zi = signal.sosfilt(sos, src[i:i + blk], zi=zi)
        out[i:i + blk] = seg
    e = np.hanning(n) if shape == "hann" else np.linspace(0, 1, n) ** 2
    return out * e * g * 2.2


# ---------------------------------------------------------------- arrangement
def bar_chord(t):
    return CHORDS[int(np.floor((t - DOWN) / 2.0)) % 4]


# intro (0 – 2.5): soft ticking + drone
for i in range(int(2.5 / 0.125)):
    add(music, i * 0.125, hat(), 0.18 if i % 4 else 0.28, pan=0.3 if i % 2 else -0.3)
add(music, 0.0, filt(saw(MIDI(33), 3.4) + saw(MIDI(45), 3.4, 0.004), "lowpass", 300) * env(3.4, 1.0, 0.9), 0.35, rev=0.2)
# break after the slam: pad swell into the logo
add(music, 2.5, sum(pad_note(m, 1.1) for m in CHORDS[0][0]) * 0.8, 0.5, rev=0.5)

# main groove
t = DOWN
while t < GROOVE_END - 1e-6:
    beat_in_bar = int(round((t - DOWN) / BEAT)) % 4
    notes, root = bar_chord(t)
    add(music, t, kick(), 0.95)
    if beat_in_bar in (1, 3):
        add(music, t, clap(), 0.55, rev=0.25)
    add(music, t + BEAT / 2, hat(True), 0.28, pan=0.2)
    if t >= 7.0:
        for s in range(4):
            if s != 2:
                add(music, t + s * BEAT / 4, hat(), 0.16 if s else 0.1, pan=-0.25)
    # bass: off-beat 8ths
    add(music, t + BEAT / 2, bass_note(root, BEAT / 2 * 0.9), 0.55)
    if beat_in_bar == 0:
        add(music, t, sum(pad_note(m, 2.0) for m in notes) / 3, 0.42, rev=0.35)
    # 16th-note arp from the preview scene on
    if t >= 12.0:
        arp = [notes[0] + 12, notes[1] + 12, notes[2] + 12, notes[3] + 12, notes[2] + 12, notes[1] + 12, notes[3] + 12, notes[2] + 24]
        for s in range(4):
            idx = (int(round((t - DOWN) / BEAT)) * 4 + s) % len(arp)
            g = 0.16 if t < 27.5 else 0.21
            add(music, t + s * BEAT / 4, pluck(arp[idx]), g, pan=0.35 if s % 2 else -0.35, rev=0.4)
    t += BEAT

# sidechain the groove to the kick (pump)
pump = np.ones(N)
t = DOWN
while t < GROOVE_END:
    i = int(t * SR)
    n = int(0.28 * SR)
    pump[i:i + n] = np.minimum(pump[i:i + n], 0.35 + 0.65 * np.linspace(0, 1, n) ** 0.6)
    t += BEAT
# (kick itself is inside `music`; applying pump to the full bus gives the classic duck)
music *= pump

# outro: resolve to C and ring out
add(music, GROOVE_END, sum(pad_note(m, 1.9) for m in [48, 55, 60, 64, 67, 72]) / 3, 0.6, rev=0.6)
add(music, GROOVE_END, bass_note(36, 1.4), 0.5)
add(music, GROOVE_END, bell(MIDI(84), 1.9), 0.18, rev=0.6)

# ---------------------------------------------------------------- sfx
def tap():
    t = tt(0.05)
    return np.sin(2 * np.pi * 1900 * t) * np.exp(-t * 140) + filt(noise(0.05), "highpass", 3000) * np.exp(-t * 300) * 0.4


def tick():
    t = tt(0.02)
    return np.sin(2 * np.pi * 3200 * t) * np.exp(-t * 350)


def pop():
    t = tt(0.12)
    f = 320 + 900 * (1 - np.exp(-t * 40))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 30)


def zip_():
    d = 0.5
    t = tt(d)
    buzz = filt(saw(220, d) + saw(330, d), "bandpass", [600, 3000]) * 0.4
    return (buzz + sweep(d, 1500, 4000, 0.4)) * np.sin(np.pi * t / d) * 0.6


def sparkle():
    x = np.zeros(int(0.9 * SR))
    for k in range(18):
        f = 2200 + rng.random() * 4200
        o = rng.random() * 0.6
        b = bell(f, 0.25, 0.25)
        i = int(o * SR)
        x[i:i + len(b)] += b[: len(x) - i]
    return x


SYN = {
    "tap": lambda: (tap(), 0.5, 0.05),
    "tick": lambda: (tick(), 0.35, 0.0),
    "pop": lambda: (pop(), 0.5, 0.15),
    "blink": lambda: (pop(), 0.25, 0.1),
    "zip": lambda: (zip_(), 0.45, 0.1),
    "riser": lambda: (sweep(1.15, 200, 7000, 0.6, 0.5, "rise") + np.sin(2 * np.pi * np.cumsum(np.linspace(110, 880, int(1.15 * SR))) / SR) * np.linspace(0, 1, int(1.15 * SR)) ** 3 * 0.25, 0.6, 0.3),
    "slice": lambda: (mx(sweep(0.3, 5000, 1500, 0.8, 0.4), bell(2637, 0.6, 0.25)), 0.7, 0.4),
    "impact": lambda: (impact(), 0.95, 0.5),
    "whoosh": lambda: (sweep(0.5, 300, 3500, 0.75), 0.55, 0.35),
    "swoosh": lambda: (sweep(0.6, 200, 2500, 0.7), 0.55, 0.3),
    "swipe": lambda: (sweep(0.28, 800, 5000, 0.5), 0.4, 0.2),
    "suck": lambda: (sweep(0.4, 3500, 400, 0.7), 0.5, 0.3),
    "jet": lambda: (sweep(0.8, 400, 2000, 0.6), 0.45, 0.3),
    "send": lambda: (sweep(0.45, 600, 6000, 0.6), 0.5, 0.3),
    "ding": lambda: (bell(), 0.35, 0.5),
    "toggle": lambda: (np.concatenate([tick(), np.zeros(int(0.03 * SR)), tick()]), 0.5, 0.0),
    "process": lambda: (np.sin(2 * np.pi * np.cumsum(np.linspace(420, 980, int(0.85 * SR))) / SR) * (0.6 + 0.4 * np.sin(2 * np.pi * 18 * tt(0.85))) * env(0.85, 0.05, 0.1) * 0.25, 0.4, 0.2),
    "sparkle": lambda: (sparkle(), 0.55, 0.5),
}
for c in cues["sfx"]:
    x, g, rv = SYN[c["type"]]()
    pan = float(np.clip(rng.normal(0, 0.25), -0.6, 0.6)) if c["type"] in ("tap", "pop", "tick") else 0.0
    add(fx, c["t"], x, g * c["g"], pan=pan, rev=rv)

# ---------------------------------------------------------------- reverb + master
def ir(seed, d=1.6):
    r = np.random.default_rng(seed)
    t = tt(d)
    x = r.standard_normal(len(t)) * np.exp(-t * 3.2)
    x = filt(x, "lowpass", 6000)
    return x / np.sqrt(np.sum(x ** 2))


wet = np.stack([signal.fftconvolve(send[0], ir(1))[:N], signal.fftconvolve(send[1], ir(2))[:N]])
mix = music * 0.8 + fx * 0.9 + wet * 0.45
mix = filt(mix, "highpass", 25)
# fade tail
fade = int(0.5 * SR)
mix[:, -fade:] *= np.linspace(1, 0, fade)
mix = np.tanh(mix / np.max(np.abs(mix)) * 1.6) / np.tanh(1.6) * 0.9

pcm = (mix.T * 32767).astype(np.int16)
with wave.open(os.path.join(OUT, "audio.wav"), "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(pcm.tobytes())
print("wrote", os.path.join(OUT, "audio.wav"), f"{DUR:.1f}s")
