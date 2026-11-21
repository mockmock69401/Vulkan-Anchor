package net.vulkanmod.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-21: Xaero's zoomed-out tile caches saved by older builds are removed once; full-size tiles and other files stay. */
class XaeroBranchCacheResetTest {
    @TempDir
    Path game;

    private Path file(String path) throws Exception {
        Path p = game.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        return p;
    }

    @Test
    void onlyZoomLevelCachesGoAndOnlyOnce() throws Exception {
        String dim = "xaero/world-map/새로운 세계/null/";
        file(dim + "cache/1/0_-1.xwmc");
        file(dim + "cache/2/0_-1.xwmc");
        Path leaf = file(dim + "cache_1/0_-1.xwmc");
        Path cave = file(dim + "caves/-1/cache_1/0_0.xwmc");
        file("xaero/world-map/Multiplayer_x/DIM-1/cache/3/1_1.xwmc");
        Path other = file("xaero/world-map/w/null/cache/notes.txt");
        Path config = game.resolve("config");

        XaeroBranchCacheReset.runOnce(game, config, true);

        assertFalse(Files.exists(game.resolve(dim + "cache")));
        assertFalse(Files.exists(game.resolve("xaero/world-map/Multiplayer_x/DIM-1/cache")));
        assertTrue(Files.exists(leaf));
        assertTrue(Files.exists(cave));
        assertTrue(Files.exists(other), "a cache folder holding anything else is left alone");
        assertTrue(Files.exists(config.resolve(XaeroBranchCacheReset.MARKER)));

        Path later = file(dim + "cache/1/0_-1.xwmc");
        XaeroBranchCacheReset.runOnce(game, config, true);
        assertTrue(Files.exists(later), "the caches the fixed build writes are kept");
    }

    @Test
    void nothingWithoutTheWorldMap() throws Exception {
        Path tile = file("xaero/world-map/w/null/cache/1/0_0.xwmc");
        XaeroBranchCacheReset.runOnce(game, game.resolve("config"), false);
        assertTrue(Files.exists(tile));
        assertEquals(0, XaeroBranchCacheReset.reset(game.resolve("missing")).size());
    }
}
