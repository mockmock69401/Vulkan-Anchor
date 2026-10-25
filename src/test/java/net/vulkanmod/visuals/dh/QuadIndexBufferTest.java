package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuadIndexBufferTest {
    @Test
    void sixIndicesPerQuad() {
        assertEquals(6, QuadIndexBuffer.indexCount(1));
        assertEquals(0, QuadIndexBuffer.indexCount(0));
        assertEquals(6 * 32768, QuadIndexBuffer.indexCount(32768));
    }

    @Test
    void twoTrianglesPerQuadOverItsFourVertices() {
        IntBuffer out = IntBuffer.allocate(QuadIndexBuffer.indexCount(3));
        QuadIndexBuffer.fill(out, 3);
        int[] first = new int[12];
        out.flip().get(first);
        assertArrayEquals(new int[]{0, 1, 2, 2, 3, 0, 4, 5, 6, 6, 7, 4}, first);
        assertEquals(10, out.get(15));//third quad: 8,9,10,10,11,8
        assertEquals(11, out.get(16));
        assertEquals(8, out.get(17));
    }

    @Test
    void indicesPastSixteenBits() {
        IntBuffer out = IntBuffer.allocate(QuadIndexBuffer.indexCount(20000));
        QuadIndexBuffer.fill(out, 20000);
        assertEquals(19999 * 4 + 3, out.get(QuadIndexBuffer.indexCount(20000) - 2));
    }

    @Test
    void capacityDoublesPastTheLargestQuadCount() {
        assertEquals(QuadIndexBuffer.INITIAL_QUADS, QuadIndexBuffer.grownCapacity(0, 1));
        assertEquals(QuadIndexBuffer.INITIAL_QUADS * 2, QuadIndexBuffer.grownCapacity(QuadIndexBuffer.INITIAL_QUADS, QuadIndexBuffer.INITIAL_QUADS + 1));
        assertEquals(32768, QuadIndexBuffer.grownCapacity(4096, 20000));
        assertEquals(4096, QuadIndexBuffer.grownCapacity(4096, 4096));
    }

    @Test
    void ensureGrowsByDoublingAndRetiresTheOldBuffer() {
        FakeGpu gpu = new FakeGpu();
        QuadIndexBuffer indices = new QuadIndexBuffer(gpu);
        assertEquals(0, indices.handle());
        assertTrue(indices.ensure(10));
        assertEquals(QuadIndexBuffer.INITIAL_QUADS, indices.capacityQuads());
        assertEquals(QuadIndexBuffer.INITIAL_QUADS * 6L * 4, gpu.allocated.get(0).size());
        assertFalse(indices.ensure(QuadIndexBuffer.INITIAL_QUADS));
        assertEquals(1, gpu.allocated.size());

        assertTrue(indices.ensure(QuadIndexBuffer.INITIAL_QUADS + 1));
        assertEquals(QuadIndexBuffer.INITIAL_QUADS * 2, indices.capacityQuads());
        assertEquals(2, gpu.allocated.size());
        assertEquals(gpu.allocated.get(0), gpu.retired.get(0));
        assertEquals(gpu.allocated.get(1).handle(), indices.handle());

        //The uploaded indices are the u32 pattern for the whole capacity, little-endian
        FakeGpu.Upload last = gpu.uploads.get(1);
        assertEquals(QuadIndexBuffer.INITIAL_QUADS * 2L * 6 * 4, last.bytes());
        IntBuffer uploaded = ByteBuffer.wrap(last.data()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        assertEquals(4, uploaded.get(6));
        assertEquals(4, uploaded.get(11));

        indices.free();
        assertEquals(0, indices.handle());
        assertEquals(gpu.allocated.get(1), gpu.retired.get(1));
        indices.free();
        assertEquals(2, gpu.retired.size());
    }
}
