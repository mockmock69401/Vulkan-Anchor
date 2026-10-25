package net.vulkanmod.visuals.render;

/**
 * 2026-10-21 (CTE2: the view under water was too opaque): with the post-processing on, vanilla's water fog (FogRenderer.setupFog,
 * camera in water) ends END_SCALE times as far. Its start stays; the composite still sees the view at UNDERWATER_EXPOSURE.
 */
public final class UnderwaterFog {
    public static final float END_SCALE = 2.0f;

    private UnderwaterFog() {
    }

    /** The water fog's end for vanilla's end, with the camera in water and the post-processing on or not. */
    public static float end(float vanillaEnd, boolean inWater, boolean postOn) {
        return inWater && postOn && vanillaEnd > 0 && Float.isFinite(vanillaEnd) ? vanillaEnd * END_SCALE : vanillaEnd;
    }
}
