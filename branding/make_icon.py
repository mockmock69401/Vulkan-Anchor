"""Vulkan Anchor icon: a blocky sunset landscape with far terrain fading into haze (shaders, LODs), framed by a large V.
python -I make_icon.py OUT.png [SIZE]"""
import math
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

OUT = sys.argv[1]
FINAL = int(sys.argv[2]) if len(sys.argv) > 2 else 512
S = 2048  # drawn at 4x, then downsampled
rnd = random.Random(7)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(stops, t):
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0)
    return stops[-1][1]


HORIZON = int(S * 0.64)
SKY = [(0.0, (14, 22, 66)), (0.45, (72, 52, 140)), (0.78, (214, 98, 120)), (1.0, (255, 170, 96))]

img = Image.new('RGB', (S, S))
px = img.load()
d = ImageDraw.Draw(img)
for y in range(HORIZON):
    d.line([(0, y), (S, y)], fill=gradient(SKY, y / HORIZON))

# stars in the upper sky
for _ in range(90):
    x, y = rnd.randrange(S), rnd.randrange(int(S * 0.32))
    r = rnd.choice((3, 4, 5, 6))
    d.rectangle([x, y, x + r, y + r], fill=(255, 245, 230) if rnd.random() < 0.8 else (190, 220, 255))

# sun with bloom
SUN = (S // 2, int(S * 0.555))
SUN_R = int(S * 0.115)
glow = Image.new('RGB', (S, S))
g = ImageDraw.Draw(glow)
g.ellipse([SUN[0] - SUN_R * 2.4, SUN[1] - SUN_R * 2.4, SUN[0] + SUN_R * 2.4, SUN[1] + SUN_R * 2.4], fill=(150, 70, 40))
g.ellipse([SUN[0] - SUN_R * 1.5, SUN[1] - SUN_R * 1.5, SUN[0] + SUN_R * 1.5, SUN[1] + SUN_R * 1.5], fill=(200, 110, 60))
glow = glow.filter(ImageFilter.GaussianBlur(S * 0.06))
img = ImageChops.add(img, glow)
d = ImageDraw.Draw(img)
d.ellipse([SUN[0] - SUN_R, SUN[1] - SUN_R, SUN[0] + SUN_R, SUN[1] + SUN_R], fill=(255, 236, 180))


def ridge(base, amp, block, seed, freq):
    """A stepped (block-wise) mountain line: y per block column."""
    r = random.Random(seed)
    phases = [r.uniform(0, 6.28) for _ in range(3)]
    ys = []
    for i in range(S // block + 2):
        x = i / (S / block)
        h = (math.sin(x * freq * 6.28 + phases[0]) * 0.55 + math.sin(x * freq * 2.3 * 6.28 + phases[1]) * 0.3
             + math.sin(x * freq * 5.1 * 6.28 + phases[2]) * 0.15)
        ys.append(int(base - amp * (h * 0.5 + 0.5)) // block * block)
    return ys


def draw_layer(ys, block, top, bottom, haze, snow=None):
    for i, y0 in enumerate(ys):
        x0 = i * block
        for y in range(y0, HORIZON + block, block):
            t = (y - y0) / max(1, HORIZON - y0)
            c = lerp(top, bottom, min(1, t * 1.6))
            sky = gradient(SKY, min(1, y / HORIZON))
            c = lerp(c, sky, haze)
            if snow and y < y0 + block * 2 and y0 < snow:
                c = lerp((236, 226, 240), sky, haze * 0.8)
            d.rectangle([x0, y, x0 + block - 1, y + block - 1], fill=c)


# far to near: hazier, smaller blocks far away (LODs), crisper and bigger near
draw_layer(ridge(HORIZON - S * 0.05, S * 0.24, 16, 3, 1.4), 16, (120, 92, 170), (150, 110, 170), 0.55, snow=HORIZON - S * 0.2)
draw_layer(ridge(HORIZON - S * 0.01, S * 0.19, 32, 11, 1.1), 32, (70, 70, 130), (90, 70, 120), 0.32)
draw_layer(ridge(HORIZON + S * 0.03, S * 0.13, 64, 23, 1.3), 64, (34, 64, 84), (30, 50, 70), 0.08)

# water: reflection of the sky, sun streak, ripple lines
for y in range(HORIZON, S):
    t = (y - HORIZON) / (S - HORIZON)
    c = lerp(gradient(SKY, 1 - t * 0.9), (10, 20, 50), min(1, t * 1.3))
    d.line([(0, y), (S, y)], fill=c)
for k in range(26):
    y = HORIZON + int((k + 0.5) / 26 * (S - HORIZON) * 0.95)
    w = int(SUN_R * (1.6 - k / 26 * 1.1) * (0.8 + rnd.random() * 0.4))
    h = max(6, int(10 + k * 0.8))
    a = 1 - k / 26
    c = lerp((40, 40, 80), (255, 214, 150), a)
    d.rectangle([SUN[0] - w, y, SUN[0] + w, y + h], fill=c)

# the V: two slanted bars with a bevel; its notch frames the sun
v_top, v_bottom = int(S * 0.13), int(S * 0.86)
half_top, thick = int(S * 0.37), int(S * 0.15)
cx = S // 2
outer = [(cx - half_top, v_top), (cx - half_top + thick, v_top), (cx, v_bottom - int(thick * 1.25)),
         (cx + half_top - thick, v_top), (cx + half_top, v_top), (cx + int(thick * 0.42), v_bottom),
         (cx - int(thick * 0.42), v_bottom)]
mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).polygon(outer, fill=255)

shadow = Image.new('L', (S, S), 0)
ImageDraw.Draw(shadow).polygon([(x + S * 0.012, y + S * 0.02) for x, y in outer], fill=170)
shadow = shadow.filter(ImageFilter.GaussianBlur(S * 0.012))
img = Image.composite(Image.new('RGB', (S, S), (8, 8, 24)), img, shadow)

fill = Image.new('RGB', (S, S))
f = ImageDraw.Draw(fill)
for y in range(S):
    t = min(1, max(0, (y - v_top) / (v_bottom - v_top)))
    f.line([(0, y), (S, y)], fill=gradient([(0, (255, 255, 255)), (0.55, (196, 236, 255)), (1, (120, 190, 255))], t))
img = Image.composite(fill, img, mask)

# bevel: a darker inner edge on the right faces, a light rim on the left
edge = mask.filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(9))
img = Image.composite(Image.new('RGB', (S, S), (40, 70, 150)), img, ImageChops.multiply(edge, mask))
right = Image.new('L', (S, S), 0)
rd = ImageDraw.Draw(right)
rd.polygon([(cx + half_top - thick, v_top), (cx + half_top, v_top), (cx + int(thick * 0.42), v_bottom),
            (cx, v_bottom - int(thick * 1.25))], fill=70)
img = Image.composite(Image.new('RGB', (S, S), (70, 110, 190)), img, ImageChops.multiply(right, mask))

img = img.resize((FINAL, FINAL), Image.LANCZOS)
img.save(OUT)
print('saved', OUT, img.size)
