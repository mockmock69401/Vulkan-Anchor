package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shape-compatible with Vivecraft's VRSettings: public boolean vrEnabled + public void saveOptions(). */
class VrGuardSavedSettingTest {
    public static class FakeVrSettings {
        public boolean vrEnabled;
        public int saves;

        public void saveOptions() {
            saves++;
        }
    }

    @Test
    void clearsSavedVrEnabledAndSaves() throws ReflectiveOperationException {
        FakeVrSettings settings = new FakeVrSettings();
        settings.vrEnabled = true;

        assertTrue(VrGuard.clearSavedVrEnabled(settings));
        assertFalse(settings.vrEnabled);
        assertEquals(1, settings.saves);
    }

    @Test
    void leavesAlreadyDisabledSettingUntouched() throws ReflectiveOperationException {
        FakeVrSettings settings = new FakeVrSettings();

        assertFalse(VrGuard.clearSavedVrEnabled(settings));
        assertFalse(settings.vrEnabled);
        assertEquals(0, settings.saves);
    }
}
