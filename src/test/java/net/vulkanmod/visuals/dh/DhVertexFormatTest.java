package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UINT;

/**
 * DH's 16-byte LOD vertex, built here by DH's encoding rule (LodQuadBuilder.putVertex: three shorts of position, a meta short
 * with sky light in bits 0-3, block light in bits 4-7 and the micro offset in bits 8-13 as 2-bit x, y, z codes, 01 = +, 11 = -;
 * then r, g, b, a, the material, the normal and the tile short), in the byte order DH's buffers have on x86 (native, little
 * endian). The expected values are worked out by hand from that rule.
 */
class DhVertexFormatTest {
    /** One vertex as DH writes it. */
    private static void put(ByteBuffer bb, int x, int y, int z, int sky, int block, int mx, int my, int mz, int rgba) {
        bb.putShort((short) x).putShort((short) y).putShort((short) z);
        int micro = 0;
        if (mx != 0) micro |= mx > 0 ? 0b01 : 0b11;
        if (my != 0) micro |= my > 0 ? 0b0100 : 0b1100;
        if (mz != 0) micro |= mz > 0 ? 0b010000 : 0b110000;
        bb.putShort((short) ((sky % 16) | ((block % 16) << 4) | (micro << 8)));
        bb.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8)).put((byte) rgba);
        bb.put((byte) 0).put((byte) 1).putShort((short) 0);
    }

    private static ByteBuffer threeVertices() {
        ByteBuffer bb = ByteBuffer.allocate(3 * DhVertexFormat.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        put(bb, 0, 0, 0, 15, 0, 0, 0, 0, 0x336699FF);//the container's corner
        put(bb, 65535, 65535, 65535, 0, 15, 0, 0, 0, 0xFFFFFFFF);//the largest u16 (shorts -1 as DH writes them)
        put(bb, 16, 70, 32, 7, 9, 1, 1, -1, 0x00000000);//micro offset +x, +y (not applied, as DH), -z
        return bb.flip();
    }

    @Test
    void theCornerVertexDecodesToZero() {
        ByteBuffer bb = threeVertices();
        assertArrayEquals(new float[]{0, 0, 0}, DhVertexFormat.decodePosition(bb, 0));
        int meta = DhVertexFormat.meta(bb, 0);
        assertEquals(0x000F, meta);
        assertEquals(15, DhVertexFormat.skyLight(meta));
        assertEquals(0, DhVertexFormat.blockLight(meta));
    }

    @Test
    void positionsAreUnsigned() {
        ByteBuffer bb = threeVertices();
        assertArrayEquals(new float[]{65535, 65535, 65535}, DhVertexFormat.decodePosition(bb, 1));
        int meta = DhVertexFormat.meta(bb, 1);
        assertEquals(0x00F0, meta);
        assertEquals(0, DhVertexFormat.skyLight(meta));
        assertEquals(15, DhVertexFormat.blockLight(meta));
    }

    /** micro = 0b11_01_01 = 0x35 in bits 8-13: x +0.01, y left as is (DH's GL shader skips it), z -0.01. */
    @Test
    void theMicroOffsetMovesXAndZByAHundredthOfABlock() {
        ByteBuffer bb = threeVertices();
        int meta = DhVertexFormat.meta(bb, 2);
        assertEquals(0x3597, meta);
        assertEquals(7, DhVertexFormat.skyLight(meta));
        assertEquals(9, DhVertexFormat.blockLight(meta));
        float[] p = DhVertexFormat.decodePosition(bb, 2);
        assertEquals(16.01f, p[0], 1e-5f);
        assertEquals(70f, p[1], 0f);
        assertEquals(31.99f, p[2], 1e-5f);
    }

    /** The position decode reads from the vertex, not from the buffer's position. */
    @Test
    void decodingLeavesTheBufferAsGiven() {
        ByteBuffer bb = threeVertices();
        DhVertexFormat.decodePosition(bb, 2);
        assertEquals(0, bb.position());
        assertEquals(3 * DhVertexFormat.BYTES, bb.limit());
    }

    /** The pipeline reads the same bytes: the u16 position and meta at 0, the unorm colour at 8, a 16-byte stride. */
    @Test
    void theVertexLayoutMatchesTheBytes() {
        assertEquals(DhVertexBuffer.BYTES_PER_VERTEX, DhVertexFormat.BYTES);
        assertEquals(16, DhVertexFormat.LAYOUT.stride());
        assertArrayEquals(new int[]{0, 1, 2}, DhVertexFormat.LAYOUT.locations());
        assertArrayEquals(new int[]{VK_FORMAT_R16G16B16A16_UINT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8_UINT}, DhVertexFormat.LAYOUT.formats());
        assertArrayEquals(new int[]{DhVertexFormat.POSITION_OFFSET, DhVertexFormat.COLOUR_OFFSET, DhVertexFormat.MATERIAL_OFFSET},
                DhVertexFormat.LAYOUT.offsets());
        assertEquals(12, DhVertexFormat.MATERIAL_OFFSET);
        assertEquals(DhVertexFormat.MATERIAL_OFFSET + 1, DhVertexFormat.NORMAL_OFFSET);
        assertEquals(0, DhVertexFormat.POSITION_OFFSET);
        assertEquals(6, DhVertexFormat.META_OFFSET);
        assertEquals(8, DhVertexFormat.COLOUR_OFFSET);
    }

    /** The shader decodes the same bits as the Java mirror. */
    @Test
    void theShaderUsesTheSameBits() {
        String vert = ShaderFiles.read("dh/terrain.vert");
        assertTrue(vert.contains("layout(location = 0) in uvec4 vPosition;"), "position and meta as u16");
        assertTrue(vert.contains("layout(location = 1) in vec4 colour;"), "colour as unorm");
        assertTrue(vert.contains("uint lights = meta & 0xFFu;"), "sky and block light in the low byte");
        assertTrue(vert.contains("uint micro = (meta >> 8u) & 0x3Fu;"), "micro offset in bits 8-13");
        assertTrue(vert.contains("(micro & 1u) != 0u") && vert.contains("(micro & 2u) != 0u"), "x code");
        assertTrue(vert.contains("(micro & 16u) != 0u") && vert.contains("(micro & 32u) != 0u"), "z code");
        assertTrue(vert.contains("texelFetch(lightMap, ivec2(lights >> 4u, lights & 15u), 0)"), "block light along u, sky light along v");
    }
}
