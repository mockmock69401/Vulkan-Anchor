package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-25 (the user: "오로라 커튼 무늬 텍스처 미리 계산도 해줘"): the aurora's footprint noise from postAuroraMap's image
 * (AuroraMath.auroraFootprintMapped) draws the same curtains as evaluating it, and the composite alone reads the map.
 */
class AuroraMapTest {
    private static final float[] ONE = {1, 1, 1};

    @Test
    void theMapDrawsTheSameCurtains() {
        float[] params = {1, 731.3f, 0, 0};
        double sum = 0, diff = 0, worst = 0;
        int lit = 0, n = 0;
        for (int i = 0; i < 4000; i++) {
            //directions over the upper sky, denser near the horizon where most of it shows
            double az = i * 2.39996, el = Math.asin(0.02 + 0.98 * ((i * 0.618034) % 1.0) * ((i * 0.618034) % 1.0));
            float[] d = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            float[] a = AuroraMath.aurora(d, params, ONE, ONE, ONE, AuroraMath.AURORA_STEPS, 0.37f, false);
            float[] b = AuroraMath.aurora(d, params, ONE, ONE, ONE, AuroraMath.AURORA_STEPS, 0.37f, true);
            double la = a[0] + a[1] + a[2], lb = b[0] + b[1] + b[2];
            sum += la;
            diff += Math.abs(la - lb);
            if (la > 0.05) {
                worst = Math.max(worst, Math.abs(la - lb) / la);
                lit++;
            }
            n++;
        }
        System.out.println("aurora map: mean |difference| " + diff / sum + " of the light, worst lit direction " + worst + ", lit " + lit + " of " + n);
        assertTrue(lit > 200, "curtains drawn: " + lit);
        assertTrue(diff / sum < 0.01, "overall " + diff / sum);
        assertTrue(worst < 0.06, "worst " + worst);
    }

    @Test
    void theNoiseSplitKeepsTheFootprint() {
        for (int i = 0; i < 200; i++) {
            float qx = (i * 7.31f) % 60 - 30, qz = (i * 3.17f) % 60 - 30, t = i * 13.7f;
            float[] noise = AuroraMath.auroraFootprintNoise(qx, qz, t), f = AuroraMath.auroraFootprintFrom(noise, qx);
            assertTrue(Math.abs(noise[3]) <= 0.5f * AuroraMath.AURORA_WARP + 1e-4f, "the warp's shift stays small");
            assertEquals(f[0], AuroraMath.auroraFootprint(qx, qz, t)[0], 0f);
        }
        //the End and beyond the reach: evaluated
        float[] far = AuroraMath.auroraFootprintMapped(AuroraMath.AURORA_MAP_REACH + 1, 0, 5, false);
        assertEquals(AuroraMath.auroraFootprint(AuroraMath.AURORA_MAP_REACH + 1, 0, 5)[0], far[0], 0f);
        assertEquals(AuroraMath.auroraFootprint(3, 4, 5)[0], AuroraMath.auroraFootprintMapped(3, 4, 5, true)[0], 0f);
    }

    @Test
    void theCompositeReadsTheMapAndOthersDoNot() throws Exception {
        String aurora = ShaderFiles.read("post/aurora.glsl"), map = ShaderFiles.read("post/aurora_map.comp");
        String copy = ShaderFiles.read("post/scene_copy.comp"), reflection = ShaderFiles.read("post/reflection.comp");
        assertTrue(aurora.contains("#ifdef AURORA_FOOTPRINT_MAP\n        vec4 f = auroraFootprintAt(x.xz, t, end);") || aurora.replace("\r\n", "\n")
                .contains("#ifdef AURORA_FOOTPRINT_MAP\n        vec4 f = auroraFootprintAt(x.xz, t, end);"));
        assertTrue(map.contains("imageStore(dst, p, auroraFootprintNoise(q, params.x));"));
        assertTrue(copy.contains("#define AURORA_FOOTPRINT_MAP") && copy.contains("uniform sampler2D auroraFootprintMap;"));
        assertTrue(copy.indexOf("#define AURORA_FOOTPRINT_MAP") < copy.indexOf("#import <vulkanmod:visuals/post/aurora.glsl>"));
        assertTrue(!reflection.contains("AURORA_FOOTPRINT_MAP"), "the reflection evaluates it (its six samples)");
    }
}
