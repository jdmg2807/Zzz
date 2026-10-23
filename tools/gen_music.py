#!/usr/bin/env python3
"""Generates the ambient soundtrack shipped with the APK.

Minecraft's soundtrack is calm, sparse and slow: soft pads, a few piano-like notes from a
pentatonic scale and a lot of silence. This script synthesises that mood from scratch (no
samples, no Mojang audio) at 22.05 kHz mono 16-bit, which is exactly what MediaPlayer can
stream from the APK.
"""
import math
import os
import random
import struct
import sys
import wave

SR = 22050

# Scales as semitone offsets (minor pentatonic / major pentatonic flavours).
PENTA_MINOR = [0, 3, 5, 7, 10]
PENTA_MAJOR = [0, 2, 4, 7, 9]


def midi_to_freq(n):
    return 440.0 * (2.0 ** ((n - 69) / 12.0))


def render(path, seconds, seed, root, major=True):
    rnd = random.Random(seed)
    n = int(SR * seconds)
    buf = [0.0] * n
    scale = PENTA_MAJOR if major else PENTA_MINOR

    def add_note(start, dur, freq, amp, harmonics=(1.0, 0.35, 0.15), decay=True):
        s0 = int(start * SR)
        s1 = min(n, s0 + int(dur * SR))
        if s1 <= s0:
            return
        w0 = 2.0 * math.pi * freq / SR
        env_len = max(1, s1 - s0)
        for i in range(s0, s1):
            t = (i - s0) / env_len
            if decay:
                env = math.exp(-3.4 * t)
            else:
                env = math.sin(math.pi * t) ** 1.5
            ph = w0 * (i - s0)
            v = harmonics[0] * math.sin(ph)
            v += harmonics[1] * math.sin(2.0 * ph)
            v += harmonics[2] * math.sin(3.0 * ph)
            buf[i] += amp * env * v

    # ---- slow pad chords every ~11 s
    t = 0.0
    while t < seconds:
        chord_root = root + 12 * rnd.choice([0, 0, 1, -1])
        degrees = [0, rnd.choice([3, 4]), 7, rnd.choice([10, 12])]
        for d in degrees:
            freq = midi_to_freq(chord_root + d)
            add_note(t, 13.0, freq, 0.055, harmonics=(1.0, 0.25, 0.08), decay=False)
        t += 10.5 + rnd.random() * 2.0

    # ---- sparse "piano" notes over the pad
    t = 1.5
    while t < seconds - 3.0:
        note = root + 24 + rnd.choice(scale) + 12 * rnd.choice([0, 0, 0, 1])
        add_note(t, 2.6 + rnd.random(), midi_to_freq(note), 0.09)
        if rnd.random() < 0.35:
            add_note(t + 0.42, 2.2, midi_to_freq(note - 12), 0.05)
        t += 1.1 + rnd.random() * 2.4

    # ---- airy noise bed (very low, gives the silence some texture)
    noise = 0.0
    for i in range(n):
        noise = noise * 0.995 + (rnd.random() - 0.5) * 0.02
        buf[i] += noise * 0.05

    # ---- simple feedback delay for space, then a global fade in/out
    delay = int(0.19 * SR)
    for i in range(delay, n):
        buf[i] += buf[i - delay] * 0.32

    fade_in = int(2.0 * SR)
    fade_out = int(4.0 * SR)
    for i in range(min(fade_in, n)):
        buf[i] *= i / fade_in
    for i in range(min(fade_out, n)):
        buf[n - 1 - i] *= i / fade_out

    peak = max(1e-9, max(abs(v) for v in buf))
    gain = 0.82 / peak
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray(n * 2)
        for i, v in enumerate(buf):
            s = int(max(-1.0, min(1.0, v * gain)) * 32000)
            struct.pack_into("<h", frames, i * 2, s)
        w.writeframes(bytes(frames))
    print("wrote", path, os.path.getsize(path), "bytes")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    tracks = [
        ("menu_1", 205, 11, 45, True),    # A3 pentatonic major
        ("menu_2", 200, 23, 41, False),   # F3 minor
        ("world_1", 210, 37, 48, True),   # C4 major
        ("world_2", 205, 51, 43, False),  # G3 minor
        ("cave_1", 200, 67, 38, False),   # D3 minor, darker
        ("night_1", 210, 79, 40, True),   # E3 major, calm
    ]
    for name, secs, seed, root, major in tracks:
        render(os.path.join(out, name + ".wav"), secs, seed, root, major)


if __name__ == "__main__":
    main()
