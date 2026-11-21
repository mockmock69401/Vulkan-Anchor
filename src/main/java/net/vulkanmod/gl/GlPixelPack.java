package net.vulkanmod.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteOrder;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Byte order of 8-bit RGBA readbacks (glGetTexImage): a Vulkan image's bytes are reordered into the GL format and type the caller
 * asked for. Xaero's World Map reads its zoomed-out map tiles back as GL_BGRA / GL_UNSIGNED_INT_8_8_8_8 into its disk cache; the
 * image's own R,G,B,A bytes came back there as A,R,G,B, so a tile loaded from that cache showed its colours shifted by one
 * channel (olive seas, pure blue where the tile was empty).
 */
public final class GlPixelPack {
    private static final int R = 0, G = 1, B = 2, A = 3;

    private GlPixelPack() {
    }

    /**
     * For each destination byte of a pixel, the source byte of the image's pixel it takes; null when the bytes are already in the
     * requested order or the conversion is not one of the 8-bit RGBA/BGRA ones (the bytes are then left as the image holds them).
     */
    public static int[] permutation(int vkFormat, int glFormat, int type, boolean swapBytes, ByteOrder order) {
        int[] image = switch (vkFormat) {
            case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB -> new int[]{R, G, B, A};
            case VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB -> new int[]{B, G, R, A};
            default -> null;
        };
        int[] components = switch (glFormat) {
            case GL11.GL_RGBA -> new int[]{R, G, B, A};
            case GL12.GL_BGRA -> new int[]{B, G, R, A};
            default -> null;
        };
        if (image == null || components == null) return null;

        //Components in memory order: bytes as listed for UNSIGNED_BYTE; packed types hold the first component in the high byte
        //(8_8_8_8) or the low byte (_REV) of a machine word
        boolean reversed;
        switch (type) {
            case GL11.GL_UNSIGNED_BYTE -> reversed = false;
            case GL12.GL_UNSIGNED_INT_8_8_8_8 -> reversed = (order == ByteOrder.LITTLE_ENDIAN) != swapBytes;
            case GL12.GL_UNSIGNED_INT_8_8_8_8_REV -> reversed = (order == ByteOrder.BIG_ENDIAN) != swapBytes;
            default -> {
                return null;
            }
        }

        int[] permutation = new int[4];
        boolean identity = true;
        for (int i = 0; i < 4; i++) {
            int component = components[reversed ? 3 - i : i];
            int source = indexOf(image, component);
            permutation[i] = source;
            identity &= source == i;
        }
        return identity ? null : permutation;
    }

    /** Reorders the bytes of each 4-byte pixel of rows written at {@code ptr} with {@code rowStride} bytes between rows. */
    public static void apply(long ptr, int width, int height, long rowStride, int[] permutation) {
        byte[] pixel = new byte[4];
        for (int y = 0; y < height; y++) {
            long row = ptr + y * rowStride;
            for (int x = 0; x < width; x++) {
                long p = row + x * 4L;
                for (int i = 0; i < 4; i++) pixel[i] = MemoryUtil.memGetByte(p + i);
                for (int i = 0; i < 4; i++) MemoryUtil.memPutByte(p + i, pixel[permutation[i]]);
            }
        }
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) if (array[i] == value) return i;
        throw new IllegalArgumentException();
    }
}
