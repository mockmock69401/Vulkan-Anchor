package dev.vulkanfix.compat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VrGuardTest {
    @BeforeEach
    void reset() {
        VrGuard.resetForTest();
    }

    @Test
    void onlyFirstAttemptClaimsTheWarning() {
        assertTrue(VrGuard.claimFirstWarning());
        assertFalse(VrGuard.claimFirstWarning());
        assertFalse(VrGuard.claimFirstWarning());
    }

    @Test
    void manyConcurrentAttemptsYieldExactlyOneWarning() throws InterruptedException {
        int threads = 16;
        java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] pool = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            pool[i] = new Thread(() -> {
                if (VrGuard.claimFirstWarning()) {
                    claims.incrementAndGet();
                }
            });
            pool[i].start();
        }
        for (Thread t : pool) {
            t.join();
        }
        assertTrue(claims.get() == 1, "expected exactly one claim, got " + claims.get());
    }
}
