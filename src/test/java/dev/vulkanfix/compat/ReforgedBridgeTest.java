package dev.vulkanfix.compat;

import dev.vulkanfix.compat.fixture.FakeCapabilitiesFallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReforgedBridgeTest {
    private static final ClassLoader LOADER = ReforgedBridgeTest.class.getClassLoader();

    @BeforeEach
    void reset() {
        FakeCapabilitiesFallback.installs = 0;
    }

    @Test
    void invokesStaticInstallOnTheNamedClass() {
        assertTrue(ReforgedBridge.installEmulatedGl(FakeCapabilitiesFallback.class.getName(), LOADER));
        assertEquals(1, FakeCapabilitiesFallback.installs);
    }

    @Test
    void missingReforgedClassIsReportedNotThrown() {
        assertFalse(ReforgedBridge.installEmulatedGl("net.vulkanmod.compat.opengl.DoesNotExist", LOADER));
    }

    @Test
    void targetsReforgedsRealFallbackClassByDefault() {
        assertEquals("net.vulkanmod.compat.opengl.GlCapabilitiesFallback", ReforgedBridge.FALLBACK_CLASS);
    }
}
