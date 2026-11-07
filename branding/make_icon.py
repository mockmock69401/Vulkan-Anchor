"""Vulkan Anchor logo: a glowing red V whose arms are an anchor's, over the name.
python -I make_icon.py OUT.png [SIZE] [--mark]   (--mark: no wordmark, the mark fills the square)
The wordmark is set in Geist (SIL Open Font License), which has to be installed for the current user."""
import math
import os
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

args = [a for a in sys.argv[1:] if not a.startswith('--')]
MARK_ONLY = '--mark' in sys.argv
OUT = args[0]
FINAL = int(args[1]) if len(args) > 1 else 512
S = 2048  # canvas; the shapes are drawn at twice this, then everything is downsampled
SS = 2
FONT = os.path.join(os.environ['LOCALAPPDATA'], 'Microsoft', 'Windows', 'Fonts', 'Geist-VariableFont_wght.ttf')

RED_TOP, RED_BOTTOM = (255, 58, 58), (172, 20, 30)
BG_EDGE, BG_CENTRE = (7, 7, 10), (30, 8, 12)

# the mark is laid out in 0..1 around (0.5, 0.344); --mark scales it up to fill the square
if MARK_ONLY:
    SCALE, SHIFT_Y = 1.36, 0.5 - 0.344 * 1.36
else:
    SCALE, SHIFT_Y = 1.0, 0.0


def p(x, y):
    return (S * SS * (0.5 + (x - 0.5) * SCALE), S * SS * (y * SCALE + SHIFT_Y))


def size(v):
    return S * SS * v * SCALE


def vertical(y0, y1, c0, c1):
    grad = Image.new('RGB', (1, S))
    for y in range(S):
        t = min(1, max(0, (y - y0) / (y1 - y0)))
        grad.putpixel((0, y), tuple(round(c0[i] + (c1[i] - c0[i]) * t) for i in range(3)))
    return grad.resize((S, S))


def blank():
    return Image.new('L', (S * SS, S * SS), 0)


def finish(big, round_by=0.0):
    """Down to canvas size; round_by softens corners (blur, then a steep threshold)."""
    if round_by:
        big = big.filter(ImageFilter.GaussianBlur(size(round_by))).point(lambda v: min(255, max(0, (v - 118) * 14)))
    return big.resize((S, S), Image.LANCZOS)


# ---- the V: its arms are the anchor's arms, with barbed flukes; ring, stock and shank rise from the notch
Y_CROWN, ARM_TOP, ARM_X, ARM_THICK = 0.58, 0.235, 0.27, 0.098
FLUKE_BACK, FLUKE_WIDE, FLUKE_TIP = 0.07, 0.021, 0.018

big = blank()
m = ImageDraw.Draw(big)
dx, dy = ARM_X, ARM_TOP - Y_CROWN
length = math.hypot(dx, dy)
ux, uy = -dx / length, dy / length
nx, ny = -uy, ux
half = ARM_THICK / 2
for arm in (-1, 1):
    def q(along, side):
        return p(0.5 + (ux * along + nx * side) * -arm, Y_CROWN + uy * along + ny * side)
    start = lambda side: -nx * side / ux  # both edges start on the centre line: the arms meet in one point
    # one outline per arm. side > 0 faces the notch; the end is a chisel from a tip over the inner edge
    # down to a barb that flares past the outer edge: the fluke
    m.polygon([q(start(-half), -half), q(length - FLUKE_BACK * 1.3, -half), q(length - FLUKE_BACK, -half - FLUKE_WIDE),
               q(length + FLUKE_TIP, half), q(start(half), half)], fill=255)
SHANK, STOCK_X, STOCK_Y, STOCK_H = 0.020, 0.086, 0.148, 0.0165
RING_Y, RING_OUT, RING_IN = 0.077, 0.047, 0.023
m.rectangle([p(0.5 - SHANK, RING_Y), p(0.5 + SHANK, Y_CROWN)], fill=255)
m.rectangle([p(0.5 - STOCK_X, STOCK_Y - STOCK_H), p(0.5 + STOCK_X, STOCK_Y + STOCK_H)], fill=255)
for side in (-1, 1):
    ex, ey = p(0.5 + side * STOCK_X, STOCK_Y)
    r = size(STOCK_H)
    m.ellipse([ex - r, ey - r, ex + r, ey + r], fill=255)
rx, ry = p(0.5, RING_Y)
for radius, value in ((RING_OUT, 255), (RING_IN, 0)):
    r = size(radius)
    m.ellipse([rx - r, ry - r, rx + r, ry + r], fill=value)
v_mask = finish(big, 0.0035)

