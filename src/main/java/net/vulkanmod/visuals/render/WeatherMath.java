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
        int h = Math.max(Y_MIN, Math.min(Y_MAX, height)) + WEATHER_Y_BIAS;
        int s = Math.max(Y_MIN, Math.min(Y_MAX, snowLine)) + WEATHER_Y_BIAS;
        return h | (s << 12) | (precipitation ? WEATHER_FLAG_PRECIPITATION : 0) | (snowLow ? WEATHER_FLAG_SNOW_LOW : 0) | WEATHER_FLAG_KNOWN;
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

    /** weatherDrop: blends one cell's drop under acc (premultiplied rgb, alpha). */
    static void drop(float[] acc, float[] dir, float sceneDist, int cellX, int cellY, int cellZ, int layer, int dropIndex, boolean isSnow, float[] origin,
                     float[] camera, float[] frac, float[] params, float[] motion, Map map, Light light) {
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
        float t0 = ax * dir[0] + ay * dir[1] + az * dir[2];
        float reach = (float) Math.sqrt(motion[0] * motion[0] + motion[1] * motion[1] + motion[2] * motion[2]) + sway;
        float px = ax - dir[0] * t0, py = ay - dir[1] * t0, pz = az - dir[2] * t0;
        if ((float) Math.sqrt(px * px + py * py + pz * pz) > reach + (isSnow ? SNOW_RADIUS : RAIN_WIDTH) * (1 + WEATHER_SIZE_SPREAD) + frac[3] * (Math.max(t0, 0) + reach)) return;
        int columnX = camX + (int) Math.floor(frac[0] + ax), columnZ = camZ + (int) Math.floor(frac[2] + az);
        int texel = map.at(columnX & WEATHER_MAP_MASK, columnZ & WEATHER_MAP_MASK);
        if (kind(texel, (int) Math.floor(camera[3])) != (isSnow ? SNOW : RAIN)) return;
        float[] v = bytes(hash(worldX, worldY, worldZ, seed + WEATHER_LAYERS));
        if (isSnow) {
            ax += SNOW_SWAY * (float) Math.sin(params[3] + 6.2831853f * v[0]);
            az += SNOW_SWAY * (float) Math.cos(0.8f * params[3] + 6.2831853f * v[1]);
        }
        float[] streak = halfStreak(motion, size, cellHeight, sway);
        if (camera[3] + ay - Math.abs(streak[1]) < height(texel)) return;
        float[] hit = raySegment(dir, new float[]{ax - streak[0], ay - streak[1], az - streak[2]}, new float[]{ax + streak[0], ay + streak[1], az + streak[2]});
        float width = (isSnow ? SNOW_RADIUS : RAIN_WIDTH) * (1 + WEATHER_SIZE_SPREAD * (2 * v[2] - 1));
        float alpha = coverage(hit[0], width, hit[1], frac[3], isSnow ? SNOW_ALPHA : RAIN_ALPHA);
        float start = layer == 0 ? WEATHER_NEAR : layerEnd(layer - 1);
        float fadeIn = layer == 0 ? WEATHER_NEAR_FADE : size;
        float end = layerEnd(layer);
        alpha *= smoothstep(start, start + fadeIn, hit[1]) * (1 - smoothstep(end - size, end, hit[1]));
        alpha *= clamp((sceneDist - hit[1]) / WEATHER_DEPTH_FADE, 0, 1);
        if (!isSnow) alpha *= mix(RAIN_TAIL, 1, hit[2]);
        alpha *= params[2];
        if (alpha <= 0) return;
        float k = 2 * hit[2] - 1;
        float[] colour = light.at(ax + streak[0] * k, ay + streak[1] * k, az + streak[2] * k, isSnow);
        float bright = 1 + WEATHER_BRIGHT_SPREAD * (2 * v[3] - 1);
        for (int i = 0; i < 3; i++) acc[i] += (1 - acc[3]) * alpha * colour[i] * bright;
        acc[3] += (1 - acc[3]) * alpha;
    }

    /** weatherWalk: one kind's drops along dir, layer by layer, cells in the frame falling with that kind, nearest first. */
    static void walk(float[] acc, float[] dir, float sceneDist, boolean isSnow, float[] camera, float[] frac, float[] params, float[] motion, Map map, Light light) {
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
                    drop(acc, dir, sceneDist, cell[0], cell[1], cell[2], layer, k, isSnow, origin, camera, frac, params, motion, map, light);
                }
                if (acc[3] > WEATHER_OPAQUE) return;
                int a = tMax[0] <= tMax[1] && tMax[0] <= tMax[2] ? 0 : (tMax[1] <= tMax[2] ? 1 : 2);
                t = tMax[a];
                cell[a] += step[a];
                tMax[a] += tDelta[a];
            }
        }
    }

    /**
     * weatherPixel: the overlay (premultiplied rgb, alpha) along unit world dir up to sceneDist. camera: x, z the camera's block modulo
     * WEATHER_WRAP (y unused), w its y; frac: the camera within its block, w the pixel angle; params: rain and snow cell shares (0: that
     * kind's walk is skipped), coverage scale, sway angle; rain / snow: the streak's half vector, w the fall offset (WeatherUniforms).
     */
    public static float[] pixel(float[] dir, float sceneDist, float[] camera, float[] frac, float[] params, float[] rain, float[] snow, Map map, Light light) {
        float[] acc = new float[4];
        if (params[0] > 0) walk(acc, dir, sceneDist, false, camera, frac, params, rain, map, light);
        if (params[1] > 0 && acc[3] <= WEATHER_OPAQUE) walk(acc, dir, sceneDist, true, camera, frac, params, snow, map, light);
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
