package net.vulkanmod.visuals.dh;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02: a dedicated server with Distant Horizons generates LODs for its players itself, with the same partial regions and
 * generation threads as the integrated server. DH's world-gen isolation mixins target server classes, so they are common mixins
 * (a "client" mixin never applies on a dedicated server).
 */
class DhServerWorldGenTest {
    private static final List<String> SERVER_MIXINS = List.of("visuals.WorldGenRegionBlendingMixin", "visuals.ServerChunkCacheDhIsolationMixin");

    @Test
    void worldGenIsolationMixinsApplyOnBothSides() throws Exception {
        JsonObject config;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanmod.mixins.json")) {
            config = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> common = names(config.getAsJsonArray("mixins"));
        List<String> client = names(config.getAsJsonArray("client"));
        for (String m : SERVER_MIXINS) {
            assertTrue(common.contains(m), m + " is not a common mixin");
            assertFalse(client.contains(m), m + " is still a client mixin");
        }
    }

    private static List<String> names(JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(e -> out.add(e.getAsString()));
        return out;
    }
}
