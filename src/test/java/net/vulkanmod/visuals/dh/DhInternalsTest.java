package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhInternalsTest {
    /** Shaped like DH's block position: int coordinates behind getters. */
    public static final class FakePos {
        private final int x, y, z;

        FakePos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int getX() {
            return this.x;
        }

        public int getY() {
            return this.y;
        }

        public int getZ() {
            return this.z;
        }
    }

    /** Shaped like DH's buffer container: a public final position field and a section key. */
    public static final class FakeContainer {
        public final FakePos minCornerBlockPos;
        public final long pos;

        FakeContainer(FakePos p, long pos) {
            this.minCornerBlockPos = p;
            this.pos = pos;
        }
    }

    public static final class NoPosContainer {
        public final int something = 1;
    }

    @BeforeEach
    @AfterEach
    void reset() {
        DhInternals.resetForTest();
    }

    @Test
    void readsTheContainerCorner() {
        long[] p = DhInternals.minCornerBlockPos(new FakeContainer(new FakePos(-4096, -64, 512), 7L));
        assertArrayEquals(new long[]{-4096, -64, 512}, p);
        assertTrue(DhInternals.ok());
        //cached for the class: a second container reads too
        assertArrayEquals(new long[]{16, 0, -16}, DhInternals.minCornerBlockPos(new FakeContainer(new FakePos(16, 0, -16), 8L)));
    }

    @Test
    void aMissingFieldDisablesWithOneWarning() {
        try (LogCapture log = LogCapture.visuals()) {
            assertNull(DhInternals.minCornerBlockPos(new NoPosContainer()));
            assertFalse(DhInternals.ok());
            assertEquals(1, log.warnings().size());
            assertTrue(log.warnings().get(0).contains("minCornerBlockPos"), log.warnings().get(0));

            assertNull(DhInternals.minCornerBlockPos(new NoPosContainer()));
            assertNull(DhInternals.minCornerBlockPos(new FakeContainer(new FakePos(1, 2, 3), 0L)), "stays off once disabled");
            assertEquals(1, log.warnings().size(), "the second failure adds no warning");
        }
    }

    @Test
    void aNullCornerDisables() {
        try (LogCapture log = LogCapture.visuals()) {
            assertNull(DhInternals.minCornerBlockPos(new FakeContainer(null, 0L)));
            assertFalse(DhInternals.ok());
            assertEquals(1, log.warnings().size());
        }
    }

    @Test
    void theSectionKeyIsReadForStatisticsWithoutDisabling() {
        assertEquals(42L, DhInternals.sectionKey(new FakeContainer(new FakePos(0, 0, 0), 42L)));
        assertNull(DhInternals.sectionKey(new NoPosContainer()));
        assertTrue(DhInternals.ok());
    }

    @Test
    void registrationIsDisabledExactlyWhenTheInternalsAreNot() {
        assertEquals(!DhInternals.ok(), DhRegistration.disabled());
        DhInternals.disable("test");
        assertTrue(DhRegistration.disabled());
        assertEquals(!DhInternals.ok(), DhRegistration.disabled());
    }

    /** Shaped like DH's ConfigEntry: get() answers the API value while one is set; only the true value is saved to the toml. */
    public static final class FakeEntry {
        public Object trueValue = Boolean.TRUE, apiValue;
        public String apiUser;
        public int saves;

        public void setApiValue(Object value, String user) {
            this.apiValue = value;
            this.apiUser = user;
        }

        public Object get() {
            return this.apiValue != null ? this.apiValue : this.trueValue;
        }
    }

    /** Shaped like DH's Config.Client.Advanced.Graphics: the entries are public static fields. */
    public static final class FakeGraphics {
        public static FakeEntry overrideVanillaGraphicsSettings = new FakeEntry();
    }

    /**
     * Ruling R22: DH's overrideVanillaGraphicsSettings would turn vanilla clouds off at its first frame (right for DH's GL renderer;
     * under the fork the visuals' far clouds and their water reflections need them). The fork holds it off through the entry's API
     * value, in memory: the toml keeps the player's value, and no DH drawing is switched off by it.
     */
    @Test
    void theVanillaGraphicsOverrideIsHeldOffInMemory() {
        FakeGraphics.overrideVanillaGraphicsSettings = new FakeEntry();
        try (LogCapture log = LogCapture.visuals()) {
            assertTrue(DhInternals.holdOffVanillaGraphicsOverride(FakeGraphics.class.getName()));
            FakeEntry e = FakeGraphics.overrideVanillaGraphicsSettings;
            assertEquals(Boolean.FALSE, e.get());
            assertEquals(Boolean.TRUE, e.trueValue, "the saved value is the player's");
            assertEquals("VulkanMod", e.apiUser);
            assertEquals(0, log.warnings().size());
        }
        assertTrue(DhInternals.ok());
    }

    @Test
    void aMissingConfigWarnsOnceAndKeepsDhDrawing() {
        try (LogCapture log = LogCapture.visuals()) {
            assertFalse(DhInternals.holdOffVanillaGraphicsOverride("com.example.NoSuchConfig"));
            assertFalse(DhInternals.holdOffVanillaGraphicsOverride(NoPosContainer.class.getName()), "no such entry field");
            assertEquals(1, log.warnings().size());
            assertTrue(log.warnings().get(0).contains("overrideVanillaGraphicsSettings = false"), "the toml guidance: " + log.warnings().get(0));
        }
        assertTrue(DhInternals.ok(), "DH keeps drawing");
    }

    @Test
    void theRealClassIsDhsGraphicsConfig() {
        assertEquals("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics", DhInternals.GRAPHICS_CONFIG);
        assertEquals("overrideVanillaGraphicsSettings", DhInternals.VANILLA_OVERRIDE_FIELD);
    }

    /** M4 (spec 4.5): one line at activation names what is read of DH outside its API, and DH's version. */
    @Test
    void theActivationLineNamesTheInternalsReadAndDhsVersion() throws Exception {
        String line = DhInternals.readsLine("3.3.5-b-dev");
        assertTrue(line.contains("3.3.5-b-dev"), line);
        assertTrue(line.contains("LodBufferContainer.minCornerBlockPos"), line);
        assertTrue(line.contains("LodBufferContainer.pos"), line);
        assertTrue(line.contains("Config.Client.Advanced.Graphics.overrideVanillaGraphicsSettings"), line);
        assertTrue(net.vulkanmod.visuals.Bytecode.calls("net/vulkanmod/visuals/dh/DhRegistration", "activateSteps").contains("DhInternals.logReads"));
        assertTrue(net.vulkanmod.visuals.Bytecode.calls("net/vulkanmod/visuals/dh/DhInternals", "logReads").contains("DhApi.getModVersion"));
    }
}
