package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's glGetTexImage hands back the Vulkan image bytes as they are (R,G,B,A) whatever format/type was asked
 * for. Xaero reads branch textures back as GL_BGRA + GL_UNSIGNED_INT_8_8_8_8, whose bytes on little-endian are
 * A,R,G,B, so an opaque black tile came back as alpha 0 / blue 255 and showed as a solid blue square.
 */
class ReadbackFormatTest {
    private static final int RGBA = 6408, BGRA = 32993;
    private static final int UBYTE = 5121, UINT_8888 = 32821, UINT_8888_REV = 33639;

    private static byte[] convert(byte[] rgbaPixels, boolean sourceIsBgra, int format, int type) {
        ByteBuffer src = ByteBuffer.allocateDirect(rgbaPixels.length).put(rgbaPixels).flip();
        ByteBuffer dst = ByteBuffer.allocateDirect(rgbaPixels.length);
        assertTrue(ReadbackFormat.convert(src, sourceIsBgra, dst, format, type));
        byte[] out = new byte[rgbaPixels.length];
        dst.get(0, out);
        return out;
    }

    private static byte[] px(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void bgraUnsignedInt8888IsArgbBytesOnLittleEndian() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
        // opaque black, then r=1 g=2 b=3 a=4
        assertArrayEquals(px(255, 0, 0, 0, 4, 1, 2, 3), convert(px(0, 0, 0, 255, 1, 2, 3, 4), false, BGRA, UINT_8888));
        int argbInt = ByteBuffer.wrap(convert(px(1, 2, 3, 4), false, BGRA, UINT_8888)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        assertTrue(argbInt == ((3 << 24) | (2 << 16) | (1 << 8) | 4), "packed as B<<24|G<<16|R<<8|A");
    }

    @Test
    void byteOrderedFormats() {
        assertArrayEquals(px(1, 2, 3, 4), convert(px(1, 2, 3, 4), false, RGBA, UBYTE));
        assertArrayEquals(px(3, 2, 1, 4), convert(px(1, 2, 3, 4), false, BGRA, UBYTE));
        assertArrayEquals(px(3, 2, 1, 4), convert(px(1, 2, 3, 4), false, BGRA, UINT_8888_REV));
        assertArrayEquals(px(1, 2, 3, 4), convert(px(1, 2, 3, 4), false, RGBA, UINT_8888_REV));
        assertArrayEquals(px(4, 3, 2, 1), convert(px(1, 2, 3, 4), false, RGBA, UINT_8888));
    }

    @Test
    void bgraSourceImagesAreReorderedToo() {
        // a B8G8R8A8 image (swapchain-like) stores b,g,r,a
        assertArrayEquals(px(1, 2, 3, 4), convert(px(3, 2, 1, 4), true, RGBA, UBYTE));
        assertArrayEquals(px(3, 2, 1, 4), convert(px(3, 2, 1, 4), true, BGRA, UBYTE));
    }

    @Test
    void unsupportedCombinationIsRejected() {
        ByteBuffer src = ByteBuffer.allocateDirect(4);
        assertFalse(ReadbackFormat.convert(src, false, ByteBuffer.allocateDirect(4), 6407 /* GL_RGB */, UBYTE));
        assertFalse(ReadbackFormat.isSupported(6407, UBYTE));
        assertTrue(ReadbackFormat.isSupported(BGRA, UINT_8888));
    }
}
