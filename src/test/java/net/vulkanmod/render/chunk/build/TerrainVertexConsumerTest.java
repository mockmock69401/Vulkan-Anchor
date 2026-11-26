package net.vulkanmod.render.chunk.build;

import net.vulkanmod.render.model.quad.QuadUtils;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-26 (snow carpets in Sunlit): a quad from vanilla's block renderer is split along the diagonal its corners agree on. */
class TerrainVertexConsumerTest {
    private static int grey(float v) {
        return ColorUtil.RGBA.pack(v, v, v, 1f);
    }

    private static int start(float[] ao, int[] sky) {
        int[] colour = new int[4], light = new int[4];
        for (int i = 0; i < 4; i++) {
            colour[i] = grey(ao[i]);
            light[i] = sky[i] << 20;
        }
        return TerrainVertexConsumer.startIndex(colour, light, new float[4], new int[4]);
    }

    @Test
    void theDarkCornerDecidesTheDiagonal() {
        //Corner 1 in a corner's shade: the split runs along the bright corners 0 and 2, as BlockRenderer.putQuadData does
        assertEquals(QuadUtils.getIterationStartIdx(new float[]{1, 0.6f, 1, 1}, new int[4]), start(new float[]{1, 0.6f, 1, 1}, new int[]{15, 15, 15, 15}));
        assertEquals(QuadUtils.DEFAULT_START_IDX, start(new float[]{1, 0.6f, 1, 1}, new int[]{15, 15, 15, 15}));
        //Corner 0 dark: the split turns to run along corners 1 and 3
        assertEquals(QuadUtils.FLIPPED_START_IDX, start(new float[]{0.6f, 1, 1, 1}, new int[]{15, 15, 15, 15}));
        //Even shade: the light decides (corner 2 darker in the sky light: corners 0 and 2 sum lower, the default split)
        assertEquals(QuadUtils.DEFAULT_START_IDX, start(new float[]{1, 1, 1, 1}, new int[]{15, 15, 11, 15}));
        assertEquals(QuadUtils.FLIPPED_START_IDX, start(new float[]{1, 1, 1, 1}, new int[]{15, 11, 15, 15}));
    }
}
