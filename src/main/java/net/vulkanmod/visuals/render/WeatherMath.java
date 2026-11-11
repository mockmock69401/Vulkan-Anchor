package net.vulkanmod.visuals.render;

/**
 * 2026-11-09 (session 6, the user: vanilla's rain and snow look thick and too vanilla): the mirror of post/weather.glsl, the shader-drawn
 * rain and snow (see that file for the model and its literature: Tatarchuk 2006, Garg and Nayar 2004 and 2006, Amanatides and Woo 1987).
 * Every public static float and int field here is a const of the shader with the same value (WeatherTest); the functions do the same
 * steps in the same order. Units: blocks, world directions (y up) from the camera, display colours.
 */
public final class WeatherMath {
    public static final int WEATHER_LAYERS = 3;
    public static final float WEATHER_LAYER_END_0 = 4.0f;
    public static final float WEATHER_LAYER_END_1 = 12.0f;
    public static final float WEATHER_LAYER_END_2 = 32.0f;
    public static final float WEATHER_NEAR = 0.5f;
    public static final float WEATHER_NEAR_FADE = 1.0f;
    public static final float WEATHER_MARGIN = 0.25f;
    public static final int WEATHER_MAX_STEPS = 24;
    public static final int WEATHER_DROPS = 2;
    public static final float WEATHER_OPAQUE = 0.98f;
    public static final float WEATHER_DEPTH_FADE = 0.25f;
    public static final int WEATHER_WRAP = 4096;
    public static final int WEATHER_MAP_SIZE = 128;
    public static final int WEATHER_MAP_MASK = 127;
    public static final int WEATHER_Y_BIAS = 2048;
    public static final int WEATHER_FLAG_PRECIPITATION = 1 << 24;
    public static final int WEATHER_FLAG_SNOW_LOW = 1 << 25;
    public static final int WEATHER_FLAG_KNOWN = 1 << 26;
    public static final int WEATHER_TEMPERATURE_Y = 80;
    public static final int WEATHER_EVAL_DROP = 10;
    public static final float RAIN_PERIOD = 1.25f;
    public static final float RAIN_WIDTH = 0.011f;
    public static final float RAIN_ALPHA = 0.6f;
    public static final float RAIN_TAIL = 0.3f;
    public static final float SNOW_PERIOD = 1.0f;
    public static final float SNOW_RADIUS = 0.03f;
    public static final float SNOW_ALPHA = 0.85f;
    public static final float SNOW_SWAY = 0.1f;
    public static final int WEATHER_FALL_WRAP = 256;
    public static final float WEATHER_SIZE_SPREAD = 0.3f;
    public static final float WEATHER_BRIGHT_SPREAD = 0.25f;
    public static final float SPLASH_CELL = 0.5f;
    public static final float SPLASH_PERIOD = 0.55f;
    public static final float SPLASH_LIFE = 0.16f;
    public static final float SPLASH_RADIUS = 0.1f;
    public static final float SPLASH_RING = 0.008f;
    public static final float SPLASH_ALPHA = 0.65f;
    public static final int SPLASH_FLECKS = 4;
    public static final float SPLASH_FLECK = 0.01f;
    public static final float SPLASH_THROW = 1.1f;
    public static final float SPLASH_SURFACE = 0.02f;
    public static final float SPLASH_END = 16.0f;
    public static final float SPLASH_FADE = 6.0f;
    public static final int SPLASH_EVENTS = 64;
    public static final int SPLASH_WRAP = 1024;
    public static final float SPLASH_GRAZE = 0.2f;
    public static final float IMPACT_LIFE = 0.12f;
    public static final float IMPACT_CROWN = 0.03f;
    public static final float IMPACT_RIM = 0.004f;
    public static final float IMPACT_JET = 0.045f;
    public static final float IMPACT_JET_WIDTH = 0.004f;
    public static final int IMPACT_DROPLETS = 2;
    public static final float IMPACT_DROPLET = 0.005f;
    public static final float IMPACT_HOP = 0.05f;
    public static final float IMPACT_THROW = 0.07f;
    public static final float IMPACT_ALPHA = 0.7f;
    public static final float IMPACT_END = 10.0f;
    public static final float IMPACT_FADE = 4.0f;
    public static final int WEATHER_FLAG_WATER = 1 << 27;
    public static final int WEATHER_TYPE_ROUND = 0;
    public static final int WEATHER_TYPE_PIXEL = 1;
    public static final int WEATHER_TYPE_BOTH = 2;
    public static final int PIXEL_GRID = 5;
    public static final float SNOW_PIXEL_HALF = 0.07f;
    public static final float SNOW_PIXEL_ALPHA = 0.9f;
    public static final float SNOW_PIXEL_SPIN = 0.2f;
    public static final int SNOW_PIXEL_SHAPES = 4;
    public static final int SNOW_PIXEL_SHAPE_0 = 145536;
    public static final int SNOW_PIXEL_SHAPE_1 = 4353156;
    public static final int SNOW_PIXEL_SHAPE_2 = 18157905;
    public static final int SNOW_PIXEL_SHAPE_3 = 4543812;
    public static final float RAIN_PIXEL_HALF_WIDTH = 0.0125f;
    public static final float RAIN_PIXEL_HALF_LENGTH = 0.15f;
    public static final float RAIN_PIXEL_ALPHA = 0.6f;
    public static final float WEATHER_BOTH_SPLIT = 8.0f;
    public static final float PIXEL_TEXEL_BLEND_LOW = 0.5f;
    public static final float PIXEL_TEXEL_BLEND_HIGH = 1.0f;
    public static final int WEATHER_PRIME_X = 73856093;
    public static final int WEATHER_PRIME_Z = 19349663;
    public static final int WEATHER_PRIME_Y = 50331653;
    public static final int WEATHER_PRIME_SEED = 83492791;

