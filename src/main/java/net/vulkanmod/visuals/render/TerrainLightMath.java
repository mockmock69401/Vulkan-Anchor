package net.vulkanmod.visuals.render;

/**
 * The terrain light's value (2026-10-23, TerrainLightTarget): the Java mirror of terrain_light.glsl's packTerrainLight (the terrain
 * shaders write it) and lightvolume.glsl's terrainLightAt (the composite reads it). Every shader const is a field of the same name
 * here (TerrainLightMathTest compares them). A value is the fragment's window depth in 20 bits above its sky and block levels in
 * quarter steps, 6 bits each, so the smallest value of a pixel is its nearest fragment; NONE (all ones) where no terrain was drawn.
 */
public final class TerrainLightMath {
    public static final float TERRAIN_LIGHT_DEPTH_STEPS = 1048576f;
    public static final int TERRAIN_LIGHT_DEPTH_MAX = 1048575;
    public static final float TERRAIN_LIGHT_LEVEL_STEPS = 4f;
    public static final int TERRAIN_LIGHT_LEVEL_MAX = 60;
    /** How many depth steps the stored depth may differ from the main depth's and still be the same surface. */
    public static final int TERRAIN_LIGHT_DEPTH_SLACK = 1;
    /** The cleared value: no terrain fragment. */
    public static final int NONE = 0xFFFFFFFF;
    /** The light source the composite reports for the terrain light (pixelLight's z; 1 is the volume's or the LOD pass's). */
    public static final float SOURCE = 2;

    private TerrainLightMath() {
    }

    /** terrainLightLevel: a level 0..15 in quarter steps. */
    public static int level(float level) {
        return Math.min(Math.round(Math.max(0, Math.min(15, level)) * TERRAIN_LIGHT_LEVEL_STEPS), TERRAIN_LIGHT_LEVEL_MAX);
    }

    /** The window depth's 20-bit step (floor, as the shaders' uint conversion). */
    public static int depthStep(float depth) {
        return (int) Math.min((long) (Math.max(0, Math.min(1, depth)) * TERRAIN_LIGHT_DEPTH_STEPS), TERRAIN_LIGHT_DEPTH_MAX);
    }

    /** packTerrainLight: depth gl_FragCoord.z, the interpolated sky and block levels. */
    public static int pack(float depth, float sky, float block) {
        return depthStep(depth) << 12 | level(sky) << 6 | level(block);
    }

    /** terrainLightAt: (sky, block, SOURCE) when value holds the surface at the main depth mainDepth, else null. */
    public static float[] at(int value, float mainDepth) {
        if (value == NONE) return null;
        int stored = value >>> 12;
        if (Math.abs(depthStep(mainDepth) - stored) > TERRAIN_LIGHT_DEPTH_SLACK) return null;
        return new float[]{((value >>> 6) & 63) / TERRAIN_LIGHT_LEVEL_STEPS, (value & 63) / TERRAIN_LIGHT_LEVEL_STEPS, SOURCE};
    }

    // ---- the albedo (terrain_light.glsl's packTerrainAlbedo, scene_copy.comp's terrainAlbedoAt) ----
    /** Set on a written albedo (the cleared value 0 is not). */
    public static final int TERRAIN_ALBEDO_WRITTEN = 0x80000000;
    /** The light value's depth step bits the albedo carries as its tag. */
    public static final int TERRAIN_ALBEDO_TAG_MASK = 127;
    /** Only opaque texels write an albedo. */
    public static final float TERRAIN_ALBEDO_MIN_ALPHA = 0.999f;

    /** packTerrainAlbedo: display rgb 0..1 of the fragment whose light value is light. */
    public static int packAlbedo(float r, float g, float b, int light) {
        return TERRAIN_ALBEDO_WRITTEN | (((light >>> 12) & TERRAIN_ALBEDO_TAG_MASK) << 24) | channel(b) << 16 | channel(g) << 8 | channel(r);
    }

    private static int channel(float c) {
        return Math.round(Math.max(0, Math.min(1, c)) * 255);
    }

    /** terrainAlbedoAt: the display rgb when albedo was written by the fragment the light value light holds, else null. */
    public static float[] albedoAt(int light, int albedo) {
        if ((albedo & TERRAIN_ALBEDO_WRITTEN) == 0) return null;
        if (((albedo >>> 24) & TERRAIN_ALBEDO_TAG_MASK) != ((light >>> 12) & TERRAIN_ALBEDO_TAG_MASK)) return null;
        return new float[]{(albedo & 255) / 255f, ((albedo >>> 8) & 255) / 255f, ((albedo >>> 16) & 255) / 255f};
    }

    /** The image's value after the given fragments in any order (the atomic min, unsigned). */
    public static int nearest(int... values) {
        int out = NONE;
        for (int v : values) if (Integer.compareUnsigned(v, out) < 0) out = v;
        return out;
    }
}
