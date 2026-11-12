"""Bake the round moon's face (src/main/resources/assets/vulkanmod/visuals/textures/moon/moon_face.bin) from NASA's CGI Moon Kit.

Offline only: not part of the build or the jar. Needs Python 3 with numpy and Pillow, and the two public-domain maps from
NASA's Scientific Visualization Studio, CGI Moon Kit (https://svs.gsfc.nasa.gov/4720/):
  lroc_color_poles_4k.tif  LROC WAC colour mosaic (Hapke normalised), 4096 x 2048, equirectangular, centred on 0 deg longitude
  ldem_16_uint.tif         LOLA elevation, 16 px/deg, unsigned 16-bit half-metres relative to 1727400 m (5760 x 2880)

    python -I tools/moon/bake_moon_face.py <colour.tif> <ldem.tif> <out moon_face.bin> [preview dir]

The face is the Moon's near side as seen from Earth (no libration), an orthographic disc filling a square, in the disc frame of
water.glsl moonShape: texel (col, row) of an n x n plane is e = ((col + 0.5) / n * 2 - 1, (row + 0.5) / n * 2 - 1); lunar north is
-e.y and lunar east -e.x (session 10's frame). Within FILL_TEXELS outside the unit disc each plane holds the limb's value smoothed
around the circle (RING_BLUR radians; the sampler's bilinear taps and mips never mix in a step, and the disc's soft edge shows no
radial streaks); farther out it is flat (the albedo's median, zero slope). Planes,
8 bits a texel (MoonFace.java reads them):
  albedo        SIZE x SIZE: the colour map's luminance in linear light over its ALBEDO_WHITE percentile on the disc (clamped to 1)
  slope east    SLOPE_SIZE x SLOPE_SIZE, then slope north: the elevation's gradient (height over distance along the surface) at
                0.5 + g / (2 SLOPE_RANGE). Half the albedo's size: the relief only shows near the terminator, and halves the file
The file: b"VAMOON01", SIZE and SLOPE_SIZE as little-endian uint32, then one zlib stream of the three planes in that order, every
row stored as each byte's difference from the one to its left (mod 256; PNG's "sub" filter).
"""
import struct
import sys
import zlib

import numpy as np
from PIL import Image

SIZE = 1024
SLOPE_SIZE = 512
#Supersampling per texel side (the limb packs many map pixels into one texel)
SUPER = 3
#The disc radius the face is read to: the limb is carried outward from here
LIMB_READ = 0.999
FILL_TEXELS = 16
RING_BLUR = 0.03
RING_SAMPLES = 4096
ALBEDO_WHITE = 99.0
SLOPE_RANGE = 0.5
MOON_RADIUS_M = 1737400.0
LDEM_PPD = 16.0

Image.MAX_IMAGE_PIXELS = None


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def bilinear(img, x, y):
    """img sampled at pixel coordinates x, y (centres at integers + 0.5), wrapping in x and clamping in y."""
    h, w = img.shape[:2]
    x = x - 0.5
    y = np.clip(y - 0.5, 0, h - 1)
    x0 = np.floor(x).astype(np.int64)
    y0 = np.floor(y).astype(np.int64)
    fx = x - x0
    fy = y - y0
    y1 = np.minimum(y0 + 1, h - 1)
    x0w = x0 % w
    x1w = (x0 + 1) % w
    top = img[y0, x0w] * (1 - fx) + img[y0, x1w] * fx
    bottom = img[y1, x0w] * (1 - fx) + img[y1, x1w] * fx
    return top * (1 - fy) + bottom * fy


