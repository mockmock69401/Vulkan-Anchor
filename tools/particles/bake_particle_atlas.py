"""Bake the realistic particles' atlas (src/main/resources/assets/vulkanmod/visuals/textures/particles/particle_atlas.png) from CC0 images.

Offline only: not part of the build or the jar. Needs Python 3 with numpy and Pillow, and the unpacked CC0 sources:
  Unity's free VFX flipbooks (2016, CC0, https://unity.com/blog/engine-platform/free-vfx-image-sequences-flipbooks):
    Flame03/Flame03_16x4.tga              a looping flame, 64 frames of 64 x 128 (16 x 4)
  Kenney's Particle Pack (CC0, https://kenney.nl/assets/particle-pack): PNG/*.png, 512 x 512 white on black (brightness = coverage)
  Kenney's Smoke Particles (CC0, https://kenney.nl/assets/smoke-particles): PNG/White puff/whitePuff00..24.png, with alpha

    python -I tools/particles/bake_particle_atlas.py <Flame03 dir> <particle pack PNG dir> <smoke PNG dir> <out png> [preview png]

The atlas is 1024 x 768, tiles of TILE x TILE texels, every tile white or coloured with straight alpha, mips made at run time
(ParticleSprites.java names every tile; keep the two in step):
  rows 0..3   FLAME_FRAMES flame frames (every other frame of Flame03, in order: the loop), the 64 x 128 frame scaled to fill the tile's
              height and centred across it (a tall flame in a square quad), stored as its brightness (alpha = the frame's alpha) so the
              shader colours it (white-hot core, the particle's own colour toward the edge: particle.fsh)
  row 4       8 smoke puffs (Kenney white puffs 0, 3, 6, ... 21), each centred and fitted to the tile, their shading lifted (no dark rim)
  row 5       8 sprites: glow (circle_05, a soft dot), ember (flare_01, a hot dot), star (star_06), sparkle (star_04), ring (circle_03),
              drop, petal, magic (star_01); drop and petal are drawn here (a lit sphere-like droplet, a soft petal)
Each tile keeps a PAD texel transparent border so bilinear taps and mips never bleed into a neighbour.
"""
import os
import sys

import numpy as np
from PIL import Image

TILE = 128
PAD = 2
COLS = 8
FLAME_FRAMES = 32
ROWS = 6


def to_float(im):
    return np.asarray(im.convert('RGBA'), dtype=np.float64) / 255.0


