package net.vulkanmod.visuals.dev;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pilot's script format: lines, comments, the command names the existing scripts use, config keys, the glide line. */
class VisualsPilotScriptTest {
    @Test
    void blankAndCommentLinesAreSkipped() {
        assertNull(PilotScript.parse(""));
        assertNull(PilotScript.parse("   "));
        assertNull(PilotScript.parse("# a comment"));
        assertNull(PilotScript.parse("  # indented"));
    }

    @Test
    void aLineIsACommandAndItsRestOfLine() {
        PilotScript.Line l = PilotScript.parse("  set  taa   false ");
        assertEquals("set", l.command());
        assertEquals("taa   false", l.arg());
        PilotScript.Line bare = PilotScript.parse("quit");
        assertEquals("quit", bare.command());
        assertEquals("", bare.arg());
    }

    @Test
    void everyKeptCommandNameIsKnownAndLodCommandsAreNot() {
        for (String c : new String[]{"world", "wait", "cmd", "ccmd", "rd", "fov", "zoom", "key", "hold", "set", "gui", "shot", "fps",
                "log", "vm", "spin", "burst", "glide", "gpu", "screen", "quit", "size", "f3", "chunks", "leave", "frames", "fgburst", "fsr", "window"}) {
            assertTrue(PilotScript.isKnown(c), c);
        }
        for (String c : new String[]{"blockhash", "densitydump", "frobnicate"}) {
            assertFalse(PilotScript.isKnown(c), c);
        }
    }

    @Test
    void setWritesTheConfigFieldByItsType() throws Exception {
        VisualsConfig c = new VisualsConfig();
        PilotScript.setField(c, "taa", "false");
        PilotScript.setField(c, "exposure", "130");
        PilotScript.setField(c, "lightDebug", "2");
        assertFalse(c.taa);
        assertEquals(130, c.exposure);
        assertEquals(2, c.lightDebug);
    }

    @Test
    void setRejectsAnUnknownKey() {
        assertThrows(ReflectiveOperationException.class, () -> PilotScript.setField(new VisualsConfig(), "noSuchKey", "1"));
    }

    @Test
    void glideIsLinearAndClampedAtTheEnds() {
        double[] a = {0, 10, 0}, b = {10, 10, -20};
        assertArrayEquals(new double[]{0, 10, 0}, Glide.at(a, b, 2.0, -1.0), 1e-9);
        assertArrayEquals(new double[]{5, 10, -10}, Glide.at(a, b, 2.0, 1.0), 1e-9);
        assertArrayEquals(new double[]{10, 10, -20}, Glide.at(a, b, 2.0, 3.0), 1e-9);
    }

    @Test
    void fgburstParsesANameAndACount() {
        PilotScript.FgBurst b = PilotScript.parseFgBurst("warp 8");
        assertEquals("warp", b.name());
        assertEquals(8, b.count());
        assertEquals(32, PilotScript.parseFgBurst("x_1-a.b 32").count());
    }

    @Test
    void fgburstRejectsACountPastTheLimitAndBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFgBurst("warp 33"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFgBurst("warp 0"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFgBurst("warp"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFgBurst("warp many"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFgBurst("../up 4"));
    }

    @Test
    void fsrDebugWordsCombineIntoFfxFlags() {
        //FfxApiDispatchFrameGenerationFlags: tear lines 1 << 0, debug view 1 << 2, pacing lines 1 << 4
        assertEquals(new PilotScript.FsrDebug(1 << 2, false), PilotScript.parseFsrDebug("view"));
        assertEquals(new PilotScript.FsrDebug(1, false), PilotScript.parseFsrDebug("tear"));
        assertEquals(new PilotScript.FsrDebug(1 << 4, false), PilotScript.parseFsrDebug("pacing"));
        assertEquals(new PilotScript.FsrDebug((1 << 2) | (1 << 4), true), PilotScript.parseFsrDebug(" view  pacing generated "));
        assertEquals(new PilotScript.FsrDebug(0, true), PilotScript.parseFsrDebug("generated"));
        assertEquals(new PilotScript.FsrDebug(0, false), PilotScript.parseFsrDebug("off"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFsrDebug("view sparkles"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFsrDebug("off view"));
        assertThrows(IllegalArgumentException.class, () -> PilotScript.parseFsrDebug(""));
    }

    @Test
    void fsrStatusGivesPresentsPerFrameSinceTheLastLine() {
        assertEquals("support OK, live true, presents 1201, frame 600; since the last status (5.0 s): 600 presents in 300 frames, "
                + "2.00 presents per frame, 60.0 fps, 120.0 presents/s", PilotScript.fsrStatus("OK", true, 1201, 600, 601, 300, 5.0));
        //no previous line, FSR off, or FSR's swapchain remade since (its count restarted): the totals only
        assertEquals("support OK, live true, presents 40, frame 20", PilotScript.fsrStatus("OK", true, 40, 20, -1, -1, 3.0));
        assertEquals("support OK, live false, presents -1, frame 900", PilotScript.fsrStatus("OK", false, -1, 900, 1201, 600, 5.0));
        assertEquals("support OK, live true, presents 30, frame 915", PilotScript.fsrStatus("OK", true, 30, 915, 1201, 600, 5.0));
        assertEquals("support DLL, live false, presents -1, frame -1", PilotScript.fsrStatus("DLL", false, -1, -1, -1, -1, 0));
    }
}
