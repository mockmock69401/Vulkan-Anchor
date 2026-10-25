package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge cover's program reads EdgeCover's quads from a storage buffer (three vec4 a quad, six vertices each), lit like vanilla
 * terrain; from inside the built section it gives way to the hole's LODs, when there are LODs. The quads go into host buffers of
 * their own, never the shared upload stream (overflowing it stopped the render system).
 */
class EdgeCoverWiringTest {
    private static byte[] bytes(String path) throws Exception {
        try (InputStream in = EdgeCoverWiringTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return in.readAllBytes();
        }
    }

    private static String read(String path) throws Exception {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    @Test
    void aQuadIsThreeVec4() {
        assertEquals(12, EdgeCover.FLOATS_PER_QUAD);
        assertEquals("edgeCover", VkEdgeCover.PROGRAM);
        assertEquals(2, VkEdgeCover.QUADS);
    }

    @Test
    void theShaderBuildsSixVerticesAQuadAndRepeatsTheTexture() throws Exception {
        String vert = read("assets/vulkanmod/visuals/shaders/post/edge_cover.vert");
        assertTrue(vert.contains("layout(binding = 2, std430) readonly restrict buffer Quads { vec4 quads[]; };"));
        assertTrue(vert.contains("vec4 a = quads[3*gl_InstanceID], b = quads[3*gl_InstanceID + 1], c = quads[3*gl_InstanceID + 2];"));
        String frag = read("assets/vulkanmod/visuals/shaders/post/edge_cover.frag");
        //The x-ray shows from inside too, through the built section's water and caves toward the hole; from inside, where the
        // hole has LODs (the LOD depth is not clear) they show instead of the cover
        assertTrue(frag.contains("bool inside = dot(SIDES[side], rel) >= 0.0;"));
        assertTrue(frag.contains("if (inside && compareLod > 0.5 && texture(lodDepthTex, clip.xy/clip.w*0.5 + 0.5).r < 1.0) {"), "the hole's LODs win from inside");
        assertTrue(frag.contains("layout(binding = 3) uniform sampler2D lodDepthTex;"));
        assertEquals(3, VkEdgeCover.LOD_DEPTH);
        assertTrue(frag.contains("mix(sprite.xy, sprite.zw, fract(local))"), "the sprite repeats per block");
        assertTrue(frag.contains("texture(lightmapTex, (vec2(light.y, light.x) + 0.5)/16.0)"), "block, sky like scene_copy's lightmapLinear");
    }

    /** No LOD depth: the dedicated read-only stand-in fills the binding and the compare flag is 0; with one, its view and flag 1. */
    @Test
    void noLodDepthDrawsWithoutCompare() throws Exception {
        long standIn = 111, lod = 222;
        VkEdgeCover.DepthBinding none = VkEdgeCover.depthBinding(false, lod, standIn);
        assertEquals(standIn, none.view(), "the stand-in, never the main depth, is bound without LODs");
        assertEquals(0f, none.compare(), "no compare without LODs");
        VkEdgeCover.DepthBinding with = VkEdgeCover.depthBinding(true, lod, standIn);
        assertEquals(lod, with.view());
        assertEquals(1f, with.compare());
        assertEquals(0f, VkEdgeCover.compareFlag(null), "no LOD depth: no compare");
        String vert = read("assets/vulkanmod/visuals/shaders/post/edge_cover.vert");
        assertTrue(vert.contains("layout(location = 1) uniform vec4 cameraFrac;"));
        assertTrue(vert.contains("compareLod = cameraFrac.w;"), "the flag travels in the camera uniform's w");
        String cls = new String(bytes("net/vulkanmod/visuals/render/VkEdgeCover.class"), StandardCharsets.ISO_8859_1);
        assertTrue(!cls.contains("captureMainTarget") && !cls.contains("createMainDepthView"), "the main depth (an attachment in this pass) is never bound");
    }

    @Test
    void theQuadsNeverGoThroughTheUploadStream() throws Exception {
        String src = new String(bytes("net/vulkanmod/visuals/render/VkEdgeCover.class"), StandardCharsets.ISO_8859_1);
        assertTrue(!src.contains("VkUploadStream"), "a host buffer per frame slot instead");
    }
}
