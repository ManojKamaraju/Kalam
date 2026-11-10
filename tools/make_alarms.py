#!/usr/bin/env python3
"""Synthesizes the built-in alarm tones (royalty free: generated from scratch)."""
import numpy as np, wave, os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/KALAM/app/src/main/res/raw"
SR = 22050
os.makedirs(OUT, exist_ok=True)


def t(d):
    return np.arange(int(SR * d)) / SR


def env(n, a=0.005, r=0.2):
    e = np.ones(n)
    na, nr = max(1, int(SR * a)), max(1, int(SR * r))
    e[:na] = np.linspace(0, 1, na)
    e[-nr:] = np.minimum(e[-nr:], np.linspace(1, 0, nr))
    return e


def bell(freq, d, decay=4.0):
    x = t(d)
    s = (np.sin(2 * np.pi * freq * x) + 0.5 * np.sin(2 * np.pi * freq * 2.01 * x) + 0.25 * np.sin(2 * np.pi * freq * 3.99 * x))
    return s * np.exp(-decay * x) * env(len(x), 0.003, 0.02)


def place(parts, total):
    out = np.zeros(int(SR * total))
    for start, sig in parts:
        i = int(SR * start)
        out[i:i + len(sig)] += sig[: max(0, len(out) - i)]
    return out


def save(name, sig):
    sig = sig / (np.max(np.abs(sig)) + 1e-9) * 0.85
    data = (sig * 32767).astype("<i2")
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


# 1 soft chime
save("alarm_soft_chime", place([(0.0, bell(660, 1.6, 2.5)), (0.9, bell(880, 1.6, 2.5))], 2.8))
# 2 gentle bells (arpeggio)
save("alarm_gentle_bells", place([(i * 0.35, bell(f, 1.4, 3.0)) for i, f in enumerate([523.25, 659.25, 783.99, 1046.5])], 2.8))
# 3 digital beeps
x = t(0.12)
beep = np.sign(np.sin(2 * np.pi * 1760 * x)) * env(len(x), 0.002, 0.01) * 0.6
save("alarm_digital", place([(i * 0.2, beep) for i in range(3)], 1.4))
# 4 classic bell (fast trill)
x = t(1.2)
trill = np.sin(2 * np.pi * 1450 * x) * (0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 24 * x))) + 0.4 * np.sin(2 * np.pi * 2900 * x)
save("alarm_classic_bell", place([(0.0, trill * env(len(x), 0.005, 0.05))], 2.0))
# 5 minimal ping
save("alarm_minimal_ping", place([(0.0, bell(1568, 0.5, 9.0)), (0.7, bell(1568, 0.5, 9.0))], 1.6))
# 6 notification style (two-note ding)
save("alarm_notification", place([(0.0, bell(987.8, 0.7, 5.0)), (0.22, bell(1318.5, 0.9, 5.0))], 1.6))
# 7 urgent siren
x = t(2.0)
f = 700 + 400 * np.sin(2 * np.pi * 1.5 * x)
phase = 2 * np.pi * np.cumsum(f) / SR
save("alarm_urgent_siren", np.sin(phase) * env(len(x), 0.01, 0.05))
# 8 rapid pulse
x = t(0.09)
pulse = np.sin(2 * np.pi * 1200 * x) * env(len(x), 0.002, 0.02)
save("alarm_rapid_pulse", place([(i * 0.14, pulse) for i in range(6)] , 1.2))
print(sorted(os.listdir(OUT)))
