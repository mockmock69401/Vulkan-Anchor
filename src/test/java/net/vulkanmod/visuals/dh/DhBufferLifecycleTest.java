package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhBufferLifecycleTest {
    @AfterEach
    void freeLeftovers() {
        DhVertexBuffer.closeAllNow();
    }

    @Test
    void closeHandsTheBufferToTheDeferredFreeAndForgetsItsHandle() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.uploadVertexBuffer(DhVertexBufferTest.vertices(16), 16);
        assertTrue(DhVertexBuffer.LIVE.contains(vbo));
        assertEquals(16L * 16, DhVertexBuffer.liveBytes());

        vbo.close();
        assertEquals(1, gpu.retired.size());
        assertEquals(gpu.allocated.get(0), gpu.retired.get(0));
        assertEquals(0, vbo.handle());
        assertEquals(0, vbo.getVertexCount());
        assertFalse(DhVertexBuffer.LIVE.contains(vbo));
        assertEquals(0, DhVertexBuffer.liveBytes());

        vbo.close();
        assertEquals(1, gpu.retired.size(), "a second close frees nothing");
    }

    @Test
    void anUploadAfterCloseIsDropped() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.close();
        vbo.uploadVertexBuffer(DhVertexBufferTest.vertices(16), 16);
        assertEquals(0, gpu.allocated.size());
        assertEquals(0, vbo.handle());
    }

    @Test
    void closingAnEmptyBufferRetiresNothing() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer vbo = new DhVertexBuffer("t", gpu);
        vbo.close();
        assertEquals(0, gpu.retired.size());
    }

    @Test
    void freeAllReleasesEveryLiveBufferOnceAndALaterCloseIsANoOp() {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer a = new DhVertexBuffer("a", gpu);
        DhVertexBuffer b = new DhVertexBuffer("b", gpu);
        DhVertexBuffer empty = new DhVertexBuffer("c", gpu);
        a.uploadVertexBuffer(DhVertexBufferTest.vertices(4), 4);
        b.uploadVertexBuffer(DhVertexBufferTest.vertices(8), 8);
        assertEquals(3, DhVertexBuffer.liveCount());

        DhVertexBuffer.freeAll();
        DhVertexBuffer.closeHandedOver();
        assertEquals(0, DhVertexBuffer.liveCount());
        assertEquals(0, DhVertexBuffer.liveBytes());
        assertEquals(2, gpu.retired.size());
        assertEquals(0, a.handle());
        assertEquals(0, b.handle());
        assertEquals(0, empty.handle());

        DhVertexBuffer.freeAll();
        DhVertexBuffer.closeHandedOver();
        a.close();
        b.close();
        assertEquals(2, gpu.retired.size(), "freeAll is idempotent and DH's own close afterwards frees nothing");
    }

    /**
     * C1: freeAll may come from any thread (DH's world unload); it hands the buffers live at that moment to the render thread, which
     * closes them when the next frame starts recording. A buffer made after the call (the next world's) is not touched.
     */
    @Test
    void freeAllFromAnotherThreadClosesOnTheRenderThreadOnlyWhatWasLiveThen() throws Exception {
        FakeGpu gpu = new FakeGpu();
        DhVertexBuffer old = new DhVertexBuffer("old", gpu);
        old.uploadVertexBuffer(DhVertexBufferTest.vertices(8), 8);
        Thread dh = new Thread(DhVertexBuffer::freeAll, "ClientLevelTickCleanup");
        dh.start();
        dh.join();
        assertTrue(old.handle() != 0, "nothing is closed on the calling thread");
        assertEquals(0, gpu.retired.size());
        DhVertexBuffer fresh = new DhVertexBuffer("fresh", gpu);
        fresh.uploadVertexBuffer(DhVertexBufferTest.vertices(8), 8);

        DhVertexBuffer.closeHandedOver();
        assertEquals(0, old.handle());
        assertTrue(fresh.handle() != 0, "a buffer made after the call keeps drawing");
        assertEquals(1, gpu.retired.size());
        DhVertexBuffer.closeHandedOver();
        assertTrue(fresh.handle() != 0, "the hand-over runs once");
    }
}
