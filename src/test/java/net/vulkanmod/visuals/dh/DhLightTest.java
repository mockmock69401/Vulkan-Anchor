package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.lod.LodTarget;
import net.vulkanmod.visuals.render.LightPacking;
import net.vulkanmod.visuals.render.LightVolumeMath;
import net.vulkanmod.visuals.render.VkPostProcess;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LOD light attachment holds, per LOD pixel, the light the post-process relights it from: one byte, sky light in the low
 * nibble and block light in the high one, the rule the visuals pack vanilla's light volume by (LightPacking). DH's vertex meta
 * carries sky light in bits 0-3 and block light in bits 4-7, so the attachment value is the meta's low byte.
 */
class DhLightTest {
    /** DH's meta word for a vertex: sky (bits 0-3), block (bits 4-7), micro offset (bits 8-13). */
    private static int meta(int sky, int block, int micro) {
        return sky | block << 4 | micro << 8;
    }

    /** What LightPacking makes of one block with this sky and block light. */
    private static int packed(int sky, int block) {
        byte[] skyNibbles = new byte[LightVolumeMath.SECTION_BYTES / 2];
        byte[] blockNibbles = new byte[LightVolumeMath.SECTION_BYTES / 2];
        skyNibbles[0] = (byte) sky;//index 0: the low half of byte 0
        blockNibbles[0] = (byte) block;
        ByteBuffer out = ByteBuffer.allocate(LightVolumeMath.SECTION_BYTES);
        LightPacking.pack(skyNibbles, blockNibbles, (x, z) -> 0, out);
        return out.get(0) & 0xFF;
    }

    @Test
    void theAttachmentValueIsTheVisualsPacking() {
        int[][] pairs = {{15, 0}, {7, 9}, {0, 14}};
        for (int[] p : pairs) {
            int m = meta(p[0], p[1], 0b00_11_01_01);
            assertEquals(p[0], DhVertexFormat.skyLight(m));
            assertEquals(p[1], DhVertexFormat.blockLight(m));
            assertEquals(packed(p[0], p[1]), DhVertexFormat.lightAttachment(m), "sky " + p[0] + ", block " + p[1]);
        }
        //Hand-worked: sky 7, block 9 -> 0x97
        assertEquals(0x97, DhVertexFormat.lightAttachment(meta(7, 9, 0b111111)));
    }

    /** The attachment the shader writes into is the format the post-process reads the LOD light in. */
    @Test
    void theAttachmentHasThePostProcessFormat() {
        assertEquals(VkPostProcess.LOD_LIGHT_FORMAT, LodTarget.LIGHT_FORMAT);
    }

    /**
     * 2026-10-19 (Sun Shading flicker on far LODs): the attachment's high byte is DH's face index plus one (0: no normal), so the
     * post-process can light an LOD pixel by its face instead of a normal rebuilt from the depth of neighbouring pixels.
     */
    @Test
    void theHighByteIsTheFaceIndexPlusOne() {
        int m = meta(7, 9, 0);
        assertEquals(0x97 | 1 << 8, DhVertexFormat.lightAttachment(m, 0));//down
        assertEquals(0x97 | 2 << 8, DhVertexFormat.lightAttachment(m, 1));//up
        assertEquals(0x97 | 6 << 8, DhVertexFormat.lightAttachment(m, 5));//east
        assertEquals(0x97, DhVertexFormat.lightAttachment(m, 6), "not a face: no normal");
        assertEquals(0x97, DhVertexFormat.lightAttachment(m, -1), "not a face: no normal");
    }

    /** terrain.vert passes on the meta's low byte and the face index plus one, the value lightAttachment computes. */
    @Test
    void theShaderWritesTheMetaLowByte() throws Exception {
        String vert = resource("assets/vulkanmod/visuals/shaders/dh/terrain.vert");
        String frag = resource("assets/vulkanmod/visuals/shaders/dh/terrain.frag");
        assertTrue(vert.contains("uint lights = meta & 0xFFu;"), "terrain.vert takes the low byte");
        assertTrue(vert.contains("layout(location = 2) in uvec2 vFace;"), "terrain.vert reads the material and normal bytes");
        assertTrue(vert.contains("vertexLight = lights | ((vFace.y < 6u ? vFace.y + 1u : 0u) << 8u);"), "and packs the face above the light");
        assertTrue(frag.contains("layout(location = 1) out uint fragLight;"), "terrain.frag has the integer light output");
        assertTrue(frag.contains("fragLight = vertexLight;"), "terrain.frag writes it unchanged");
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = DhLightTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
