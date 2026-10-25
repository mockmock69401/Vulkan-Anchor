package net.vulkanmod.visuals.render;

import java.nio.ByteBuffer;

/** P7l design 3.1: a section's two DataLayer nibble arrays (2048 bytes each) as one byte per block: sky in the low nibble, block in the high. */
public final class LightPacking {
    /** A missing sky layer (no data for the section): column (x, z)'s sky light, the same at every height in it. */
    public interface Column {
        int light(int x, int z);
    }

    private LightPacking() {
    }

    /** sky or block null: no data (block light 0). out gets SECTION_BYTES bytes at 0, DataLayer order. */
    public static void pack(byte[] sky, byte[] block, Column skyColumn, ByteBuffer out) {
        for (int i = 0; i < LightVolumeMath.SECTION_BYTES; i++) {
            int s = sky != null ? nibble(sky, i) : skyColumn.light(i & 15, (i >> 4) & 15) & 15;
            int b = block != null ? nibble(block, i) : 0;
            out.put(i, (byte) (b << 4 | s));
        }
    }

    /** DataLayer's nibble: index y*256 + z*16 + x, the even index in the low half of its byte. */
    static int nibble(byte[] data, int index) {
        return data[index >> 1] >> ((index & 1) << 2) & 15;
    }
}
