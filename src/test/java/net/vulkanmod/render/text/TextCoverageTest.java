package net.vulkanmod.render.text;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-30: the text shaders' coverage curve (text_aa.glsl, mirrored by TextCoverage) and their wiring. */
class TextCoverageTest {
    private static final Path CORE = Path.of("src/main/resources/assets/vulkanmod/shaders/minecraft/core");
    private static final String[] SHADERS = {"rendertype_text", "rendertype_text_see_through", "rendertype_text_intensity",
            "rendertype_text_intensity_see_through"};

    /** Magnified text is not reweighted: its edges come from sharp-bilinear sampling (sharpCoord), not from the coverage. */
    @Test
    void magnifiedCoverageIsIdentity() {
        for (float f : new float[]{0.25f, 0.5f, 0.99f}) {
            for (float a : new float[]{0, 0.1f, 0.5f, 1}) {
                assertEquals(a, TextCoverage.coverage(a, f), 1e-6f);
            }
        }
    }

    /**
     * Sharp bilinear (magnified glyphs, m screen pixels per texel): a texel's inside samples its own centre, only the last pixel before
     * the next texel blends. Linear magnification had made 1-texel strokes a tent (pale unifont hangul in the GUI).
     */
    @Test
    void sharpBilinearKeepsTexelsFlat() {
        float m = 3f;
        assertEquals(4.5f, TextCoverage.sharpCoord(4.5f, m), 1e-5f, "a texel centre");
        assertEquals(4.5f, TextCoverage.sharpCoord(4.6f, m), 1e-5f, "inside the texel");
        assertEquals(4.5f, TextCoverage.sharpCoord(4.4f, m), 1e-5f);
        assertEquals(5.0f, TextCoverage.sharpCoord(5.0f, m), 1e-5f, "the boundary: halfway");
        assertEquals(5.5f, TextCoverage.sharpCoord(5.2f, m), 1e-5f, "past a third of a texel: the next centre");
        float prev = -1;
        for (float t = 4f; t <= 7f; t += 0.05f) {
            float c = TextCoverage.sharpCoord(t, m);
            assertTrue(c >= prev - 1e-6f, "monotonic at " + t);
            prev = c;
        }
        assertEquals(4.3f, TextCoverage.sharpCoord(4.3f, 1f), 1e-5f, "one pixel per texel: plain bilinear");
    }

    @Test
    void minifiedStrokesThicken() {
        assertEquals(1 - Math.pow(0.8, 3), TextCoverage.coverage(0.2f, 3f), 1e-4);
        float prev = -1;
        for (float a = 0; a <= 1.0001f; a += 0.05f) {
            float c = TextCoverage.coverage(a, 2.5f);
            assertTrue(c >= prev && c <= 1.0f + 1e-6f);
            prev = c;
        }
        prev = -1;
        for (float f = 1; f <= 3; f += 0.25f) {
            float c = TextCoverage.coverage(0.3f, f);
            assertTrue(c >= prev);
            prev = c;
        }
    }

    @Test
    void boostIsCapped() {
        assertEquals(TextCoverage.coverage(0.2f, TextCoverage.MAX_BOOST), TextCoverage.coverage(0.2f, 10f), 1e-6f);
    }

    @Test
    void shadersUseTheMirror() throws Exception {
        String inc = Files.readString(CORE.getParent().getParent().resolve("include/text_aa.glsl"));
        assertTrue(inc.contains("const float MAX_BOOST = 3.0;"), inc);
        assertTrue(inc.contains("const float DISCARD_ALPHA = 1.0/255.0;"), inc);
        assertEquals(3f, TextCoverage.MAX_BOOST);
        assertEquals(1f / 255f, TextCoverage.DISCARD_ALPHA);
        assertEquals(0.1f, TextCoverage.LEGACY_DISCARD);
        for (String s : SHADERS) {
            String fsh = Files.readString(CORE.resolve(s + "/" + s + ".fsh"));
            assertTrue(fsh.contains("#include \"text_aa.glsl\""), s);
            assertTrue(fsh.contains("TextAntialias"), s);
            String json = Files.readString(CORE.resolve(s + "/" + s + ".json"));
            assertTrue(json.contains("\"TextAntialias\""), s);
        }
    }

    /**
     * World text cuts its coverage at WORLD_CUTOUT: sign text draws before the board, and blended edge fragments wrote depth over
     * the wall behind it (light halos). GUI text (orthographic) blends.
     */
    @Test
    void worldTextIsCutGuiTextBlends() throws Exception {
        assertEquals(0.5f, TextCoverage.WORLD_CUTOUT);
        assertFalse(TextCoverage.perspective(0, 0, 0), "orthographic (GUI)");
        assertTrue(TextCoverage.perspective(0, 0, -1), "perspective (world)");
        assertTrue(TextCoverage.perspective(0.3f, 0, 0));
        String inc = Files.readString(CORE.getParent().getParent().resolve("include/text_aa.glsl"));
        assertTrue(inc.contains("const float WORLD_CUTOUT = 0.5;"), inc);
        for (String s : SHADERS) {
            String vsh = Files.readString(CORE.resolve(s + "/" + s + ".vsh"));
            assertTrue(vsh.contains("textPerspective = textIsPerspective(MVP);"), s);
            String fsh = Files.readString(CORE.resolve(s + "/" + s + ".fsh"));
            assertTrue(fsh.contains("textCoverageAt(") && fsh.contains("textSample(Sampler0"), s);
            assertTrue(fsh.contains("textPerspective"), s);
        }
    }

    /** Final review: every upload path (VTextureSelector, GlTexture's GL emulation, PBOs) reaches FontPages through VulkanImage. */
    @Test
    void everyUploadReachesTheFontPages() throws Exception {
        String image = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/texture/VulkanImage.java"));
        String selector = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/texture/VTextureSelector.java"));
        int at = image.indexOf("public void uploadSubTextureAsync(");
        int end = image.indexOf("\n    }", at);
        assertTrue(image.substring(at, end).contains("FontPages.afterUpload(this, mipLevel"), "VulkanImage.uploadSubTextureAsync");
        assertFalse(selector.contains("FontPages.afterUpload"), "not twice");
    }
}
