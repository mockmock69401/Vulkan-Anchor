package net.vulkanmod.visuals.lod;

/** The LOD composite's clamp depth (lod_composite.frag) and the threshold the post passes compare the main depth against. */
public final class LodClamp {
    /**
     * The depth lod_composite.frag writes for LODs past vanilla's far plane (its FAR_LIMIT, three 24-bit steps short of 1), exactly
     * representable in D32F and D24. Vanilla geometry only reaches it within a hair of the far plane. 2026-10-30 (audit): this said
     * two steps while the shader wrote three; the threshold below matched the written value only by float rounding.
     */
    public static final float LOD_CLAMP_DEPTH = 1f - 3f / ((1 << 24) - 1);
    /**
     * What the post passes compare the main depth against (their depthParams.x): three 24-bit steps below the clamp value, so a D24
     * depth that reads back a step or two lower (the unorm-to-float conversion) still counts as a clamped LOD pixel.
     */
    public static final float CLAMP_THRESHOLD = 1f - 6f / ((1 << 24) - 1);

    private LodClamp() {
    }
}
