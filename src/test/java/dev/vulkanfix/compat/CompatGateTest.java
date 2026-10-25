package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatGateTest {
    private static Predicate<String> loaded(String... modIds) {
        Set<String> ids = Set.of(modIds);
        return ids::contains;
    }

    @Test
    void skipsEverythingWhenVulkanModAbsent() {
        assertFalse(CompatGate.shouldApply("vivecraft.VRStateMixin", loaded("vivecraft")));
        assertFalse(CompatGate.shouldApply("misc.Anything", loaded()));
    }

    @Test
    void appliesVivecraftMixinWhenBothLoaded() {
        assertTrue(CompatGate.shouldApply("vivecraft.VRStateMixin", loaded("vulkanmod", "vivecraft")));
    }

    @Test
    void skipsVivecraftMixinWhenVivecraftAbsent() {
        assertFalse(CompatGate.shouldApply("vivecraft.VRStateMixin", loaded("vulkanmod")));
    }

    @Test
    void appliesImmediatelyFastMixinOnlyWhenImmediatelyFastLoaded() {
        assertTrue(CompatGate.shouldApply("immediatelyfast.WindowEarlyGlMixin", loaded("vulkanmod", "immediatelyfast")));
        assertFalse(CompatGate.shouldApply("immediatelyfast.WindowEarlyGlMixin", loaded("vulkanmod")));
    }

    @Test
    void appliesAaaParticlesMixinOnlyWhenAaaParticlesLoaded() {
        assertTrue(CompatGate.shouldApply("aaaparticles.NativeLibraryGuardMixin", loaded("vulkanmod", "aaa_particles")));
        assertFalse(CompatGate.shouldApply("aaaparticles.NativeLibraryGuardMixin", loaded("vulkanmod")));
        assertFalse(CompatGate.shouldApply("aaaparticles.NativeLibraryGuardMixin", loaded("aaa_particles")));
    }

    @Test
    void appliesFancyMenuMixinOnlyWhenFancyMenuLoaded() {
        assertTrue(CompatGate.shouldApply("fancymenu.SmoothImageRectFallbackMixin", loaded("vulkanmod", "fancymenu")));
        assertFalse(CompatGate.shouldApply("fancymenu.SmoothImageRectFallbackMixin", loaded("vulkanmod")));
    }

    @Test
    void mixinWithoutModPrefixNeedsOnlyVulkanMod() {
        assertTrue(CompatGate.shouldApply("misc.Anything", loaded("vulkanmod")));
    }
}
