package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-23: Distant Horizons' generation skips vanilla's blending scan and never makes the server load real chunks; nothing else changes. */
class DhWorldGenIsolationTest {
    @Test
    void onlyDistantHorizonsRegionsSkipTheBlendingScan() throws Exception {
        assertFalse(DhWorldGenIsolation.isDhRegion(Object.class));
        assertFalse(DhWorldGenIsolation.isDhRegion(null));
        //DH's released jars run the loader's copy under a suffixed name
        assertTrue(DhWorldGenIsolation.isDhRegionName(DhWorldGenIsolation.DH_REGION));
        assertTrue(DhWorldGenIsolation.isDhRegionName(DhWorldGenIsolation.DH_REGION + "_forge"));
        assertTrue(DhWorldGenIsolation.isDhRegionName(DhWorldGenIsolation.DH_REGION + "_neoforge"));
        assertFalse(DhWorldGenIsolation.isDhRegionName(DhWorldGenIsolation.DH_REGION + "Other"));
        assertFalse(DhWorldGenIsolation.isDhRegionName("net.minecraft.server.level.WorldGenRegion"));
        assertEquals("com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion", DhWorldGenIsolation.DH_REGION);
        String mixin = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/visuals/WorldGenRegionBlendingMixin.java"));
        assertTrue(mixin.contains("@Mixin(WorldGenRegion.class)") && mixin.contains("@Inject(method = \"isOldChunkAround\", at = @At(\"HEAD\"), cancellable = true)"));
        assertTrue(mixin.contains("if (DhWorldGenIsolation.isDhRegion(this.getClass())) cir.setReturnValue(false);"));
    }

    @Test
    void onlyDistantHorizonsGenerationStepsAreKeptOffTheServer() throws Exception {
        assertFalse(DhWorldGenIsolation.inDhGenerationStep(), "the test thread");
        //A DH world gen thread outside the steps (its other modes ask the server on purpose)
        AtomicBoolean seen = new AtomicBoolean(true);
        Thread t = new Thread(() -> seen.set(DhWorldGenIsolation.inDhGenerationStep()), "DH-World Gen Thread[0]");
        t.start();
        t.join();
        assertFalse(seen.get());
        assertEquals("com.seibel.distanthorizons.common.wrappers.worldGeneration.step.", DhWorldGenIsolation.DH_STEPS);
        String mixin = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/visuals/ServerChunkCacheDhIsolationMixin.java"));
        assertTrue(mixin.contains("if (!DhWorldGenIsolation.inDhGenerationStep()) return;"));
        assertTrue(mixin.contains("cir.setReturnValue(require ? DhWorldGenIsolation.emptyChunk((ServerLevel) ((ServerChunkCache) (Object) this).getLevel(), x, z) : null);"));
        //This build does not map shadowed fields (a @Shadow ServerChunkCache.level crashed the server at its first chunk)
        assertFalse(java.util.regex.Pattern.compile("(?m)^\\s*@Shadow").matcher(mixin).find());
        String json = Files.readString(Path.of("src/main/resources/vulkanmod.mixins.json"));
        assertTrue(json.contains("\"visuals.WorldGenRegionBlendingMixin\"") && json.contains("\"visuals.ServerChunkCacheDhIsolationMixin\""));
    }
}
