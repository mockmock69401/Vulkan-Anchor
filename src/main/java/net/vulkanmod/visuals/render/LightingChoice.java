package net.vulkanmod.visuals.render;

/**
 * Whether this frame lights the whole screen (design 2026-10-11 P7d 3.3), decided once in frame() before the LOD passes:
 * with AO the LOD SSAO only copies the colour, with sun shading the composite's P4b face shading is off. The post-process
 * of the same frame (same serial) applies the lighting. P7e-1 3.4: the water depth colour is a third effect; the pass, and
 * with it the translucent detection and the depth copies, runs for any of them. P7e-2 3.4: the water reflections are a fourth effect.
 */
public record LightingChoice(long serial, boolean ao, boolean sun, boolean water, boolean reflections) {
    public static final LightingChoice NONE = new LightingChoice(Long.MIN_VALUE, false, false, false, false);

    public static LightingChoice of(long serial, PostProcessSettings s, boolean postAvailable, boolean atmosphereCaptured, boolean fogCoversAll,
                                    boolean hasSkyLight) {
        boolean on = s.enabled() && postAvailable && atmosphereCaptured && !fogCoversAll && hasSkyLight;
        return new LightingChoice(serial, on && s.aoStrength() > 0, on && s.sunShading() > 0, on && s.waterDepth() > 0, on && reflectionPass(s));
    }

    /** Whether the reflection pass has work: water reflections, or 2026-10-29 the block reflections at their second level. */
    public static boolean reflectionPass(PostProcessSettings s) {
        return s.waterReflections() > 0 || s.blockReflections() >= BlockReflectionMath.REFLECTIONS;
    }

    public boolean any() {
        return this.ao || this.sun || this.water || this.reflections;
    }
}