    /** No precipitation: weatherKind's 0; rain 1, snow 2. */
    static final int NONE = 0, RAIN = 1, SNOW = 2;
    /** The largest and smallest y a packed column holds (12 bits around WEATHER_Y_BIAS). */
    static final int Y_MIN = -WEATHER_Y_BIAS, Y_MAX = 4095 - WEATHER_Y_BIAS;

    /** The precipitation map's packed column (weatherMapAt), by world column modulo WEATHER_MAP_SIZE. */
    public interface Map {
        int at(int texelX, int texelZ);
    }

    /** weatherLight: the colour (display values) of a drop at (x, y, z) blocks from the camera. */
    public interface Light {
        float[] at(float x, float y, float z, boolean snow);
    }

    private WeatherMath() {
    }

    /**
     * PrecipitationMap's texel: height the MOTION_BLOCKING height (the first free block), snowLine the lowest y above
     * WEATHER_TEMPERATURE_Y where it snows (Y_MAX: nowhere), precipitation whether the biome has any, snowLow whether it snows at and
     * below WEATHER_TEMPERATURE_Y; known always set.
     */
    public static int pack(int height, int snowLine, boolean precipitation, boolean snowLow) {
        return pack(height, snowLine, precipitation, snowLow, false);
    }

    /** 2026-11-10: with water at the column's top (the crowns of the rings' drops). */
    public static int pack(int height, int snowLine, boolean precipitation, boolean snowLow, boolean water) {
        int h = Math.max(Y_MIN, Math.min(Y_MAX, height)) + WEATHER_Y_BIAS;
        int s = Math.max(Y_MIN, Math.min(Y_MAX, snowLine)) + WEATHER_Y_BIAS;
        return h | (s << 12) | (precipitation ? WEATHER_FLAG_PRECIPITATION : 0) | (snowLow ? WEATHER_FLAG_SNOW_LOW : 0) | (water ? WEATHER_FLAG_WATER : 0)
                | WEATHER_FLAG_KNOWN;
    }

