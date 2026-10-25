package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.api.LodInputs;

/** The post inputs as a pure function: which depth view each post slot reads for this frame. */
public final class PostInputs {
    private PostInputs() {
    }

    /**
     * Builds the depth views post reads. With no LOD the LOD slots are the main depth; without lighting the opaque band is
     * the main depth. A missing shadow cascade (null) puts the main depth in both of its slots.
     */
    public static VkPostProcess.Depths depths(long main, LodInputs lod, boolean lightingOn, long before, long after,
                                              VkShadowMaps.Maps near, VkShadowMaps.Maps far, VkShadowMaps.Maps close) {
        long lodDepth = lod == null ? main : lod.depthView();
        long lodOpaque = lod == null || !lightingOn ? main : lod.opaqueBandView();
        return new VkPostProcess.Depths(main, lodDepth, before, after, lodOpaque,
                viewA(near, main), viewA(far, main), viewB(near, main), viewB(far, main), viewA(close, main), viewB(close, main));
    }

    private static long viewA(VkShadowMaps.Maps m, long main) {
        return m == null ? main : m.viewA();
    }

    private static long viewB(VkShadowMaps.Maps m, long main) {
        return m == null ? main : m.viewB();
    }
}
