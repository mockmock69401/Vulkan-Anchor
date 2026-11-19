package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-19: the far map's casters past the near maps' reach (ShadowMath.fillParams, lighting.comp's farFill). */
class FarFillTest {
    private static final Path SHADER = Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/lighting.comp");

    @Test
    void offWithoutTheVanillaCastersOrTheFarMaps() {
        assertArrayEquals(new float[4], ShadowMath.fillParams(false, true, 4, 16));
        assertArrayEquals(new float[4], ShadowMath.fillParams(true, false, 4, 16));
        assertEquals(0, ShadowMath.fillWeight(new float[4], 500, 50));
    }

    @Test
    void theReachIsTheShadowDistanceLessTheMapsDrift() {
        float[] p = ShadowMath.fillParams(true, true, 4, 16);
        assertEquals(64 - ShadowMath.NEAR_MAX_MOVE, p[0]);
        //Never past the render distance (nearReach's own cap)
        assertEquals(ShadowMath.fillReach(ShadowMath.nearReach(10, 6)), ShadowMath.fillParams(true, true, 10, 6)[0]);
        //The far map's texel (1.5 blocks) sets the least blocker height
        assertEquals(ShadowMath.FILL_ABOVE * 1.5f, p[2], 1e-6f);
    }

    @Test
    void aBlockerInReachOrOnItsOwnGroundDoesNotCount() {
        float[] p = ShadowMath.fillParams(true, true, 4, 16);
        assertEquals(0, ShadowMath.fillWeight(p, p[0] - p[1] - 1, 20), "inside the near maps' reach: they hold it");
        assertEquals(1, ShadowMath.fillWeight(p, p[0] + 1, 20), 1e-6f, "past it: the far map's shadow");
        float half = ShadowMath.fillWeight(p, p[0] - p[1] / 2, 20);
        assertTrue(half > 0 && half < 1, "faded in across the band");
        assertEquals(0, ShadowMath.fillWeight(p, 100, p[2] - 0.1f), "the far map's own coarse ground");
        assertEquals(1, ShadowMath.fillWeight(p, 100, p[2] + p[3] + 0.1f), 1e-6f);
    }

    @Test
    void theShaderMirrorsIt() throws IOException {
        String s = Files.readString(SHADER);
        assertTrue(s.contains("layout(location = " + ShadowMath.FILL_SLOT + ") uniform vec4 fillParams;"));
        assertTrue(s.contains("smoothstep(fillParams.x - fillParams.y, fillParams.x, reach)*smoothstep(fillParams.z, fillParams.z + fillParams.w, above)"));
        //The close and near cascades keep the darker of theirs and the far casters past their reach
        assertEquals(2, s.split("v = min\\(v, fill\\);", -1).length - 1);
    }
}
