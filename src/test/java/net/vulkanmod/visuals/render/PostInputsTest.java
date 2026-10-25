package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.api.LodInputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostInputsTest {
    private static final long MAIN = 1, LOD_DEPTH = 2, BAND = 3, NEAR_A = 10, NEAR_B = 11, FAR_A = 20, FAR_B = 21, CLOSE_A = 30, CLOSE_B = 31,
            BEFORE = 40, AFTER = 41;

    private static LodInputs lod() {
        return new LodInputs(null, LOD_DEPTH, BAND, 99L, null, true, 16f);
    }

    private static VkShadowMaps.Maps maps(long a, long b) {
        return new VkShadowMaps.Maps(null, null, 0.5f, a, b);
    }

    @Test
    void noLodUsesMainDepth() {
        VkPostProcess.Depths d = PostInputs.depths(MAIN, null, true, BEFORE, AFTER, maps(NEAR_A, NEAR_B), null, null);
        assertEquals(MAIN, d.lod());
        assertEquals(MAIN, d.lodOpaque());
    }

    @Test
    void lodWithoutLightingSkipsBand() {
        VkPostProcess.Depths d = PostInputs.depths(MAIN, lod(), false, BEFORE, AFTER, null, null, null);
        assertEquals(LOD_DEPTH, d.lod());
        assertEquals(MAIN, d.lodOpaque());
    }

    @Test
    void lodWithLighting() {
        VkPostProcess.Depths d = PostInputs.depths(MAIN, lod(), true, BEFORE, AFTER, null, null, null);
        assertEquals(LOD_DEPTH, d.lod());
        assertEquals(BAND, d.lodOpaque());
    }

    @Test
    void missingCascadeFallsBack() {
        VkPostProcess.Depths d = PostInputs.depths(MAIN, null, true, BEFORE, AFTER, null, maps(FAR_A, FAR_B), maps(CLOSE_A, CLOSE_B));
        assertEquals(MAIN, d.shadowNear());
        assertEquals(MAIN, d.shadowNearB());
        assertEquals(FAR_A, d.shadowFar());
        assertEquals(FAR_B, d.shadowFarB());
        assertEquals(CLOSE_A, d.shadowClose());
        assertEquals(CLOSE_B, d.shadowCloseB());
    }

    @Test
    void translucentDepthsPassThrough() {
        VkPostProcess.Depths d = PostInputs.depths(MAIN, null, false, BEFORE, AFTER, null, null, null);
        assertEquals(BEFORE, d.beforeTranslucent());
        assertEquals(AFTER, d.afterTranslucent());
        assertEquals(MAIN, d.main());
    }
}