def ring_fill(plane, n, far):
    """Texels past the limb: the ring just inside it (two texels in), blurred around the circle, out to FILL_TEXELS; beyond, far."""
    c = (np.arange(n) + 0.5) / n * 2 - 1
    ex, ey = np.meshgrid(c, c)
    l = np.hypot(ex, ey)
    angle = np.arctan2(ey, ex)
    t = np.arange(RING_SAMPLES) / RING_SAMPLES * 2 * np.pi
    r0 = 1 - 2.0 * 2 / n
    ring = bilinear(plane, (np.cos(t) * r0 * 0.5 + 0.5) * n, (np.sin(t) * r0 * 0.5 + 0.5) * n)
    k = np.arange(-RING_SAMPLES // 2, RING_SAMPLES // 2) * (2 * np.pi / RING_SAMPLES)
    kernel = np.exp(-0.5 * (k / RING_BLUR) ** 2)
    kernel = np.fft.ifftshift(kernel / kernel.sum())
    ring = np.real(np.fft.ifft(np.fft.fft(ring) * np.fft.fft(kernel)))
    idx = (np.rint((angle % (2 * np.pi)) / (2 * np.pi) * RING_SAMPLES).astype(np.int64)) % RING_SAMPLES
    out = plane.copy()
    band = (l > 1.0) & (l <= 1 + 2.0 * FILL_TEXELS / n)
    out[band] = ring[idx[band]]
    out[l > 1 + 2.0 * FILL_TEXELS / n] = far
    return out


def plane_radius(n):
    c = (np.arange(n) + 0.5) / n * 2 - 1
    ex, ey = np.meshgrid(c, c)
    return np.hypot(ex, ey)


def bake(colour_path, ldem_path):
    n = SIZE * SUPER
    c = (np.arange(n) + 0.5) / n * 2 - 1
    ex, ey = np.meshgrid(c, c)
    l = np.hypot(ex, ey)
    k = np.where(l > LIMB_READ, LIMB_READ / np.maximum(l, 1e-9), 1.0)
    ex, ey = ex * k, ey * k
    z = np.sqrt(np.maximum(1 - ex * ex - ey * ey, 0))
    east, north = -ex, -ey
    lat = np.arcsin(np.clip(north, -1, 1))
    lon = np.arctan2(east, z)

    colour = np.asarray(Image.open(colour_path).convert("RGB"), dtype=np.float32) / 255.0
    lum = srgb_to_linear(colour) @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    ch, cw = lum.shape
    albedo = bilinear(lum, (np.degrees(lon) + 180) / 360 * cw, (90 - np.degrees(lat)) / 180 * ch)

    ldem = np.asarray(Image.open(ldem_path), dtype=np.float32)
    height = (ldem * 0.5 + 1727400.0 - MOON_RADIUS_M) / MOON_RADIUS_M
    lh, lw = height.shape
    step = np.radians(1.0 / LDEM_PPD)
    lat_rows = np.radians(90 - (np.arange(lh) + 0.5) / LDEM_PPD)
    d_lon = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) / (2 * step)
    d_lat = np.empty_like(height)
    d_lat[1:-1] = (height[:-2] - height[2:]) / (2 * step)
    d_lat[0] = d_lat[1]
    d_lat[-1] = d_lat[-2]
    g_east_map = d_lon / np.maximum(np.cos(lat_rows), 0.05)[:, None]
    del d_lon
    lx = (np.degrees(lon) + 180) / 360 * lw
    ly = (90 - np.degrees(lat)) / 180 * lh
    g_east = bilinear(g_east_map, lx, ly)
    del g_east_map
    g_north = bilinear(d_lat, lx, ly)
    del d_lat

    def down(a, size):
        f = n // size
        return a.reshape(size, f, size, f).mean(axis=(1, 3))

    albedo = down(albedo, SIZE)
    inside = plane_radius(SIZE) < 1.0
    white = np.percentile(albedo[inside], ALBEDO_WHITE)
    albedo = np.clip(albedo / white, 0, 1)
    albedo = ring_fill(albedo, SIZE, np.median(albedo[inside]))
    slopes = []
    for g in (g_east, g_north):
        g = down(g, SLOPE_SIZE)
        g = ring_fill(g, SLOPE_SIZE, 0.0)
        slopes.append(0.5 + g / (2 * SLOPE_RANGE))
    s_in = plane_radius(SLOPE_SIZE) < 1.0
    mag = np.hypot(slopes[0] - 0.5, slopes[1] - 0.5)[s_in] * 2 * SLOPE_RANGE
    print(f"albedo: white {white:.4f}; disc mean {albedo[inside].mean():.3f}, 5% {np.percentile(albedo[inside], 5):.3f}")
    print(f"slopes: |g| mean {mag.mean():.4f}, 99% {np.percentile(mag, 99):.4f}")
    q = lambda a: np.clip(np.rint(a * 255), 0, 255).astype(np.uint8)
    return q(albedo), q(slopes[0]), q(slopes[1])


def encode(planes):
    rows = []
    for p in planes:
        f = p.copy()
        f[:, 1:] = p[:, 1:] - p[:, :-1]
        rows.append(f.tobytes())
    return b"VAMOON01" + struct.pack("<II", SIZE, SLOPE_SIZE) + zlib.compress(b"".join(rows), 9)


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    planes = bake(sys.argv[1], sys.argv[2])
    data = encode(planes)
    with open(sys.argv[3], "wb") as f:
        f.write(data)
    print(f"{sys.argv[3]}: {len(data)} bytes")
    if len(sys.argv) > 4:
        #Previews as seen in the sky (north up, east right: the disc frame mirrored left to right)
        Image.fromarray(planes[0][:, ::-1]).save(f"{sys.argv[4]}/moon_face_albedo.png")
        Image.fromarray(np.stack([planes[1], planes[2], np.full_like(planes[1], 128)], axis=-1)[:, ::-1]).save(f"{sys.argv[4]}/moon_face_slopes.png")


if __name__ == "__main__":
    main()
