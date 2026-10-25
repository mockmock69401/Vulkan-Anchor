package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P7l design 3.1: DataLayer nibbles to one byte per block (sky low, block high). */
class LightPackingTest {
    private static byte[] layer(int index, int value) {
        byte[] d = new byte[2048];
        d[index >> 1] |= (byte) (value << ((index & 1) << 2));
        return d;
    }

    /** Review focus 2: DataLayer index y*256 + z*16 + x, even index in the low nibble. */
    @Test
    void dataLayerOrderAndNibbles() {
        int even = 3 * 256 + 5 * 16 + 2, odd = 3 * 256 + 5 * 16 + 7;
        byte[] sky = layer(even, 13);
        sky[odd >> 1] |= (byte) (9 << 4);
        byte[] block = layer(odd, 14);
        ByteBuffer out = ByteBuffer.allocate(4096);
        LightPacking.pack(sky, block, (x, z) -> 0, out);
        assertEquals(13, out.get(even) & 0xFF);
        assertEquals(9 | 14 << 4, out.get(odd) & 0xFF);
        assertEquals(0, out.get(0));
    }

    /** Review focus 4. */
    @Test
    void aMissingSkyLayerFillsEachColumn() {
        ByteBuffer out = ByteBuffer.allocate(4096);
        LightPacking.pack(null, null, (x, z) -> x == 4 && z == 9 ? 6 : 15, out);
        for (int y = 0; y < 16; y++) {
            assertEquals(6, out.get(y * 256 + 9 * 16 + 4) & 0xFF);
            assertEquals(15, out.get(y * 256 + 9 * 16 + 5) & 0xFF);
        }
    }
}
