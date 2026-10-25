package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3, spec 4.4: the LOD/vanilla edge treatment, chosen by comparing four in the same scenes: DH's far and vanilla fades drawn
 * in Vulkan, a composite distance rule, the visuals' edge cover, nothing. The choice:
 * the edge cover, with DH's own near discard as the boundary; DH's fade renderers stay empty whatever DH's fade setting says.
 */
class DhFadeChoiceTest {
    /** The decision table: every DH fade mode, with the edge cover setting on and off. */
    @Test
    void theEdgeCoverIsTheTreatmentAndDhsFadesAreNotDrawn() {
        for (EDhApiMcRenderingFadeMode mode : EDhApiMcRenderingFadeMode.values()) {
            assertEquals(new DhRenderDefinition.EdgeChoice(DhRenderDefinition.EdgeTreatment.EDGE_COVER, false, false),
                    DhRenderDefinition.edgeChoice(mode, true), mode.name());
            assertEquals(new DhRenderDefinition.EdgeChoice(DhRenderDefinition.EdgeTreatment.NONE, false, false),
                    DhRenderDefinition.edgeChoice(mode, false), mode.name());
        }
        assertEquals(4, DhRenderDefinition.EdgeTreatment.values().length, "the four compared");
    }

    /**
     * Why DH's vanilla fade has (next to) nothing to fade under the fork: DH blends vanilla terrain towards the LOD colour by
     * smoothstep(1.5 n, 1.9 n, distance), n = its automatic near clip + 16, the near clip being the vanilla distance times 0.2 (RD 2),
     * 0.3 (RD 4), 0.6 (RD 6), 0.8 (RD 10), 0.9 (beyond). Vanilla's farthest pixel is the corner of its square, RD * 16 * sqrt 2. The
     * largest weight the fade could put on any vanilla pixel, by RD (hand-worked, the comparison runs were at RD 12 and 32):
     */
    @Test
    void dhsVanillaFadeReachesAlmostNoVanillaTerrain() {
        //rd, DH's fade start, vanilla's farthest pixel, the largest fade weight there
        float[][] table = {
                {2, 33.6f, 45.25f, 1},
                {6, 110.4f, 135.76f, 0.948f},
                {7, 158.4f, 158.39f, 0},
                {8, 177.6f, 181.02f, 0.015f},
                {10, 216.0f, 226.27f, 0.084f},
                {12, 283.2f, 271.53f, 0},
                {16, 369.6f, 362.04f, 0},
                {23, 520.8f, 520.43f, 0},
                {32, 715.2f, 724.08f, 0.0063f}};
        for (float[] row : table) {
            int rd = (int) row[0];
            assertEquals(row[1], DhRenderDefinition.dhVanillaFadeStart(rd), 0.01f, "RD " + rd);
            assertEquals(row[2], DhRenderDefinition.vanillaFarthest(rd), 0.01f, "RD " + rd);
            assertEquals(row[3], DhRenderDefinition.dhVanillaFadeMaxWeight(rd), 0.001f, "RD " + rd);
        }
        for (int rd = 7; rd <= 32; rd++) {
            assertTrue(DhRenderDefinition.dhVanillaFadeMaxWeight(rd) < 0.09f, "RD " + rd + ": under 9 % at the square's very corner");
        }
        for (int rd = 11; rd <= 23; rd++) {
            assertEquals(0, DhRenderDefinition.dhVanillaFadeMaxWeight(rd), "RD " + rd + ": the band starts past vanilla terrain");
        }
        assertFalse(DhRenderDefinition.dhVanillaFadeMaxWeight(4) < 0.5f, "RD 2-6: DH's band would reach vanilla terrain");
    }

    /** DH's far fade starts at half its far clip, (LOD blocks + 512) * 2: past the LOD radius at every LOD distance. */
    @Test
    void dhsFarFadeStartsPastTheLodRadius() {
        for (int chunks : new int[]{64, 128, 256, 512, 1024}) {
            float far = (chunks * 16 + 512) * 2f;
            assertTrue(0.5f * far > chunks * 16, chunks + " chunks");
        }
    }

    /** The fade renderers DH calls are the empty ones. */
    @Test
    void theFadeRenderersStayEmpty() {
        DhRenderDefinition def = new DhRenderDefinition();
        assertSame(DhRenderDefinition.FAR_FADE, def.getFarFadeRenderer());
        assertSame(DhRenderDefinition.VANILLA_FADE, def.getVanillaFadeRenderer());
        def.getFarFadeRenderer().render(null);
        def.getVanillaFadeRenderer().render(null);
    }
}
