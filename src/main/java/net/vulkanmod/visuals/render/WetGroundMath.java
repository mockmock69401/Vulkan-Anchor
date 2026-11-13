package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-13 (shader roadmap session 20, the user after AstraLex: "SSR ... 비 온 뒤 젖은 지면의 반사"): rain-wet ground without a PBR pack
 * (wetground.glsl: the composite darkens and marks, the reflection pass mirrors). Mirrors wetground.glsl's constants and functions
 * (WetGroundTest compares them) and writes the composite's wetParams (SLOT): x the strength (setting / 100; 0 = off: the composite and the
 * reflection pass as before), zw the camera's x, z wrapped at PUDDLE_PERIOD. The wetness itself is PBR's (PbrUniforms: the rain at the
 * camera, eased in and dried out), so both paths dry together.
 */
public final class WetGroundMath {
    public static final int SLOT = 72;
    /** The setting's range (percent). */
    public static final int STRENGTH_MAX = 200;
    public static final float MARK_BASE = 5.0f, MARK_TOP = -4.5f, MARK_BOTTOM = -6.5f, SHARE_MIN = 0.004f;
    public static final float SKY_LOW = 13.5f, SKY_HIGH = 14.75f;
    public static final float DARKEN = 0.3f, PUDDLE_DARKEN = 0.45f, SATURATION = 0.12f;
    public static final float FILM = 0.3f, SIDE_FILM = 0.35f;
    public static final float PUDDLE_CELL = 3.0f, PUDDLE_PERIOD = 240.0f, PUDDLE_COVER = 0.42f, PUDDLE_EDGE = 0.06f, PUDDLE_UP = 0.9f;
    public static final float PUDDLE_DETAIL = 0.35f, MARCH_SHARE = 0.5f, REFLECT_MIN = 0.01f;
    public static final float DEBUG_WET_RED = 0.2f, DEBUG_WET_GREEN = 0.5f, DEBUG_WET_BLUE = 1.0f;

    private WetGroundMath() {
    }

    /** wetParams for a setting (percent) and the camera's x, z: all 0 when off. */
    public static float[] params(int setting, double cameraX, double cameraZ) {
        float s = Math.max(0, Math.min(STRENGTH_MAX, setting)) / 100f;
        if (!(s > 0)) return new float[4];
        return new float[]{s, 0, (float) wrap(cameraX), (float) wrap(cameraZ)};
    }

    /** This frame's wetParams. */
    public static float[] params(int setting) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return params(setting, camera.x, camera.z);
    }

    /** Whether the reflection pass has wet ground to mirror this frame (the setting on and the ground wet). */
    public static boolean reflectionWanted() {
        return PbrUniforms.wetness() > 0 && VisualsConfig.get().wetGround > 0;
    }

    static double wrap(double v) {
        return v - PUDDLE_PERIOD * Math.floor(v / PUDDLE_PERIOD);
    }

    public static boolean isWetMark(float mark) {
        return mark <= MARK_TOP && mark >= MARK_BOTTOM;
    }

    public static float shareOf(float mark) {
        return clamp(-mark - MARK_BASE, 0, 1);
    }

    /** wetGroundAt. */
    public static float wetAt(float wetness, float skyLevel, float strength) {
        return clamp(wetness, 0, 1) * smoothstep(SKY_LOW, SKY_HIGH, skyLevel) * clamp(strength, 0, 1);
    }

    /** wetHash: a lattice value 0..1 at cell (cx, cz), repeating every period cells. */
    public static float hash(float cx, float cz, float period) {
        float mx = cx - period * (float) Math.floor(cx / period), mz = cz - period * (float) Math.floor(cz / period);
        int h = ((int) mx * 73856093) ^ ((int) mz * 19349663);
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 65535) / 65535f;
    }

    /** wetNoise. */
    public static float noise(float x, float z, float period) {
        float cx = (float) Math.floor(x), cz = (float) Math.floor(z);
        float fx = x - cx, fz = z - cz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = hash(cx, cz, period), b = hash(cx + 1, cz, period), d = hash(cx, cz + 1, period), e = hash(cx + 1, cz + 1, period);
        return mix(mix(a, b, fx), mix(d, e, fx), fz);
    }

    /** wetPuddleField. */
    public static float puddleField(float x, float z) {
        float cells = PUDDLE_PERIOD / PUDDLE_CELL;
        float px = x / PUDDLE_CELL, pz = z / PUDDLE_CELL;
        return (1 - PUDDLE_DETAIL) * noise(px, pz, cells) + PUDDLE_DETAIL * noise(px * 2 + 17, pz * 2 + 31, 2 * cells);
    }

    /** wetPuddle (ny: the face normal's y). */
    public static float puddle(float x, float z, float ny, float wet, float strength) {
        if (!(wet > 0) || ny < PUDDLE_UP) return 0;
        float t = 1 - PUDDLE_COVER * clamp(strength, 0, 2) * wet;
        return smoothstep(t, t + PUDDLE_EDGE, puddleField(x, z));
    }

    /** wetGroundColour: the darkening factor on a grey (the saturation leaves a grey as it is). */
    public static float darkening(float wet, float puddle) {
        return 1 - clamp(wet, 0, 1) * mix(DARKEN, PUDDLE_DARKEN, clamp(puddle, 0, 1));
    }

    /** wetShare. */
    public static float share(float wet, float puddle, float ny) {
        float film = FILM * clamp(wet, 0, 1) * mix(SIDE_FILM, 1, clamp(ny, 0, 1));
        return mix(film, 1, clamp(puddle, 0, 1));
    }

    /** wetGroundMark. */
    public static float mark(float share) {
        return share >= SHARE_MIN ? -(MARK_BASE + clamp(share, 0, 1)) : 0;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
