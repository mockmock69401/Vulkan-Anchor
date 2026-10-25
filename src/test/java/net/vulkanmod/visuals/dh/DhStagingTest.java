package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static net.vulkanmod.visuals.dh.DhStaging.Route.REFUSE;
import static net.vulkanmod.visuals.dh.DhStaging.Route.RING;
import static net.vulkanmod.visuals.dh.DhStaging.Route.TEMP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhStagingTest {
    /** Frames and memory without a device. */
    static final class FakeBackend implements DhStaging.Backend {
        long serial = 1, completed = 0;
        final Set<Long> dropped = new HashSet<>();
        final List<List<DhStaging.Copy>> recorded = new ArrayList<>();
        final List<DhStaging.Allocation> freed = new ArrayList<>();
        final List<Long> ringWrites = new ArrayList<>();
        int tempsLive;
        boolean failRingWrite, failTemp;
        private long nextHandle = 100;

        @Override
        public DhStaging.Allocation allocate(long bytes) {
            return new FakeGpu.Buffer(this.nextHandle++, bytes);
        }

        @Override
        public void free(DhStaging.Allocation a) {
            this.freed.add(a);
        }

        @Override
        public void writeRing(long offset, ByteBuffer src) {
            if (this.failRingWrite) throw new IllegalStateException("ring not mapped");
            this.ringWrites.add(offset);
        }

        @Override
        public Object copyToTemp(ByteBuffer src) {
            if (this.failTemp) throw new IllegalStateException("vmaCreateBuffer failed: -2");
            this.tempsLive++;
            return new Object();
        }

        @Override
        public void freeTemp(Object temp) {
            this.tempsLive--;
        }

        @Override
        public void record(VkCommandBuffer cmd, List<DhStaging.Copy> copies) {
            this.recorded.add(List.copyOf(copies));
        }

        @Override
        public long serial() {
            return this.serial;
        }

        @Override
        public long completedSerial() {
            return this.completed;
        }

        @Override
        public boolean isDropped(long serial) {
            return this.dropped.contains(serial);
        }
    }

    static ByteBuffer bytes(int n) {
        return ByteBuffer.allocateDirect(n);
    }

    /** The next frame starts recording after frame 'done' finished. */
    static void nextFrame(DhStaging s, FakeBackend b, long done) {
        b.serial++;
        b.completed = done;
        s.onRecordingStarted();
    }

    @Test
    void route() {
        assertEquals(RING, DhStaging.route(true, 100, 0, 1000));
        assertEquals(TEMP, DhStaging.route(false, 100, 0, 1000));
        assertEquals(TEMP, DhStaging.route(false, 100, 900, 1000));
        assertEquals(REFUSE, DhStaging.route(false, 100, 901, 1000));
    }

    @Test
    void anUploadIsCopiedNowAndRecordedByTheNextFlush() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        assertTrue(s.upload(dst, bytes(64)));
        assertEquals(1, b.ringWrites.size(), "the bytes are in the ring before any flush");
        assertEquals(0, b.recorded.size());
        assertEquals(64, s.pendingBytes());

        s.flush(null);
        assertEquals(1, b.recorded.size());
        DhStaging.Copy c = b.recorded.get(0).get(0);
        assertNull(c.temp());
        assertSame(dst, c.dst());
        assertEquals(64, c.size());
        assertEquals(0, s.pendingBytes());
        assertEquals(1, s.inFlightCount());

        s.flush(null);
        assertEquals(1, b.recorded.size(), "nothing pending, nothing recorded");
        nextFrame(s, b, 1);
        assertEquals(0, s.inFlightCount());
    }

    @Test
    void aBacklogPastTheRingUsesATemporaryBufferFreedWhenItsFrameCompletes() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation x = s.allocate(1024), y = s.allocate(512);
        assertTrue(s.upload(x, bytes(1024)));
        assertTrue(s.upload(y, bytes(512)));//the ring is full
        assertEquals(1, b.tempsLive);
        assertEquals(512, s.tempOutstanding());
        s.flush(null);
        assertTrue(b.recorded.get(0).get(1).temp() != null);

        nextFrame(s, b, 0);//frame 1 still running
        assertEquals(1, b.tempsLive);
        nextFrame(s, b, 1);
        assertEquals(0, b.tempsLive);
        assertEquals(0, s.tempOutstanding());
        //the ring is free again
        assertTrue(s.upload(y, bytes(512)));
        assertEquals(0, b.tempsLive);
    }

    @Test
    void anUploadLargerThanTheRingGoesTemporary() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        assertTrue(s.upload(s.allocate(2048), bytes(2048)));
        assertEquals(1, b.tempsLive);
        assertEquals(0, b.ringWrites.size());
    }

    @Test
    void temporaryMemoryIsBounded() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 2048);
        assertTrue(s.upload(s.allocate(2048), bytes(2048)));//past the ring: temporary, at the limit
        assertTrue(s.upload(s.allocate(16), bytes(16)));//the ring still has room
        assertFalse(s.upload(s.allocate(1024), bytes(1024)), "no ring room and no temporary memory left");
        assertEquals(1, s.refused());
        assertEquals(1, b.tempsLive);
    }

    @Test
    void aNewerUploadToTheSameBufferReplacesTheUnrecordedOne() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(256);
        s.upload(dst, bytes(256));
        s.upload(dst, bytes(128));
        s.flush(null);
        assertEquals(1, b.recorded.get(0).size());
        assertEquals(128, b.recorded.get(0).get(0).size());
    }

    @Test
    void aCopyRecordedIntoADroppedFrameIsRecordedAgain() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        s.upload(dst, bytes(64));
        s.flush(null);//frame 1, never submitted
        b.dropped.add(1L);
        nextFrame(s, b, 0);
        assertEquals(1, s.requeued());
        s.flush(null);
        assertEquals(2, b.recorded.size());
        assertSame(dst, b.recorded.get(1).get(0).dst());
    }

    @Test
    void aDroppedCopyIsNotRecordedOverANewerUpload() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        s.upload(dst, bytes(64));
        s.flush(null);
        s.upload(dst, bytes(32));//newer, before frame 1 turns out dropped
        b.dropped.add(1L);
        nextFrame(s, b, 0);
        s.flush(null);
        assertEquals(1, b.recorded.get(1).size());
        assertEquals(32, b.recorded.get(1).get(0).size());
    }

    @Test
    void retiringDropsTheUnrecordedCopyAndFreesAfterTheFrameCompletes() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        s.upload(dst, bytes(64));
        s.retire(dst);//in frame 1
        s.flush(null);
        assertEquals(0, b.recorded.size());
        nextFrame(s, b, 0);
        assertEquals(0, b.freed.size(), "frame 1 may still use it");
        nextFrame(s, b, 1);
        assertEquals(List.of(dst), b.freed);
    }

    @Test
    void aDroppedCopyIntoARetiredBufferIsForgotten() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1024, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        s.upload(dst, bytes(64));
        s.flush(null);
        s.retire(dst);
        b.dropped.add(1L);
        nextFrame(s, b, 0);
        s.flush(null);
        assertEquals(1, b.recorded.size());
        assertEquals(0, s.requeued());
    }

    @Test
    void uploadsFromSeveralThreads() throws Exception {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 1 << 20, 1 << 20);
        int threads = 8, each = 50;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Thread> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    if (s.upload(s.allocate(64), bytes(64))) ok.incrementAndGet();
                }
            });
            th.start();
            all.add(th);
        }
        start.countDown();
        for (Thread th : all) th.join();
        assertEquals(threads * each, ok.get());
        assertEquals((long) threads * each * 64, s.pendingBytes());
        s.flush(null);
        assertEquals(threads * each, b.recorded.get(0).size());
    }

    /** M5: a ring write that throws gives its region back (no PENDING region is left to block the ring for good); the failure is the caller's. */
    @Test
    void aFailedRingWriteLeavesNoPendingRegion() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 64, 4096);
        DhStaging.Allocation dst = s.allocate(64);
        b.failRingWrite = true;
        assertThrows(IllegalStateException.class, () -> s.upload(dst, bytes(64)));
        assertEquals(0, s.pendingBytes());
        b.failRingWrite = false;
        nextFrame(s, b, 0);
        assertTrue(s.upload(dst, bytes(64)));
        assertEquals(1, b.ringWrites.size(), "the whole ring is free again");
        assertEquals(0, b.tempsLive);
    }

    @Test
    void aFailedTemporaryCopyKeepsNoBookkeeping() {
        FakeBackend b = new FakeBackend();
        DhStaging s = new DhStaging(b, 64, 4096);
        DhStaging.Allocation dst = s.allocate(128);
        b.failTemp = true;
        assertThrows(IllegalStateException.class, () -> s.upload(dst, bytes(128)));
        assertEquals(0, s.pendingBytes());
        assertEquals(0, s.tempOutstanding());
        s.flush(null);
        assertEquals(0, b.recorded.size());
    }
}
