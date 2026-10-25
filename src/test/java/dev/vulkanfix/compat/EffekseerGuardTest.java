package dev.vulkanfix.compat;

import mod.chloeprime.aaaparticles.client.installer.NativePlatform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffekseerGuardTest {
    @Test
    void marksAaaParticlesPlatformUnsupportedWithTheReason() {
        NativePlatform.unsupportedBecause = null;
        assertTrue(EffekseerGuard.markPlatformUnsupported());
        assertNotNull(NativePlatform.unsupportedBecause);
        assertEquals(EffekseerGuard.REASON, NativePlatform.unsupportedBecause.getMessage());
    }

    @Test
    void reportsFailureWhenNativePlatformIsMissing() {
        assertFalse(EffekseerGuard.markPlatformUnsupported("mod.chloeprime.aaaparticles.client.installer.Missing"));
    }
}
