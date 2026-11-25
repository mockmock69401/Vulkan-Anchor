package net.vulkanmod.render.chunk.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BakedLightTest {
    private static int pack(int block, int sky) {
        return block << 4 | (sky << 4) << 16;
    }

    @Test
    void noBakedLightKeepsTheWorldLight() {
        assertEquals(pack(3, 15), BakedLight.merge(pack(3, 15), 0));
    }

    @Test
    void bakedBlockLightBrightensADarkVertex() {
        //"forge_data": {"block_light": 12} on a vertex in the dark: block 12, the world's sky kept
        assertEquals(pack(12, 2), BakedLight.merge(pack(0, 2), pack(12, 0)));
    }

    @Test
    void theWorldLightWinsWhereItIsBrighter() {
        assertEquals(pack(15, 15), BakedLight.merge(pack(15, 15), pack(12, 0)));
    }

    @Test
    void eachChannelTakesItsOwnMaximum() {
        assertEquals(pack(12, 14), BakedLight.merge(pack(4, 14), pack(12, 6)));
    }
}
