#!/usr/bin/env python3
"""Generates the 25 bundled background patterns (procedural, created at development time, shipped as WebP).
The app never generates anything at runtime and never needs the network."""
import numpy as np, os, sys
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/KALAM/app/src/main/assets/patterns"
N = 1280
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(20260611)
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
u = xx / N
v = yy / N
cx, cy = u - 0.5, v - 0.5
r = np.sqrt(cx * cx + cy * cy)
ang = np.arctan2(cy, cx)


def lerp(a, b, t):
    t = np.clip(t, 0, 1)[..., None]
    return np.array(a, np.float32) * (1 - t) + np.array(b, np.float32) * t


def ramp(t, stops):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,), np.float32)
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        m = (t >= t0) & (t <= t1)
        k = ((t - t0) / max(t1 - t0, 1e-6))
        out[m] = (np.array(c0, np.float32) * (1 - k[m][:, None]) + np.array(c1, np.float32) * k[m][:, None])
    return out


def noise(scale, seed):
    g = np.random.default_rng(seed)
    n = max(2, int(N / scale))
    base = g.random((n + 2, n + 2)).astype(np.float32)
    img = Image.fromarray((base * 255).astype(np.uint8)).resize((N, N), Image.BICUBIC)
    return np.asarray(img, np.float32) / 255.0


def fbm(seed, octaves=5, base=256):
    t = np.zeros((N, N), np.float32)
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        t += amp * noise(base / (2 ** o), seed + o)
        tot += amp
        amp *= 0.5
    return t / tot


def save(i, rgb):
    # dither to avoid banding in smooth gradients
    rgb = rgb + (rng.random(rgb.shape).astype(np.float32) - 0.5) * (1.5 / 255.0)
    img = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8))
    img.save(os.path.join(OUT, "p%02d.webp" % i), quality=88, method=6)


def C(h):
    h = h.lstrip("#")
    return tuple(int(h[k:k + 2], 16) / 255.0 for k in (0, 2, 4))


# ---- 01..05 minimalist gradients
save(1, ramp(v, [(0, C("#0b132b")), (1, C("#3a506b"))]))
save(2, ramp((u + v) / 2, [(0, C("#1a0f2e")), (0.55, C("#5b2a86")), (1, C("#e96443"))]))
save(3, ramp(r * 1.6, [(0, C("#355c7d")), (0.6, C("#2a3d66")), (1, C("#0a0a14"))]))
save(4, ramp(0.5 + 0.5 * np.sin(ang * 1 + r * 3), [(0, C("#0f2027")), (0.5, C("#203a43")), (1, C("#2c5364"))]))
save(5, ramp(v * 0.8 + 0.2 * np.sin(u * 5), [(0, C("#232526")), (1, C("#414345"))]))

# ---- 06..09 abstract geometry
grid = ((np.floor(u * 16) + np.floor(v * 16)) % 2)
save(6, lerp(C("#101820"), C("#1b2a38"), grid))
tri = np.abs(((u * 12) % 1) - 0.5) + np.abs(((v * 12) % 1) - 0.5)
save(7, ramp(tri, [(0, C("#0d1b2a")), (0.5, C("#1b263b")), (1, C("#415a77"))]))
rings = 0.5 + 0.5 * np.sin(r * 90)
save(8, lerp(C("#0a0a0a"), C("#2e2e3a"), rings))
hexv = np.sin(u * 40) + np.sin(u * 20 + v * 34.64) + np.sin(u * 20 - v * 34.64)
save(9, ramp((hexv + 3) / 6, [(0, C("#0b0c10")), (0.5, C("#1f2833")), (1, C("#45a29e"))]))

# ---- 10..13 fluid shapes
f1 = fbm(11, 4, 400)
save(10, ramp(f1, [(0, C("#1d2671")), (0.5, C("#c33764")), (1, C("#ffb88c"))]))
f2 = fbm(21, 5, 300)
save(11, ramp(np.sin(f2 * 14), [(-1, C("#02111b")), (0, C("#0a3d62")), (1, C("#60a3bc"))]) if False else ramp(0.5 + 0.5 * np.sin(f2 * 14), [(0, C("#02111b")), (0.5, C("#0a3d62")), (1, C("#60a3bc"))]))
warp = fbm(31, 4, 350)
save(12, ramp(0.5 + 0.5 * np.sin((u + warp * 0.6) * 9), [(0, C("#150050")), (0.5, C("#610094")), (1, C("#ee4540"))]))
save(13, ramp(0.5 + 0.5 * np.sin((r + warp * 0.3) * 30), [(0, C("#000000")), (1, C("#304352"))]))

# ---- 14..16 mathematical
sp = 0.5 + 0.5 * np.sin(ang * 6 + r * 40)
save(14, lerp(C("#050505"), C("#7f5af0"), sp ** 2))
lis = np.sin(u * 30) * np.sin(v * 30)
save(15, ramp((lis + 1) / 2, [(0, C("#0f0c29")), (0.5, C("#302b63")), (1, C("#24243e"))]))
phi = (np.sin(u * 55) + np.sin(v * 55 * 1.618) + np.sin((u + v) * 34)) / 3
save(16, ramp((phi + 1) / 2, [(0, C("#10002b")), (1, C("#9d4edd"))]))

# ---- 17..19 organic
cell = fbm(41, 3, 120)
save(17, ramp(np.abs(cell - 0.5) * 2, [(0, C("#e0c3a0")), (0.5, C("#7a5c3e")), (1, C("#2b1d10"))]))
leaf = 0.5 + 0.5 * np.sin((u + 0.4 * fbm(51, 4, 250)) * 26)
save(18, ramp(leaf, [(0, C("#0b2b1a")), (1, C("#2f7a4f"))]))
cloud = fbm(61, 6, 420)
save(19, ramp(cloud, [(0, C("#0d1321")), (0.55, C("#1d2d44")), (1, C("#748cab"))]))

# ---- 20..22 digital textures
scan = 0.5 + 0.5 * np.sin(yy * 1.2)
save(20, lerp(C("#02070a"), C("#0a2a1f"), scan * 0.6 + 0.4 * noise(8, 70)))
px = np.floor(u * 64) * 64 + np.floor(v * 64)
blk = (np.sin(px * 12.9898) * 43758.5453) % 1
save(21, ramp(np.abs(blk), [(0, C("#0a0a12")), (1, C("#262650"))]))
mat = noise(3, 80)
save(22, lerp(C("#050505"), C("#16301f"), (mat > 0.72).astype(np.float32) * mat))

# ---- 23..25 subtle textures
save(23, lerp(C("#111111"), C("#1c1c1c"), noise(2, 91)))
weave = 0.5 + 0.25 * np.sin(u * 300) + 0.25 * np.sin(v * 300)
save(24, lerp(C("#101010"), C("#232323"), weave))
paper = 0.8 * fbm(101, 5, 160) + 0.2 * noise(2, 111)
save(25, lerp(C("#15110d"), C("#2b241b"), paper))

print(sorted(os.listdir(OUT)))
