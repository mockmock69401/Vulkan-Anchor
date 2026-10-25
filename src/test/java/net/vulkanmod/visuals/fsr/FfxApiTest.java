package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FSR design 4.3: ffx return codes become exceptions that name the code. */
class FfxApiTest {
    @Test void checkNamesTheCode() {
        FsrException e = assertThrows(FsrException.class, () -> FfxApi.check(4, "x"));
        assertTrue(e.getMessage().contains("NO_PROVIDER"), e.getMessage());
        assertTrue(e.getMessage().contains("x"), e.getMessage());
    }

    @Test void checkAcceptsOkAndNamesUnknownCodes() {
        assertDoesNotThrow(() -> FfxApi.check(FfxApi.RETURN_OK, "x"));
        assertTrue(assertThrows(FsrException.class, () -> FfxApi.check(99, "x")).getMessage().contains("99"));
    }
}