# ---- background: near black, warming slightly behind the mark
# Image.radial_gradient is 0 at the centre, 255 at the corners; centred on the mark, which sits above the name
reach = S * 2
radial = Image.radial_gradient('L').resize((reach, reach), Image.BICUBIC)
top_of_crop = (reach - S) // 2 + (0 if MARK_ONLY else int(S * 0.15))
radial = radial.crop((reach // 4, top_of_crop, reach // 4 + S, top_of_crop + S)).point(lambda v: min(255, v * 2))
radial = radial.filter(ImageFilter.GaussianBlur(S * 0.01))
img = Image.composite(Image.new('RGB', (S, S), BG_EDGE), Image.new('RGB', (S, S), BG_CENTRE), radial)

# ---- obsidian: faint glassy blocks, each split into two facets; denser toward the edges, clear behind the mark
def obsidian(base):
    rnd = random.Random(11)
    coarse_n = 7
    coarse = [[rnd.random() for _ in range(coarse_n + 1)] for _ in range(coarse_n + 1)]

    def patches(x, y):
        gx, gy = x * coarse_n, y * coarse_n
        ix, iy = min(int(gx), coarse_n - 1), min(int(gy), coarse_n - 1)
        fx, fy = gx - ix, gy - iy
        top = coarse[iy][ix] * (1 - fx) + coarse[iy][ix + 1] * fx
        low = coarse[iy + 1][ix] * (1 - fx) + coarse[iy + 1][ix + 1] * fx
        return top * (1 - fy) + low * fy

    alpha = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(alpha)
    centre_y = 0.5 if MARK_ONLY else 0.36
    for cell, chance in ((S // 12, 0.5), (S // 24, 0.55)):  # large blocks first, small ones over them
        for row in range(S // cell + 1):
            for col in range(S // cell + 1):
                x0, y0 = col * cell, row * cell
                x, y = (col + 0.5) * cell / S, (row + 0.5) * cell / S
                away = min(1, max(0, (math.hypot(x - 0.5, y - centre_y) - 0.2) / 0.45))
                if patches(min(x, 1), min(y, 1)) + rnd.random() * 0.35 < 0.84 - away * 0.34 or rnd.random() > chance:
                    continue
                level = int((26 + rnd.random() * 44) * (0.35 + 0.65 * away))
                x1, y1 = x0 + cell - 1, y0 + cell - 1
                if rnd.random() < 0.5:
                    light, dark = [(x0, y0), (x1, y0), (x0, y1)], [(x1, y0), (x1, y1), (x0, y1)]
                else:
                    light, dark = [(x0, y0), (x1, y0), (x1, y1)], [(x0, y0), (x1, y1), (x0, y1)]
                d.polygon(light, fill=level)
                d.polygon(dark, fill=int(level * 0.8))
                d.line([(x0, y0), (x1, y0)], fill=min(255, int(level * 1.35)), width=max(2, S // 512))
    return Image.composite(Image.new('RGB', (S, S), (92, 80, 118)), base, alpha)


img = obsidian(img)

# ---- glow, wide and tight
for radius, strength, colour in ((0.05, 0.42, (230, 25, 35)), (0.014, 0.85, (255, 40, 45))):
    glow = v_mask.filter(ImageFilter.GaussianBlur(S * radius)).point(lambda v: int(v * strength))
    img = ImageChops.add(img, ImageChops.multiply(Image.merge('RGB', (glow, glow, glow)), Image.new('RGB', (S, S), colour)))

# ---- fills
v_top, v_bottom = p(0.5, RING_Y - RING_OUT)[1] / SS, p(0.5, Y_CROWN + half / -ux)[1] / SS
img = Image.composite(vertical(v_top, v_bottom, RED_TOP, RED_BOTTOM), img, v_mask)
# a thin light rim on the upper left of the V
rim = ImageChops.subtract(v_mask, ImageChops.offset(v_mask, int(S * 0.003), int(S * 0.004))).point(lambda v: v * 120 // 255)
img = Image.composite(Image.new('RGB', (S, S), (255, 165, 160)), img, rim)


# ---- the name
def text_mask(text, weight, height_px, tracking=0.0):
    """White-on-black text mask, cropped to its ink, its ink height height_px (canvas pixels)."""
    font = ImageFont.truetype(FONT, 1000)
    font.set_variation_by_axes([weight])
    canvas = Image.new('L', (9000, 1600), 0)
    d = ImageDraw.Draw(canvas)
    x = 100
    for ch in text:
        d.text((x, 100), ch, font=font, fill=255)
        x += font.getlength(ch) + tracking * 1000
    canvas = canvas.crop(canvas.getbbox())
    return canvas.resize((round(canvas.width * height_px / canvas.height), round(height_px)), Image.LANCZOS)


if not MARK_ONLY:
    word = text_mask('Vulkan', 900, S * 0.145, tracking=-0.005)
    x, y = (S - word.width) // 2, int(S * 0.692)
    layer = Image.new('L', (S, S), 0)
    layer.paste(word, (x, y))
    img = Image.composite(vertical(y, y + word.height, (255, 255, 255), (206, 208, 216)), img, layer)

    sub = text_mask('ANCHOR', 800, S * 0.056, tracking=0.34)
    x, y = (S - sub.width) // 2, int(S * 0.874)
    layer = Image.new('L', (S, S), 0)
    layer.paste(sub, (x, y))
    img = Image.composite(Image.new('RGB', (S, S), (240, 48, 52)), img, layer)

img = img.resize((FINAL, FINAL), Image.LANCZOS)
img.save(OUT)
print('saved', OUT, img.size)
