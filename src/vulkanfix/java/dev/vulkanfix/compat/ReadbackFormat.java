package dev.vulkanfix.compat;

import java.nio.ByteBuffer;

/**
 * Reorders 8-bit, 4-channel texels read back from a Vulkan image into the layout glGetTexImage was asked for.
 * Packed GL_UNSIGNED_INT_8_8_8_8(_REV) types are laid out for little-endian memory, which is what Minecraft runs on.
 */
public final class ReadbackFormat {
    private static final int GL_RGBA = 6408, GL_BGRA = 32993;
    private static final int GL_UNSIGNED_BYTE = 5121, GL_UNSIGNED_INT_8_8_8_8 = 32821, GL_UNSIGNED_INT_8_8_8_8_REV = 33639;
    private static final int R = 0, G = 1, B = 2, A = 3;

    private ReadbackFormat() {
    }

    public static boolean isSupported(int format, int type) {
        return order(format, type) != null;
    }

    /**
     * Writes {@code min(src.remaining(), dst.remaining())} bytes (whole texels) into {@code dst} from its position,
     * without moving either buffer's position. {@code sourceIsBgra} is true for B8G8R8A8 images.
     */
    public static boolean convert(ByteBuffer src, boolean sourceIsBgra, ByteBuffer dst, int format, int type) {
        int[] order = order(format, type);
        if (order == null) {
            return false;
        }
        int length = Math.min(src.remaining(), dst.remaining()) & ~3;
        int s = src.position();
        int d = dst.position();
        for (int i = 0; i < length; i += 4) {
            for (int c = 0; c < 4; c++) {
                dst.put(d + i + c, src.get(s + i + sourceOffset(order[c], sourceIsBgra)));
            }
        }
        return true;
    }

    /** Channels in output byte order. */
    private static int[] order(int format, int type) {
        if (format == GL_RGBA) {
            if (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8_REV) return new int[]{R, G, B, A};
            if (type == GL_UNSIGNED_INT_8_8_8_8) return new int[]{A, B, G, R};
        } else if (format == GL_BGRA) {
            if (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8_REV) return new int[]{B, G, R, A};
            if (type == GL_UNSIGNED_INT_8_8_8_8) return new int[]{A, R, G, B};
        }
        return null;
    }

    private static int sourceOffset(int channel, boolean sourceIsBgra) {
        if (!sourceIsBgra || channel == A || channel == G) {
            return channel;
        }
        return channel == R ? B : R;
    }
}
