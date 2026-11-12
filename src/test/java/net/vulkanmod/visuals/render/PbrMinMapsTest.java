package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 14 follow-up: PBR stays off unless the resource packs give at least MIN_LABPBR_MAPS LabPBR maps. */
class PbrMinMapsTest {
    @Test
    void aFewMapsDoNotTurnPbrOn() throws Exception {
        assertEquals(32, PbrMath.MIN_LABPBR_MAPS);
        assertFalse(PbrMath.enoughMaps(0, 0));
        assertFalse(PbrMath.enoughMaps(1, 0), "CTE2: one mod texture ending in _n");
        assertFalse(PbrMath.enoughMaps(16, 15));
        assertTrue(PbrMath.enoughMaps(16, 16));
        assertTrue(PbrMath.enoughMaps(32, 0));
        assertTrue(PbrMath.enoughMaps(560, 548), "Embrace Pixels 2.1");
        String atlas = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/PbrAtlas.java"));
        int check = atlas.indexOf("if (!PbrMath.enoughMaps(foundN, foundS)) {");
        assertTrue(check > 0 && check < atlas.indexOf("normal = foundN > 0 ? upload(n, w, h) : constant(FLAT_NORMAL);"), "checked before anything is uploaded");
        assertTrue(atlas.contains("return normal != null && normalCount + specularCount > 0;"), "no atlas: PBR inactive");
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String json = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/" + lang + ".json"));
            assertTrue(json.contains("32"), lang);
        }
    }
}
