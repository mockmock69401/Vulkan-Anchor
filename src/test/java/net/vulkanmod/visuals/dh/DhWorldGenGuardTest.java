package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DhWorldGenGuardTest {
    @Test
    void onlyPartialRegionModesAreHeld() {
        assertTrue(DhWorldGenGuard.partialRegion(EDhApiDistantGeneratorMode.FEATURES));
        assertTrue(DhWorldGenGuard.partialRegion(EDhApiDistantGeneratorMode.SURFACE));
        assertFalse(DhWorldGenGuard.partialRegion(EDhApiDistantGeneratorMode.INTERNAL_SERVER));
        assertFalse(DhWorldGenGuard.partialRegion(EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY));
        assertFalse(DhWorldGenGuard.partialRegion(null));
    }
}