def fit(rgba, size):
    """Scale an RGBA float image to fit size x size (keeping its aspect), premultiplied while resampling, centred in a transparent tile."""
    h, w = rgba.shape[:2]
    s = min(size / w, size / h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    pm = rgba.copy()
    pm[..., :3] *= pm[..., 3:4]
    img = Image.fromarray(np.uint8(np.clip(pm * 255 + 0.5, 0, 255)), 'RGBA').resize((nw, nh), Image.LANCZOS)
    a = np.asarray(img, dtype=np.float64) / 255.0
    out = np.zeros((size, size, 4))
    y0, x0 = (size - nh) // 2, (size - nw) // 2
    out[y0:y0 + nh, x0:x0 + nw] = a
    alpha = out[..., 3:4]
    out[..., :3] = np.where(alpha > 1e-4, out[..., :3] / np.maximum(alpha, 1e-4), 1.0)
    return out


def brightness_as_alpha(rgba):
    """Kenney's particle sprites: white on black, opaque. Brightness becomes coverage, colour white."""
    lum = rgba[..., 0] * 0.2126 + rgba[..., 1] * 0.7152 + rgba[..., 2] * 0.0722
    out = np.ones_like(rgba)
    out[..., 3] = lum
    return out


def flame_intensity(rgba):
    """A flame frame as brightness (white, alpha = brightness x coverage): particle.fsh colours it."""
    lum = np.max(rgba[..., :3], axis=2)
    out = np.ones_like(rgba)
    out[..., 3] = np.clip(lum * rgba[..., 3] * 1.15, 0, 1)
    return out


def drop(size):
    """A droplet: a round body, darker toward the rim, a highlight up and to the left, a faint rim light low right."""
    y, x = np.mgrid[0:size, 0:size]
    u = (x + 0.5) / size * 2 - 1
    v = (y + 0.5) / size * 2 - 1
    r = np.sqrt(u * u + v * v) / 0.62
    body = np.clip((1 - r) / 0.06, 0, 1)
    shade = 0.55 + 0.35 * (1 - r ** 2)
    hl = np.exp(-((u + 0.22) ** 2 + (v + 0.25) ** 2) / 0.006)
    rim = np.exp(-((r - 0.85) ** 2) / 0.004) * np.clip(u + v, 0, 1)
    val = np.clip(shade + hl * 0.9 + rim * 0.4, 0, 1)
    out = np.ones((size, size, 4))
    out[..., 0] = out[..., 1] = out[..., 2] = val
    out[..., 3] = body * np.clip(0.75 + hl * 0.25 + 0.2 * (r > 0.8), 0, 1)
    return out


def petal(size):
    """A petal: a soft ellipse, a little lighter at its tip, a faint midrib."""
    y, x = np.mgrid[0:size, 0:size]
    u = (x + 0.5) / size * 2 - 1
    v = (y + 0.5) / size * 2 - 1
    t = np.sqrt((u / 0.42) ** 2 + (v / 0.8) ** 2)
    a = np.clip((1 - t) / 0.12, 0, 1)
    val = np.clip(0.82 + 0.15 * (-v) - 0.08 * np.exp(-(u ** 2) / 0.002), 0, 1)
    out = np.ones((size, size, 4))
    out[..., 0] = out[..., 1] = out[..., 2] = val
    out[..., 3] = a
    return out


def place(atlas, tile, col, row):
    inner = TILE - 2 * PAD
    t = fit(tile, inner) if tile.shape[0] != inner or tile.shape[1] != inner else tile
    y, x = row * TILE + PAD, col * TILE + PAD
    atlas[y:y + inner, x:x + inner] = t


def main():
    flame_dir, pack_dir, smoke_dir, out_png = sys.argv[1:5]
    preview = sys.argv[5] if len(sys.argv) > 5 else None
    atlas = np.zeros((ROWS * TILE, COLS * TILE, 4))
    atlas[..., :3] = 1.0
    sheet = to_float(Image.open(os.path.join(flame_dir, 'Flame03_16x4.tga')))
    fw, fh = sheet.shape[1] // 16, sheet.shape[0] // 4
    for i in range(FLAME_FRAMES):
        f = i * 2
        frame = sheet[(f // 16) * fh:(f // 16 + 1) * fh, (f % 16) * fw:(f % 16 + 1) * fw]
        place(atlas, flame_intensity(frame), i % COLS, i // COLS)
    for i in range(8):
        puff = to_float(Image.open(os.path.join(smoke_dir, 'White puff', 'whitePuff%02d.png' % (i * 3))))
        #The puffs are shaded nearly black toward their faint edge: over a lit scene that drew a dark rim around every puff. Keep the
        #shading inside but lift its floor so the edge fades out light.
        puff[..., :3] = 0.62 + 0.38 * puff[..., :3]
        place(atlas, puff, i, 4)
    inner = TILE - 2 * PAD
    sprites = [brightness_as_alpha(to_float(Image.open(os.path.join(pack_dir, n + '.png')))) for n in ('circle_05', 'flare_01', 'star_06', 'star_04', 'circle_03')]
    sprites += [drop(inner), petal(inner), brightness_as_alpha(to_float(Image.open(os.path.join(pack_dir, 'star_01.png'))))]
    for i, s in enumerate(sprites):
        place(atlas, s, i, 5)
    img = Image.fromarray(np.uint8(np.clip(atlas * 255 + 0.5, 0, 255)), 'RGBA')
    os.makedirs(os.path.dirname(out_png), exist_ok=True)
    img.save(out_png, optimize=True)
    print(out_png, img.size, os.path.getsize(out_png), 'bytes')
    if preview:
        bg = Image.new('RGBA', img.size, (24, 26, 34, 255))
        bg.alpha_composite(img)
        bg.convert('RGB').save(preview, quality=90)


if __name__ == '__main__':
    main()
