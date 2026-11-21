package net.vulkanmod.gl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.vulkan.VK10.*;

/** 2026-11-21: glGetTexImage hands back the bytes in the format and type asked for (Xaero's World Map cache of zoomed-out tiles). */
class GlPixelPackTest {
    private static final byte[] RGBA = {10, 20, 30, 40};

    private static byte[] read(int vkFormat, byte[] imageBytes, int format, int type, ByteOrder order) {
        int[] permutation = GlPixelPack.permutation(vkFormat, format, type, false, order);
        ByteBuffer buffer = MemoryUtil.memAlloc(8);
        try {
            buffer.put(0, imageBytes).put(4, imageBytes);
            if (permutation != null) GlPixelPack.apply(MemoryUtil.memAddress(buffer), 2, 1, 8, permutation);
            byte[] out = new byte[4];
            buffer.get(4, out);
            return out;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Test
    void xaeroReadsItsTilesAsBgraPackedHighFirst() {
        //GL_BGRA + GL_UNSIGNED_INT_8_8_8_8: the word is B<<24 | G<<16 | R<<8 | A, so a little-endian machine stores A, R, G, B
        assertArrayEquals(new byte[]{40, 10, 20, 30},
                read(VK_FORMAT_R8G8B8A8_UNORM, RGBA, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8, ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void otherOrders() {
        assertArrayEquals(new byte[]{30, 20, 10, 40},
                read(VK_FORMAT_R8G8B8A8_UNORM, RGBA, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, ByteOrder.LITTLE_ENDIAN));
        assertArrayEquals(new byte[]{30, 20, 10, 40},
                read(VK_FORMAT_R8G8B8A8_UNORM, RGBA, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, ByteOrder.LITTLE_ENDIAN));
        assertArrayEquals(new byte[]{40, 30, 20, 10},
                read(VK_FORMAT_R8G8B8A8_UNORM, RGBA, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8, ByteOrder.LITTLE_ENDIAN));
        assertArrayEquals(new byte[]{30, 20, 10, 40},
                read(VK_FORMAT_R8G8B8A8_UNORM, RGBA, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8, ByteOrder.BIG_ENDIAN));
        //A BGRA image read as RGBA bytes
        assertArrayEquals(new byte[]{30, 20, 10, 40},
                read(VK_FORMAT_B8G8R8A8_UNORM, RGBA, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ByteOrder.LITTLE_ENDIAN));
    }

    @Test
    void matchingOrdersAndOtherFormatsAreLeftAlone() {
        assertNull(GlPixelPack.permutation(VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, false, ByteOrder.LITTLE_ENDIAN));
        assertNull(GlPixelPack.permutation(VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, false, ByteOrder.LITTLE_ENDIAN));
        assertNull(GlPixelPack.permutation(VK_FORMAT_B8G8R8A8_UNORM, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, false, ByteOrder.LITTLE_ENDIAN));
        assertNull(GlPixelPack.permutation(VK_FORMAT_R16G16B16A16_SFLOAT, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, false, ByteOrder.LITTLE_ENDIAN));
        assertNull(GlPixelPack.permutation(VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, false, ByteOrder.LITTLE_ENDIAN));
    }
}