    public static int hash(int x, int y, int z, int seed) {
        int h = (x * WEATHER_PRIME_X) ^ (y * WEATHER_PRIME_Y) ^ (z * WEATHER_PRIME_Z) ^ (seed * WEATHER_PRIME_SEED);
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    public static float[] bytes(int h) {
        return new float[]{(h & 0xFF) / 255f, ((h >>> 8) & 0xFF) / 255f, ((h >>> 16) & 0xFF) / 255f, (h >>> 24) / 255f};
    }

    public static float layerEnd(int layer) {
        return layer == 0 ? WEATHER_LAYER_END_0 : (layer == 1 ? WEATHER_LAYER_END_1 : WEATHER_LAYER_END_2);
    }

    public static int height(int texel) {
        return (texel & 0xFFF) - WEATHER_Y_BIAS;
    }

    public static int kind(int texel, int cameraY) {
        if ((texel & WEATHER_FLAG_KNOWN) == 0 || (texel & WEATHER_FLAG_PRECIPITATION) == 0) return NONE;
        int y = Math.max(cameraY - WEATHER_EVAL_DROP, height(texel));
        boolean snow = y <= WEATHER_TEMPERATURE_Y ? (texel & WEATHER_FLAG_SNOW_LOW) != 0 : y >= ((texel >>> 12) & 0xFFF) - WEATHER_Y_BIAS;
        return snow ? SNOW : RAIN;
    }

    public static float[] halfStreak(float[] motion, float cell, float cellHeight, float sway) {
        float side = (float) Math.sqrt(motion[0] * motion[0] + motion[2] * motion[2]);
        float sideLimit = Math.max(cell * WEATHER_MARGIN - sway, 0);
        float upLimit = cellHeight * WEATHER_MARGIN;
        float k = 1;
        if (side > sideLimit) k = Math.min(k, sideLimit / side);
        if (Math.abs(motion[1]) > upLimit) k = Math.min(k, upLimit / Math.abs(motion[1]));
        return new float[]{motion[0] * k, motion[1] * k, motion[2] * k};
    }

    /** x the distance, y the ray's distance there, z where on a..b (0 at a). */
    public static float[] raySegment(float[] dir, float[] a, float[] b) {
        float[] e = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        float ee = dot(e, e), de = dot(dir, e);
        float denom = ee - de * de;
        float s = denom > 1e-8f ? clamp((de * dot(dir, a) - dot(e, a)) / denom, 0, 1) : 0.5f;
        float[] q = {a[0] + e[0] * s, a[1] + e[1] * s, a[2] + e[2] * s};
        float t = Math.max(dot(dir, q), 0);
        float dx = dir[0] * t - q[0], dy = dir[1] * t - q[1], dz = dir[2] * t - q[2];
        return new float[]{(float) Math.sqrt(dx * dx + dy * dy + dz * dz), t, s};
    }

    public static float coverage(float dist, float width, float t, float pixelAngle, float alpha) {
        float footprint = pixelAngle * t;
        float w = (float) Math.sqrt(width * width + footprint * footprint);
        return alpha * (width / w) * (1 - smoothstep(0, w, dist));
    }

    /** weatherSnowShape: the PIXEL_GRID mask of snowflake shape index. */
    public static int snowShape(int index) {
        return index == 0 ? SNOW_PIXEL_SHAPE_0 : (index == 1 ? SNOW_PIXEL_SHAPE_1 : (index == 2 ? SNOW_PIXEL_SHAPE_2 : SNOW_PIXEL_SHAPE_3));
    }

    /**
     * weatherSprite: a pixel sprite at a (blocks from the camera) facing the camera, halfSize {across, up}, turned by angle: {its cover of
     * the pixel along unit dir (0..1, before the sprite's alpha), the ray's distance at it}; shape a PIXEL_GRID mask or -1 (solid).
     */
    public static float[] sprite(float[] dir, float[] a, float[] halfSize, float angle, int shape, float pixelAngle) {
        float aa = dot(a, a), da = dot(dir, a);
        if (!(da > 0) || aa < 1e-8f) return new float[2];
        float t = aa / da;
        float[] q = {dir[0] * t - a[0], dir[1] * t - a[1], dir[2] * t - a[2]};
        float inv = (float) (1 / Math.sqrt(aa));
        float[] f = {a[0] * inv, a[1] * inv, a[2] * inv};
        float hx = f[2], hz = -f[0];
        float hl = (float) Math.sqrt(hx * hx + hz * hz);
        float[] right = hl > 1e-4f ? new float[]{hx / hl, 0, hz / hl} : new float[]{1, 0, 0};
        float[] up = {f[1] * right[2] - f[2] * right[1], f[2] * right[0] - f[0] * right[2], f[0] * right[1] - f[1] * right[0]};
        float u0 = dot(q, right), v0 = dot(q, up);
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float u = c * u0 + s * v0, v = c * v0 - s * u0;
        float foot = 0.5f * pixelAngle * t;
        float wideX = Math.max(halfSize[0], foot), wideY = Math.max(halfSize[1], foot);
        if (Math.abs(u) > wideX || Math.abs(v) > wideY) return new float[]{0, t};
        float shrink = (halfSize[0] / wideX) * (halfSize[1] / wideY);
        if (shape < 0) return new float[]{shrink, t};
        int cx = Math.max(0, Math.min(PIXEL_GRID - 1, (int) Math.floor((u / halfSize[0] * 0.5f + 0.5f) * PIXEL_GRID)));
        int cy = Math.max(0, Math.min(PIXEL_GRID - 1, (int) Math.floor((v / halfSize[1] * 0.5f + 0.5f) * PIXEL_GRID)));
        boolean inside = Math.abs(u) <= halfSize[0] && Math.abs(v) <= halfSize[1];
        float bit = inside ? (shape >> ((PIXEL_GRID - 1 - cy) * PIXEL_GRID + cx)) & 1 : 0;
        float mean = Integer.bitCount(shape) / (float) (PIXEL_GRID * PIXEL_GRID);
        float texel = 2 * Math.min(halfSize[0], halfSize[1]) / PIXEL_GRID;
        return new float[]{mix(bit, mean, smoothstep(PIXEL_TEXEL_BLEND_LOW, PIXEL_TEXEL_BLEND_HIGH, 2 * foot / texel)) * shrink, t};
    }

    /** weatherDrop: blends one cell's drop under acc (premultiplied rgb, alpha); style {rain's type, snow's, rain's size, snow's}. */
    static void drop(float[] acc, float[] dir, float sceneDist, int cellX, int cellY, int cellZ, int layer, int dropIndex, boolean isSnow, float[] origin,
                     float[] camera, float[] frac, float[] params, float[] motion, float[] style, Map map, Light light) {
        int camX = (int) camera[0], camZ = (int) camera[2];
        float size = 1 << layer;
        float cellHeight = (isSnow ? SNOW_PERIOD : RAIN_PERIOD) * size;
        int mask = (WEATHER_WRAP >> layer) - 1;
        int worldX = ((camX >> layer) + cellX) & mask, worldZ = ((camZ >> layer) + cellZ) & mask;
        int seed = layer + (isSnow ? 2 * WEATHER_LAYERS : 0) + 4 * WEATHER_LAYERS * dropIndex;
        int worldY = cellY & ((WEATHER_FALL_WRAP >> layer) - 1);
        float[] r = bytes(hash(worldX, worldY, worldZ, seed));
        if (r[3] >= (isSnow ? params[1] : params[0])) return;
        float ax = (cellX + mix(WEATHER_MARGIN, 1 - WEATHER_MARGIN, r[0])) * size - origin[0];
        float ay = (cellY + mix(WEATHER_MARGIN, 1 - WEATHER_MARGIN, r[2])) * cellHeight - origin[1];
        float az = (cellZ + mix(WEATHER_MARGIN, 1 - WEATHER_MARGIN, r[1])) * size - origin[2];
        float sway = isSnow ? SNOW_SWAY : 0;
        int type = (int) ((isSnow ? style[1] : style[0]) + 0.5f);
        float scale = isSnow ? style[3] : style[2];
        float radius = (isSnow ? SNOW_RADIUS : RAIN_WIDTH) * scale;
        float[] spriteHalf = isSnow ? new float[]{SNOW_PIXEL_HALF, SNOW_PIXEL_HALF} : new float[]{RAIN_PIXEL_HALF_WIDTH, RAIN_PIXEL_HALF_LENGTH};
        for (int i = 0; i < 2; i++) spriteHalf[i] *= scale * (1 + WEATHER_SIZE_SPREAD);
        if (isSnow) {
            float limit = Math.max(size * WEATHER_MARGIN - sway, 0) * 0.70710678f;
            spriteHalf[0] = Math.min(spriteHalf[0], limit);
            spriteHalf[1] = Math.min(spriteHalf[1], limit);
        } else {
            spriteHalf[0] = Math.min(spriteHalf[0], size * WEATHER_MARGIN);
            spriteHalf[1] = Math.min(spriteHalf[1], cellHeight * WEATHER_MARGIN);
        }
        float extent = radius * (1 + WEATHER_SIZE_SPREAD);
        if (type != WEATHER_TYPE_ROUND) extent = Math.max(extent, (float) Math.hypot(spriteHalf[0], spriteHalf[1]));
        float t0 = ax * dir[0] + ay * dir[1] + az * dir[2];
        float reach = (float) Math.sqrt(motion[0] * motion[0] + motion[1] * motion[1] + motion[2] * motion[2]) + sway;
        float px = ax - dir[0] * t0, py = ay - dir[1] * t0, pz = az - dir[2] * t0;
        if ((float) Math.sqrt(px * px + py * py + pz * pz) > reach + extent + frac[3] * (Math.max(t0, 0) + reach)) return;
        int columnX = camX + (int) Math.floor(frac[0] + ax), columnZ = camZ + (int) Math.floor(frac[2] + az);
        int texel = map.at(columnX & WEATHER_MAP_MASK, columnZ & WEATHER_MAP_MASK);
        if (kind(texel, (int) Math.floor(camera[3])) != (isSnow ? SNOW : RAIN)) return;
        float[] v = bytes(hash(worldX, worldY, worldZ, seed + WEATHER_LAYERS));
        if (isSnow) {
            ax += SNOW_SWAY * (float) Math.sin(params[3] + 6.2831853f * v[0]);
            az += SNOW_SWAY * (float) Math.cos(0.8f * params[3] + 6.2831853f * v[1]);
        }
        float spread = 1 + WEATHER_SIZE_SPREAD * (2 * v[2] - 1);
        boolean pixel = isPixel(type, v[3]);
        float[] streak = new float[3];
        float[] hit;
        float alpha;
        if (pixel) {
            float k = spread / (1 + WEATHER_SIZE_SPREAD);
            float[] halfSize = {spriteHalf[0] * k, spriteHalf[1] * k};
            if (camera[3] + ay - (isSnow ? (float) Math.hypot(halfSize[0], halfSize[1]) : halfSize[1]) < height(texel)) return;
            float angle = isSnow ? 6.2831853f * v[0] + params[3] * (v[1] < 0.5f ? -SNOW_PIXEL_SPIN : SNOW_PIXEL_SPIN) : 0;
            int shape = isSnow ? snowShape((int) (fract(v[2] * 7.0f) * SNOW_PIXEL_SHAPES)) : -1;
            float[] sp = sprite(dir, new float[]{ax, ay, az}, halfSize, angle, shape, frac[3]);
            hit = new float[]{0, sp[1], 0.5f};
            alpha = sp[0] * (isSnow ? SNOW_PIXEL_ALPHA : RAIN_PIXEL_ALPHA);
        } else {
            streak = halfStreak(motion, size, cellHeight, sway);
            if (camera[3] + ay - Math.abs(streak[1]) < height(texel)) return;
            hit = raySegment(dir, new float[]{ax - streak[0], ay - streak[1], az - streak[2]}, new float[]{ax + streak[0], ay + streak[1], az + streak[2]});
            alpha = coverage(hit[0], radius * spread, hit[1], frac[3], isSnow ? SNOW_ALPHA : RAIN_ALPHA);
        }
        float start = layer == 0 ? WEATHER_NEAR : layerEnd(layer - 1);
        float fadeIn = layer == 0 ? WEATHER_NEAR_FADE : size;
        float end = layerEnd(layer);
        alpha *= smoothstep(start, start + fadeIn, hit[1]) * (1 - smoothstep(end - size, end, hit[1]));
        alpha *= clamp((sceneDist - hit[1]) / WEATHER_DEPTH_FADE, 0, 1);
        if (!isSnow && !pixel) alpha *= mix(RAIN_TAIL, 1, hit[2]);
        alpha *= params[2];
        if (alpha <= 0) return;
        float k = 2 * hit[2] - 1;
        float[] colour = light.at(ax + streak[0] * k, ay + streak[1] * k, az + streak[2] * k, isSnow);
        float bright = 1 + WEATHER_BRIGHT_SPREAD * (2 * v[3] - 1);
        for (int i = 0; i < 3; i++) acc[i] += (1 - acc[3]) * alpha * colour[i] * bright;
        acc[3] += (1 - acc[3]) * alpha;
    }

    /** weatherDrop's choice of a drop's look: PIXEL always, BOTH by its brightness byte (half each), ROUND never. */
    public static boolean isPixel(int type, float brightnessByte) {
        return type == WEATHER_TYPE_PIXEL || (type == WEATHER_TYPE_BOTH && fract(brightnessByte * WEATHER_BOTH_SPLIT) < 0.5f);
    }

    static float fract(float x) {
        return x - (float) Math.floor(x);
    }

    /** weatherWalk: one kind's drops along dir, layer by layer, cells in the frame falling with that kind, nearest first. */
    static void walk(float[] acc, float[] dir, float sceneDist, boolean isSnow, float[] camera, float[] frac, float[] params, float[] motion, float[] style,
                     Map map, Light light) {
        int camX = (int) camera[0], camZ = (int) camera[2];
        for (int layer = 0; layer < WEATHER_LAYERS; layer++) {
            float tStart = layer == 0 ? WEATHER_NEAR : layerEnd(layer - 1);
            float tEnd = Math.min(layerEnd(layer), sceneDist);
            if (tStart >= tEnd) return;
            int size = 1 << layer;
            float[] cs = {size, (isSnow ? SNOW_PERIOD : RAIN_PERIOD) * size, size};
            float[] origin = {(camX & (size - 1)) + frac[0], camera[3] + motion[3], (camZ & (size - 1)) + frac[2]};
            int[] cell = new int[3], step = new int[3];
            float[] tDelta = new float[3], tMax = new float[3];
            for (int i = 0; i < 3; i++) {
                float p = origin[i] + dir[i] * tStart;
                cell[i] = (int) Math.floor(p / cs[i]);
                step[i] = dir[i] >= 0 ? 1 : -1;
                tDelta[i] = Math.abs(dir[i]) > 1e-6f ? cs[i] / Math.abs(dir[i]) : 1e30f;
                float boundary = (cell[i] + (step[i] > 0 ? 1 : 0)) * cs[i];
                tMax[i] = Math.abs(dir[i]) > 1e-6f ? tStart + (boundary - p) / dir[i] : 1e30f;
            }
            float t = tStart;
            for (int i = 0; i < WEATHER_MAX_STEPS && t < tEnd; i++) {
                for (int k = 0; k < WEATHER_DROPS; k++) {
                    drop(acc, dir, sceneDist, cell[0], cell[1], cell[2], layer, k, isSnow, origin, camera, frac, params, motion, style, map, light);
                }
                if (acc[3] > WEATHER_OPAQUE) return;
                int a = tMax[0] <= tMax[1] && tMax[0] <= tMax[2] ? 0 : (tMax[1] <= tMax[2] ? 1 : 2);
                t = tMax[a];
                cell[a] += step[a];
                tMax[a] += tDelta[a];
            }
        }
    }

    /** weatherImpact: the crowns (premultiplied rgb, alpha) over a scene pixel; water {rings' time, rain on the water, ripple offset x, z}. */
    public static float[] impact(float[] rel, float[] dir, float dist, float[] camera, float[] frac, float[] params, float[] water, Map map, Light light) {
        float[] none = new float[4];
        if (dist >= IMPACT_END || !(water[1] > 0)) return none;
        int camX = (int) camera[0], camZ = (int) camera[2];
        int texel = map.at((camX + (int) Math.floor(frac[0] + rel[0])) & WEATHER_MAP_MASK, (camZ + (int) Math.floor(frac[2] + rel[2])) & WEATHER_MAP_MASK);
        float y = camera[3] + rel[1];
        float top = height(texel);
        if ((texel & WEATHER_FLAG_WATER) == 0 || kind(texel, (int) Math.floor(camera[3])) != RAIN || y >= top || y < top - 1) return none;
        float posX = rel[0] + water[2], posZ = rel[2] + water[3];
        float t = dist / Math.max(Math.abs(dir[1]), SPLASH_GRAZE);
        float alpha = 0;
        for (int layer = 0; layer < 2; layer++) {
            float qx = posX / RainRippleMath.RAIN_RIPPLE_CELL + layer * 0.5f, qz = posZ / RainRippleMath.RAIN_RIPPLE_CELL + layer * 0.5f;
            float bx = (float) Math.floor(qx), bz = (float) Math.floor(qz);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float[] d = RainRippleMath.drop(bx + i, bz + j, layer, water[1], water[0]);
                    if (d == null) continue;
                    float tau = d[2] * RainRippleMath.RAIN_RIPPLE_CYCLE / IMPACT_LIFE;
                    if (tau >= 1) continue;
                    float cx = (d[0] - layer * 0.5f) * RainRippleMath.RAIN_RIPPLE_CELL - water[2];
                    float cz = (d[1] - layer * 0.5f) * RainRippleMath.RAIN_RIPPLE_CELL - water[3];
                    float[] foot = {cx, rel[1], cz};
                    float a = coverage(Math.abs((float) Math.hypot(rel[0] - cx, rel[2] - cz) - IMPACT_CROWN * (float) Math.sqrt(tau)), IMPACT_RIM, t, frac[3], IMPACT_ALPHA);
                    float[] jet = raySegment(dir, foot, new float[]{cx, rel[1] + IMPACT_JET * 4 * tau * (1 - tau), cz});
                    a = Math.max(a, coverage(jet[0], IMPACT_JET_WIDTH, jet[1], frac[3], IMPACT_ALPHA));
                    for (int k = 0; k < IMPACT_DROPLETS; k++) {
                        float angle = 6.2831853f * ((float) k / IMPACT_DROPLETS + d[0] * 3.7f + d[1] * 1.3f);
                        float[] p = {cx + (float) Math.cos(angle) * IMPACT_THROW * tau, rel[1] + IMPACT_HOP * 4 * tau * (1 - tau), cz + (float) Math.sin(angle) * IMPACT_THROW * tau};
                        float[] h = raySegment(dir, p, p);
                        a = Math.max(a, coverage(h[0], IMPACT_DROPLET, h[1], frac[3], IMPACT_ALPHA));
                    }
                    alpha = Math.max(alpha, a * (1 - tau));
                }
            }
        }
        alpha *= (1 - smoothstep(IMPACT_END - IMPACT_FADE, IMPACT_END, dist)) * params[2];
        if (alpha <= 0) return none;
        float[] c = light.at(rel[0], rel[1], rel[2], false);
        return new float[]{c[0] * alpha, c[1] * alpha, c[2] * alpha, alpha};
    }

    /** weatherSplash: the splash (premultiplied rgb, alpha) over a scene pixel at rel along unit dir at distance dist; splash[0] the wrapped time. */
    public static float[] splash(float[] rel, float[] dir, float dist, float[] camera, float[] frac, float[] params, float[] splash, Map map, Light light) {
        float[] none = new float[4];
        if (dist >= SPLASH_END || params[0] <= 0) return none;
        int camX = (int) camera[0], camZ = (int) camera[2];
        float wx = frac[0] + rel[0], wz = frac[2] + rel[2];
        int columnX = camX + (int) Math.floor(wx), columnZ = camZ + (int) Math.floor(wz);
        int texel = map.at(columnX & WEATHER_MAP_MASK, columnZ & WEATHER_MAP_MASK);
        if (kind(texel, (int) Math.floor(camera[3])) != RAIN || Math.abs(camera[3] + rel[1] - height(texel)) > SPLASH_SURFACE) return none;
        float gx = camX + wx, gz = camZ + wz;
        int cellX = (int) Math.floor(gx / SPLASH_CELL), cellZ = (int) Math.floor(gz / SPLASH_CELL);
        float inX = gx - cellX * SPLASH_CELL, inZ = gz - cellZ * SPLASH_CELL;
        int mask = (int) (WEATHER_WRAP / SPLASH_CELL) - 1;
        float[] r = bytes(hash(cellX & mask, 0, cellZ & mask, 8 * WEATHER_LAYERS));
        float cycle = splash[0] / SPLASH_PERIOD + r[0];
        float event = (float) Math.floor(cycle);
        float age = (cycle - event) * SPLASH_PERIOD / SPLASH_LIFE;
        if (age >= 1) return none;
        float[] e = bytes(hash(cellX & mask, ((int) event) & (SPLASH_EVENTS - 1), cellZ & mask, 8 * WEATHER_LAYERS + 1));
        if (e[3] >= params[0]) return none;
        float cx = mix(0.25f, 0.75f, e[0]) * SPLASH_CELL, cz = mix(0.25f, 0.75f, e[1]) * SPLASH_CELL;
        float radius = SPLASH_RADIUS * (float) Math.sqrt(age);
        float t = dist / Math.max(Math.abs(dir[1]), SPLASH_GRAZE);
        float alpha = coverage(Math.abs((float) Math.hypot(inX - cx, inZ - cz) - radius), SPLASH_RING, t, frac[3], SPLASH_ALPHA);
        for (int k = 0; k < SPLASH_FLECKS; k++) {
            float a = 6.2831853f * ((k + e[2]) / SPLASH_FLECKS);
            float px = cx + (float) Math.cos(a) * radius * SPLASH_THROW, pz = cz + (float) Math.sin(a) * radius * SPLASH_THROW;
            alpha = Math.max(alpha, coverage((float) Math.hypot(inX - px, inZ - pz), SPLASH_FLECK, t, frac[3], SPLASH_ALPHA));
        }
        alpha *= (1 - age) * (1 - age) * (1 - smoothstep(SPLASH_END - SPLASH_FADE, SPLASH_END, dist)) * params[2];
        if (alpha <= 0) return none;
        float[] c = light.at(rel[0], rel[1], rel[2], false);
        return new float[]{c[0] * alpha, c[1] * alpha, c[2] * alpha, alpha};
    }

    /**
     * weatherPixel: the overlay (premultiplied rgb, alpha) along unit world dir up to sceneDist. camera: x, z the camera's block modulo
     * WEATHER_WRAP (y unused), w its y; frac: the camera within its block, w the pixel angle; params: rain and snow cell shares (0: that
     * kind's walk is skipped), coverage scale, sway angle; rain / snow: the streak's half vector, w the fall offset (WeatherUniforms).
     */
    public static float[] pixel(float[] dir, float sceneDist, float[] camera, float[] frac, float[] params, float[] rain, float[] snow, Map map, Light light) {
        return pixel(dir, sceneDist, camera, frac, params, rain, snow, DEFAULT_STYLE, map, light);
    }

    /** The default style (slot 17): both kinds round at the tuned size. */
    private static final float[] DEFAULT_STYLE = {WEATHER_TYPE_ROUND, WEATHER_TYPE_ROUND, 1, 1};

    /** weatherPixel with each kind's type and size (style {rain's type, snow's, rain's size, snow's}). */
    public static float[] pixel(float[] dir, float sceneDist, float[] camera, float[] frac, float[] params, float[] rain, float[] snow, float[] style,
                                Map map, Light light) {
        float[] acc = new float[4];
        if (params[0] > 0) walk(acc, dir, sceneDist, false, camera, frac, params, rain, style, map, light);
        if (params[1] > 0 && acc[3] <= WEATHER_OPAQUE) walk(acc, dir, sceneDist, true, camera, frac, params, snow, style, map, light);
        return acc;
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
