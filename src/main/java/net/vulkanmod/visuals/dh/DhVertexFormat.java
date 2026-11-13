package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.vk.VkGraphicsProgram;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UINT;

/**
 * DH's 16-byte LOD vertex, as DH's quad builder writes it (in native order, little endian on the machines the fork runs on):
 * <pre>
 * bytes 0-5   u16 x, y, z     block position relative to the container's minimum corner
 * bytes 6-7   u16 meta        bits 0-3 sky light, 4-7 block light, 8-13 micro offset (2 bits each for x, y, z: 01 +, 11 -)
 * bytes 8-11  u8 r, g, b, a   colour (alpha 255 in opaque buffers)
 * byte  12    u8 material     DH's block material id (shader packs read it)
 * byte  13    u8 normal       face index: down, up, north, south, west, east
 * bytes 14-15 u16 texture     DH's texture tile id (0: untextured)
 * </pre>
 * The Java side is a mirror of terrain.vert's decoding for the tests; the shader reads the position/meta and colour words, and the
 * material and normal bytes (for the LOD light attachment's face, 2026-10-19, and its water bit, 2026-11-13).
 * DH's own shader moves the corners along x and z by a hundredth of a block (and leaves y alone) to close the seams between quads.
 */
public final class DhVertexFormat {
    public static final int BYTES = 16;
    public static final int POSITION_OFFSET = 0, META_OFFSET = 6, COLOUR_OFFSET = 8, MATERIAL_OFFSET = 12, NORMAL_OFFSET = 13, TEXTURE_OFFSET = 14;
    /** The micro offset in blocks (DH's GL renderer sets it to 0.01). */
    public static final float MICRO_OFFSET = 0.01f;
    /** What terrain.vert reads: location 0 the u16 position and meta, location 1 the unorm colour, location 2 the material and normal bytes. */
    public static final VkGraphicsProgram.VertexLayout LAYOUT = new VkGraphicsProgram.VertexLayout(BYTES, new int[]{0, 1, 2},
            new int[]{VK_FORMAT_R16G16B16A16_UINT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8_UINT},
            new int[]{POSITION_OFFSET, COLOUR_OFFSET, MATERIAL_OFFSET});
    /** DH's face indices: down, up, north, south, west, east. */
    public static final int FACES = 6;
    /** DH's block material id for water (EDhApiBlockMaterial.WATER: liquids and water itself; ice, glass and slime are others). */
    public static final int MATERIAL_WATER = 12;
    /** The LOD light attachment's water bit, above the light byte and the face (index + 1 in bits 8-10). */
    public static final int LIGHT_WATER_BIT = 1 << 11;

    private DhVertexFormat() {
    }

    private static ByteBuffer le(ByteBuffer buf) {
        return buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int u16(ByteBuffer buf, int at) {
        return buf.getShort(at) & 0xFFFF;
    }

    /** Vertex number vertex of buf (counted from index 0, buf's position and order left as given): x, y, z in blocks. */
    public static float[] decodePosition(ByteBuffer buf, int vertex) {
        ByteBuffer b = le(buf);
        int base = vertex * BYTES;
        int micro = (u16(b, base + META_OFFSET) >> 8) & 0x3F;
        return new float[]{u16(b, base) + micro(micro & 0b11), u16(b, base + 2), u16(b, base + 4) + micro((micro >> 4) & 0b11)};
    }

    /** The meta word of a vertex. */
    public static int meta(ByteBuffer buf, int vertex) {
        return u16(le(buf), vertex * BYTES + META_OFFSET);
    }

    public static int skyLight(int meta) {
        return meta & 0xF;
    }

    public static int blockLight(int meta) {
        return (meta >> 4) & 0xF;
    }

    /**
     * The LOD light attachment's value for a vertex (terrain.vert: the meta's low byte): sky light in the low nibble, block light in
     * the high one, the visuals' packing (LightPacking) that the post-process relights LOD pixels from.
     */
    public static int lightAttachment(int meta) {
        return blockLight(meta) << 4 | skyLight(meta);
    }

    /**
     * 2026-10-19: the attachment's full value, the light in the low byte and DH's face index plus one in the high byte (0 when the
     * index is not a face). The lighting pass lights an LOD pixel by that face (PostMath.lodFaceNormal).
     */
    public static int lightAttachment(int meta, int normalIndex) {
        int face = normalIndex >= 0 && normalIndex < FACES ? normalIndex + 1 : 0;
        return lightAttachment(meta) | face << 8;
    }

    /**
     * 2026-11-13 (session 17: far ice in DH's range was drawn as water): as above, and LIGHT_WATER_BIT where the material is DH's WATER
     * (MATERIAL_WATER), the bit the lighting pass tells LOD water from LOD ice, glass and slime by (terrain.vert's vertexLight).
     */
    public static int lightAttachment(int meta, int normalIndex, int material) {
        return lightAttachment(meta, normalIndex) | (material == MATERIAL_WATER ? LIGHT_WATER_BIT : 0);
    }

    /** One axis's 2-bit code: bit 0 moves the corner, bit 1 makes it negative. */
    static float micro(int code) {
        if ((code & 1) == 0) return 0;
        return (code & 2) != 0 ? -MICRO_OFFSET : MICRO_OFFSET;
    }
}
