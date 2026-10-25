package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static net.vulkanmod.visuals.dh.DhVertexBuffer.Plan.GROW;
import static net.vulkanmod.visuals.dh.DhVertexBuffer.Plan.NONE;
import static net.vulkanmod.visuals.dh.DhVertexBuffer.Plan.REUSE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhVertexBufferTest {
    @AfterEach
    void freeLeftovers() {
        DhVertexBuffer.closeAllNow();
        DhInternals.resetForTest();
    }

    static ByteBuffer vertices(int count) {
        ByteBuffer b = ByteBuffer.allocateDirect(count * DhVertexBuffer.BYTES_PER_VERTEX).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < b.capacity(); i++) b.put(i, (byte) i);
        return b;
    }

    @Test
    void planOfAnUpload() {
        assertEquals(NONE, DhVertexBuffer.plan(0, 0));
        assertEquals(NONE, DhVertexBuffer.plan(0, 4096));
        assertEquals(GROW, DhVertexBuffer.plan(64, 0));
        assertEquals(REUSE, DhVertexBuffer.plan(64, 64));
        assertEquals(REUSE, DhVertexBuffer.plan(64, 4096));
        assertEquals(GROW, DhVertexBuffer.plan(65, 64));
    }

    @Test
    void sixteenBytesPerVertex() {
        assertEquals(16, DhVertexBuffer.BYTES_PER_VERTEX);
        assertEquals(16L * 132, DhVertexBuffer.uploadBytes(132));
    }

    @Test
    void zeroVerticesMakeNoGpuBuffer() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadVertexBuffer(vertices(0), 0);
        assertEquals(0, gpu.allocated.size());
        assertEquals(0, gpu.uploads.size());
        assertEquals(0, vbo.getVertexCount());
        assertEquals(0, vbo.handle());
    }

    @Test
    void uploadSendsSixteenBytesTimesTheVertexCount() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        ByteBuffer data = vertices(132);
        vbo.uploadVertexBuffer(data, 132);
        assertEquals(1, gpu.allocated.size());
        assertEquals(132L * 16, gpu.allocated.get(0).size());
        assertEquals(132L * 16, gpu.uploads.get(0).bytes());
        assertEquals(5, gpu.uploads.get(0).data()[5]);
        assertEquals(132, vbo.getVertexCount());
        assertEquals(33, vbo.quadCount());
        assertEquals(gpu.allocated.get(0).handle(), vbo.handle());
        assertEquals(0, data.position(), "DH's buffer is left as it was given");
    }

    @Test
    void reuseKeepsTheBufferAndGrowRetiresIt() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadVertexBuffer(vertices(64), 64);
        vbo.uploadVertexBuffer(vertices(32), 32);
        assertEquals(1, gpu.allocated.size());
        assertEquals(32, vbo.getVertexCount());
        assertEquals(32L * 16, gpu.uploads.get(1).bytes());

        vbo.uploadVertexBuffer(vertices(128), 128);
        assertEquals(2, gpu.allocated.size());
        assertEquals(gpu.allocated.get(0), gpu.retired.get(0));
        assertEquals(gpu.allocated.get(1).handle(), vbo.handle());
    }

    @Test
    void aVertexCountPastTheBytesIsClampedToTheBytes() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadVertexBuffer(vertices(8), 12);
        assertEquals(8, vbo.getVertexCount());
        assertEquals(8L * 16, gpu.uploads.get(0).bytes());
    }

    @Test
    void aRefusedUploadDrawsNothing() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        gpu.refuse = true;
        vbo.uploadVertexBuffer(vertices(8), 8);
        assertEquals(0, vbo.getVertexCount());
    }

    @Test
    void indexUploadsAreIgnored() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadIndexBuffer(vertices(4), 4);
        vbo.uploadIndexBuffer(vertices(4), 4);
        assertEquals(0, gpu.uploads.size());
        assertTrue(DhVertexBuffer.indexUploadsIgnored() >= 2);
    }

    @Test
    void aFailedAllocationDrawsNothingAndDoesNotEscape() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadVertexBuffer(vertices(8), 8);
        long refusedBefore = DhVertexBuffer.refusedUploads();
        gpu.failAllocate = true;
        vbo.uploadVertexBuffer(vertices(16), 16);//GROW: the allocation throws
        assertEquals(0, vbo.getVertexCount());
        assertEquals(0, vbo.handle(), "the old buffer went to the deferred free, no new one");
        assertEquals(gpu.allocated.get(0), gpu.retired.get(0));
        assertEquals(refusedBefore + 1, DhVertexBuffer.refusedUploads());
        assertEquals(0, DhVertexBuffer.liveBytes());
        gpu.failAllocate = false;
        vbo.uploadVertexBuffer(vertices(16), 16);
        assertEquals(16, vbo.getVertexCount(), "a later upload works again");
    }

    @Test
    void aRefusedUploadIsCounted() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        long refusedBefore = DhVertexBuffer.refusedUploads();
        gpu.refuse = true;
        vbo.uploadVertexBuffer(vertices(8), 8);
        assertEquals(refusedBefore + 1, DhVertexBuffer.refusedUploads());
    }

    /** M5: an upload that throws (a staging write) switches DH drawing off with one warning; nothing is thrown into DH's task. */
    @Test
    void anUploadThatThrowsSwitchesDhOffAndDoesNotEscape() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        gpu.failUpload = true;
        try (LogCapture log = LogCapture.visuals()) {
            assertDoesNotThrow(() -> vbo.uploadVertexBuffer(vertices(8), 8));
            assertDoesNotThrow(() -> vbo.uploadVertexBuffer(vertices(8), 8));
            assertEquals(0, vbo.getVertexCount());
            assertFalse(DhInternals.ok());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().get(0).contains("vkMapMemory failed"), log.warnings().get(0));
        }
    }

    /**
     * M6: once a failure has switched DH drawing off, every buffer is freed (on the render thread, at the next frame) and no upload
     * allocates again, so DH's LOD memory is not held for nothing until the game restarts.
     */
    @Test
    void aFailureThatSwitchesDhOffFreesEveryBufferAndRefusesNewOnes() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer a = new DhVertexBuffer("a", gpu);
        a.uploadVertexBuffer(vertices(8), 8);
        try (LogCapture log = LogCapture.visuals()) {
            DhInternals.disable("test failure");
        }
        DhVertexBuffer.closeHandedOver();
        assertEquals(0, a.handle());
        assertEquals(1, gpu.retired.size());
        assertEquals(0, DhVertexBuffer.liveBytes());

        DhVertexBuffer b = new DhVertexBuffer("b", gpu);
        b.uploadVertexBuffer(vertices(8), 8);
        assertEquals(1, gpu.allocated.size(), "no allocation after the failure");
        assertEquals(0, b.handle());
        assertEquals(0, b.quadCount());
    }
}
