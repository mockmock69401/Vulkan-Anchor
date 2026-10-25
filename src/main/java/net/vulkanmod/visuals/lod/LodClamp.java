package net.vulkanmod.visuals.lod;

/** The LOD composite's clamp depth (lod_composite.frag) and the threshold the post passes compare the main depth against. */
public final class LodClamp {
    /**
     * The depth lod_composite.frag writes for LODs past vanilla's far plane: REDUCTION2(FAR + CLOSER_SIGN*(2/(2^24-1)), d),
     * exactly representable in D32F and D24. Vanilla geometry only reaches it within a hair of the far plane.
     */
    public static final float LOD_CLAMP_DEPTH = 1f - 2f / ((1 << 24) - 1);
    /** Half a float step below the clamp value: what the post passes compare the main depth against (their depthParams.x). */
    public static final float CLAMP_THRESHOLD = LOD_CLAMP_DEPTH - 6e-8f;

    private LodClamp() {
    }
}
