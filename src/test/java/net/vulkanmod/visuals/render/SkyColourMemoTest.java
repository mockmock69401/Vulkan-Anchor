package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 14: getSkyColor's result is kept only while every input it reads is the same. */
class SkyColourMemoTest {
    @Test
    void keptWhileEveryInputIsEqual() {
        SkyColourMemo m = new SkyColourMemo();
        Object level = new Object(), v = new Object();
        assertNull(m.get(level, 10, 6000, 0, 1, 2, 3, 0.5f));
        m.put(level, 10, 6000, 0, 1, 2, 3, 0.5f, v);
        assertSame(v, m.get(level, 10, 6000, 0, 1, 2, 3, 0.5f));
        assertNull(m.get(new Object(), 10, 6000, 0, 1, 2, 3, 0.5f), "another level");
        assertNull(m.get(level, 11, 6000, 0, 1, 2, 3, 0.5f), "the next tick (rain, thunder)");
        assertNull(m.get(level, 10, 6001, 0, 1, 2, 3, 0.5f), "the time of day");
        assertNull(m.get(level, 10, 6000, 2, 1, 2, 3, 0.5f), "a lightning flash");
        assertNull(m.get(level, 10, 6000, 0, 1.0001, 2, 3, 0.5f), "the camera moved");
        assertNull(m.get(level, 10, 6000, 0, 1, 2, 3, 0.51f), "another partial tick");
        m.clear();
        assertNull(m.get(level, 10, 6000, 0, 1, 2, 3, 0.5f));
    }

    @Test
    void mixinRegisteredForTheRenderThreadOnly() throws Exception {
        String json = Files.readString(Path.of("src/main/resources/vulkanmod.mixins.json"));
        assertTrue(json.contains("\"visuals.ClientLevelSkyColourMixin\""));
        String mixin = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/visuals/ClientLevelSkyColourMixin.java"));
        assertEquals(2, mixin.split("isOnRenderThread", -1).length - 1);
        assertTrue(mixin.contains("getSkyFlashTime()") && mixin.contains("getDayTime()") && mixin.contains("getGameTime()"));
    }
}
