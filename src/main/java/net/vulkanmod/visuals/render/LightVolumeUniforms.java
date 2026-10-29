package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/** P7l design 3.3-3.6: the copy's (locations 18-21) and the reflection's (19-22) light volume block. */
public final class LightVolumeUniforms {
    public static final int COPY_FIRST = 18, REFLECTION_FIRST = 19;
    private static final LightVolumeMath.Layout NONE = new LightVolumeMath.Layout(1, 1, 1, 0);

    /**
     * camera: the camera's block (exact in float to 2^24); fraction: inside it; sky: a sky light dimension; sun: this frame's sun
     * visibility is valid; terrain: this frame's terrain light is filled (TerrainLightTarget; the camera slot's w is then 2).
     */
    public record Values(boolean on, float[] camera, float[] fraction, LightVolumeMath.Layout layout, float lift, int debug, boolean sky, boolean sun,
                         boolean terrain) {
        public void write(LooseUniformWriter w, int first) {
            w.putVec4(first, this.camera[0], this.camera[1], this.camera[2], this.on ? (this.terrain ? 2 : 1) : 0);
            w.putVec4(first + 1, this.fraction[0], this.fraction[1], this.fraction[2], this.lift);
            w.putVec4(first + 2, this.layout.slotsX(), this.layout.slotsY(), this.layout.slotsZ(), this.layout.minSectionY());
            w.putVec4(first + 3, this.debug, RelightMath.MAX_GAIN, this.sky ? 1 : 0, this.sun ? 1 : 0);
        }

        /** These values with the terrain light filled or not (only while on). */
        public Values withTerrain(boolean filled) {
            return new Values(this.on, this.camera, this.fraction, this.layout, this.lift, this.debug, this.sky, this.sun, this.on && filled);
        }
    }

    private LightVolumeUniforms() {
    }

    public static Values off() {
        return new Values(false, new float[3], new float[3], NONE, 0, 0, false, false, false);
    }

    /**
     * On with the setting and a volume (layout not null). The lift is Sunlit Sky Light x day when this frame's sun visibility
     * is valid (lighting pass with shadow maps) in a sky light dimension; else 0.
     */
    /**
     * 2026-10-30 (audit): the camera's block as the shaders get it, a float: floor(v), made even past 2^24 blocks, where a float holds
     * only even integers (lookups were 1-2 blocks off there). The fraction is then up to 2; the shaders add it to the view offset and
     * take whole blocks, so they need no change.
     */
    static double exactBlock(double v) {
        double b = Math.floor(v);
        return Math.abs(b) < (1 << 24) ? b : b - Math.floorMod((long) b, 2L);
    }

    public static Values compute(PostProcessSettings s, LightVolumeMath.Layout layout, double x, double y, double z, AtmosphereFrame frame, long serial,
                                 boolean hasSky, boolean sunValid) {
        if (!s.perPixelLight() || layout == null) return off();
        double bx = exactBlock(x), by = exactBlock(y), bz = exactBlock(z);
        boolean sun = sunValid && hasSky;
        float day = sun && frame != null && frame.serial() == serial
                ? SunMath.dayFactor(SunMath.sunDirection(frame.timeOfDay()).y, frame.rain()) : 0;
        return new Values(true, new float[]{(float) bx, (float) by, (float) bz}, new float[]{(float) (x - bx), (float) (y - by), (float) (z - bz)},
                layout, sun ? s.skyLightAmbient() * day : 0, s.lightDebug() == PostProcessSettings.REFLECTIVE_DEBUG ? 0 : s.lightDebug(), hasSky, sun, false);
    }
}
