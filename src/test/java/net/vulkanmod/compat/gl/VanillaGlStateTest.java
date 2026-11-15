package net.vulkanmod.compat.gl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 23: vanilla's blend record follows VulkanMod's blend calls (Conquest's crosshair under ImmediatelyFast's HUD batching). */
class VanillaGlStateTest {
    static final class Blend implements VanillaGlState.BlendRecord {
        int[] f = {1, 0, 1, 0};

        @Override
        public void vulkanmod$setFactors(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            f = new int[]{srcRgb, dstRgb, srcAlpha, dstAlpha};
        }

        @Override
        public void vulkanmod$setRgbFactors(int srcRgb, int dstRgb) {
            f = new int[]{srcRgb, dstRgb, f[2], f[3]};
        }
    }

    static class Cap implements VanillaGlState.CapRecord {
        boolean on;

        @Override
        public void vulkanmod$setEnabled(boolean enabled) throws IllegalAccessException {
            on = enabled;
        }
    }

    @AfterEach
    void reset() {
        VanillaGlState.failed("test", new RuntimeException("reset after the test"));
    }

    @Test
    void theRecordFollowsEveryBlendCall() {
        Blend blend = new Blend();
        Cap cap = new Cap();
        VanillaGlState.registerBlend(blend);
        VanillaGlState.registerBlendCap(cap);

        VanillaGlState.blendEnabled(true);
        //Gui.renderCrosshair's inverting blend: ONE_MINUS_DST_COLOR, ONE_MINUS_SRC_COLOR, ONE, ZERO
        VanillaGlState.blendFuncSeparate(775, 769, 1, 0);
        assertTrue(cap.on);
        assertArrayEquals(new int[]{775, 769, 1, 0}, blend.f);
        //blendFunc leaves the alpha factors, as vanilla's _blendFunc does
        VanillaGlState.blendFunc(770, 771);
        assertArrayEquals(new int[]{770, 771, 1, 0}, blend.f);
        VanillaGlState.blendEnabled(false);
        assertFalse(cap.on);
    }

    @Test
    void aRecordThatFailsIsDroppedAndNothingThrows() {
        VanillaGlState.blendFuncSeparate(1, 0, 1, 0);
        Cap failing = new Cap() {
            @Override
            public void vulkanmod$setEnabled(boolean enabled) throws IllegalAccessException {
                throw new IllegalAccessException("test");
            }
        };
        VanillaGlState.registerBlendCap(failing);
        VanillaGlState.blendEnabled(true);
        Cap later = new Cap();
        VanillaGlState.registerBlendCap(later);
        VanillaGlState.blendEnabled(true);
        assertTrue(later.on, "after a failure the next record is taken");
    }
}
