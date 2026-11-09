package net.vulkanmod.visuals.render;

/**
 * 2026-10-21 (CTE2: the view under water was too opaque): with the post-processing on, vanilla's water fog (FogRenderer.setupFog,
 * camera in water) ends END_SCALE times as far. Its start stays; the composite still sees the view at UNDERWATER_EXPOSURE.
 * 2026-11-09 (session 2b; the user: under water the view was far too clear): the composite now draws the water between the camera
 * and what it sees itself (underwater.glsl), so vanilla's linear fog is pushed out of the way: END_SCALE 2 -> 64 (vanilla's 96 blocks
 * become 6144: about 4% of fog at a render distance of 256 blocks, where the water already hides everything). Only while the composite
 * draws the water (UnderwaterUniforms.drawn: fog strength above 0); otherwise vanilla's fog is left as it is.
 */
public final class UnderwaterFog {
    public static final float END_SCALE = 64.0f;

    private UnderwaterFog() {
    }

    /** The water fog's end for vanilla's end, with the camera in water and the composite drawing the water (UnderwaterUniforms.drawn) or not. */
    public static float end(float vanillaEnd, boolean inWater, boolean composited) {
        return inWater && composited && vanillaEnd > 0 && Float.isFinite(vanillaEnd) ? vanillaEnd * END_SCALE : vanillaEnd;
    }
}
