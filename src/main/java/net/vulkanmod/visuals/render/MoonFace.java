package net.vulkanmod.visuals.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 2026-11-12 (shader roadmap session 15, the user: "the moon's craters are still disappointing; replace them with a more realistic,
 * higher-resolution image"): the round moon's face, baked offline by tools/moon/bake_moon_face.py from NASA's CGI Moon Kit (NASA's
 * Scientific Visualization Studio, public domain; THIRD_PARTY_NOTICES.md): the near side as seen from Earth, an orthographic disc
 * filling the square in water.glsl moonShape's disc frame (texel (x, y) is e = ((x + 0.5) / size * 2 - 1, (y + 0.5) / size * 2 - 1);
 * lunar north -e.y, lunar east -e.x). RGBA8 levels down to 1x1 (the shader picks one by the pixel's footprint, so a small moon
 * does not shimmer): r the albedo (linear, 1 the near side's brightest percent), g and b the surface slope east and north
 * (0.5 + slope / (2 SLOPE_RANGE); the LRO laser altimeter's heights), a 255.
 */
public final class MoonFace {
    public static final String PATH = "assets/vulkanmod/visuals/textures/moon/moon_face.bin";
    /** The slope (height over distance along the surface) at g, b = 0 and 255: -SLOPE_RANGE and +SLOPE_RANGE. */
    public static final float SLOPE_RANGE = 0.5f;
    private static final byte[] MAGIC = "VAMOON01".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static volatile MoonFace loaded;

    public final int size;
    /** Level i is (size >> i) squared texels, RGBA8 rows from e.y = -1. */
    public final byte[][] levels;

    private MoonFace(int size, byte[][] levels) {
        this.size = size;
        this.levels = levels;
    }

    /** The face from the jar, read once. A missing or broken file gives a plain 1x1 face (albedo 1, level) and is logged once. */
    public static MoonFace get() {
        MoonFace f = loaded;
        if (f != null) return f;
        synchronized (MoonFace.class) {
            if (loaded != null) return loaded;
            try (InputStream in = MoonFace.class.getClassLoader().getResourceAsStream(PATH)) {
                if (in == null) throw new IOException(PATH + " missing");
                loaded = decode(in.readAllBytes());
            } catch (IOException | RuntimeException e) {
                net.vulkanmod.visuals.VisualsLog.warn("Moon face not loaded, the round moon is plain: " + e);
                loaded = new MoonFace(1, new byte[][]{{(byte) 255, (byte) 128, (byte) 128, (byte) 255}});
            }
            return loaded;
        }
    }

    /** bake_moon_face.py's file: the magic, size and slope size (little-endian uint32), then zlib of the planes (rows "sub" filtered). */
    static MoonFace decode(byte[] file) throws IOException {
        if (file.length < 16 || !Arrays.equals(Arrays.copyOf(file, 8), MAGIC)) throw new IOException(PATH + ": not a moon face");
        ByteBuffer header = ByteBuffer.wrap(file, 8, 8).order(ByteOrder.LITTLE_ENDIAN);
        int size = header.getInt(), slopeSize = header.getInt();
        if (size < 1 || size > 4096 || Integer.bitCount(size) != 1 || slopeSize < 1 || slopeSize > size) {
            throw new IOException(PATH + ": size " + size + ", slope size " + slopeSize);
        }
        byte[] planes = new byte[size * size + 2 * slopeSize * slopeSize];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(file, 16, file.length - 16);
            int n = 0;
            while (n < planes.length && !inflater.finished()) {
                int got = inflater.inflate(planes, n, planes.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += got;
            }
            if (n != planes.length) throw new IOException(PATH + ": " + n + " bytes, expected " + planes.length);
        } catch (DataFormatException e) {
            throw new IOException(PATH + ": " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        unfilter(planes, 0, size);
        unfilter(planes, size * size, slopeSize);
        unfilter(planes, size * size + slopeSize * slopeSize, slopeSize);
        byte[] top = new byte[size * size * 4];
        int east = size * size, north = east + slopeSize * slopeSize;
        float k = (float) slopeSize / size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int i = (y * size + x) * 4;
                top[i] = planes[y * size + x];
                float sx = (x + 0.5f) * k - 0.5f, sy = (y + 0.5f) * k - 0.5f;
                top[i + 1] = (byte) Math.round(bilinear(planes, east, slopeSize, sx, sy));
                top[i + 2] = (byte) Math.round(bilinear(planes, north, slopeSize, sx, sy));
                top[i + 3] = (byte) 255;
            }
        }
        int count = Integer.numberOfTrailingZeros(size) + 1;
        byte[][] levels = new byte[count][];
        levels[0] = top;
        for (int l = 1; l < count; l++) levels[l] = half(levels[l - 1], size >> (l - 1));
        return new MoonFace(size, levels);
    }

    private static void unfilter(byte[] b, int offset, int n) {
        for (int y = 0; y < n; y++) {
            int row = offset + y * n;
            for (int x = 1; x < n; x++) b[row + x] = (byte) (b[row + x] + b[row + x - 1]);
        }
    }

    /** The plane at offset (n x n bytes, 0..255) at texel coordinates x, y (centres on integers), clamped to its edge. */
    private static float bilinear(byte[] b, int offset, int n, float x, float y) {
        x = Math.max(0, Math.min(x, n - 1));
        y = Math.max(0, Math.min(y, n - 1));
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, n - 1), y1 = Math.min(y0 + 1, n - 1);
        float fx = x - x0, fy = y - y0;
        float a = b[offset + y0 * n + x0] & 255, c = b[offset + y0 * n + x1] & 255;
        float d = b[offset + y1 * n + x0] & 255, e = b[offset + y1 * n + x1] & 255;
        return (a + (c - a) * fx) * (1 - fy) + (d + (e - d) * fx) * fy;
    }

    /** The next level: each texel the mean of four (n is the level's size). */
    private static byte[] half(byte[] level, int n) {
        int h = n / 2;
        byte[] out = new byte[h * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < h; x++) {
                for (int c = 0; c < 4; c++) {
                    int s = (level[((2 * y) * n + 2 * x) * 4 + c] & 255) + (level[((2 * y) * n + 2 * x + 1) * 4 + c] & 255)
                            + (level[((2 * y + 1) * n + 2 * x) * 4 + c] & 255) + (level[((2 * y + 1) * n + 2 * x + 1) * 4 + c] & 255);
                    out[(y * h + x) * 4 + c] = (byte) ((s + 2) >> 2);
                }
            }
        }
        return out;
    }

    /**
     * The top level at e (water.glsl moonFaceAt with a level-0 read): {albedo 0..1, slope east, slope north}, bilinear as the sampler's
     * linear filter, clamped to the edge.
     */
    public float[] at(float ex, float ey) {
        float x = (ex * 0.5f + 0.5f) * this.size - 0.5f, y = (ey * 0.5f + 0.5f) * this.size - 0.5f;
        float[] out = new float[3];
        for (int c = 0; c < 3; c++) out[c] = channel(this.levels[0], this.size, c, x, y) / 255f;
        out[1] = (out[1] * 2 - 1) * SLOPE_RANGE;
        out[2] = (out[2] * 2 - 1) * SLOPE_RANGE;
        return out;
    }

    private static float channel(byte[] rgba, int n, int c, float x, float y) {
        x = Math.max(0, Math.min(x, n - 1));
        y = Math.max(0, Math.min(y, n - 1));
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(x0 + 1, n - 1), y1 = Math.min(y0 + 1, n - 1);
        float fx = x - x0, fy = y - y0;
        float a = rgba[(y0 * n + x0) * 4 + c] & 255, b = rgba[(y0 * n + x1) * 4 + c] & 255;
        float d = rgba[(y1 * n + x0) * 4 + c] & 255, e = rgba[(y1 * n + x1) * 4 + c] & 255;
        return (a + (b - a) * fx) * (1 - fy) + (d + (e - d) * fx) * fy;
    }
}
