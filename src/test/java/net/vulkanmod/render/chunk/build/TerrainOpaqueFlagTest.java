package net.vulkanmod.render.chunk.build;

import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 23: a solid-layer block's texels are opaque whatever their texture alpha (Conquest Reforged's coal ore: holes and mirror
 * specks). The flag rides in the compressed vertex's last 4 bytes (UV2, location 3) and both terrain fragment shaders honour it before
 * the alpha cut and the scene marks are made from the colour.
 */
class TerrainOpaqueFlagTest {
    private static String read(String path) throws Exception {
        try (InputStream in = TerrainOpaqueFlagTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theVertexShaderPassesTheFlagOn() throws Exception {
        String v = read("assets/vulkanmod/shaders/basic/terrain/terrain.vsh");
        assertTrue(v.contains("layout (location = 3) in ivec2 UV2;"), v);
        assertTrue(v.contains("terrainOpaque = UV2.x & 1;"), v);
        assertEquals(1, TerrainBufferBuilder.TERRAIN_OPAQUE_FLAG);
    }

    @Test
    void bothFragmentShadersMakeTheTexelOpaqueBeforeUsingItsAlpha() throws Exception {
        for (String f : new String[]{"terrain.fsh", "terrain_z.fsh"}) {
            String s = read("assets/vulkanmod/shaders/basic/terrain/" + f);
            int flag = s.indexOf("if (terrainOpaque != 0) {");
            assertTrue(flag > 0, f);
            assertTrue(s.indexOf("texel.a = 1.0;", flag) > flag, f);
            assertTrue(s.indexOf("vec4 color = texel * vertexColor;") > flag, f + ": before the colour (cut and marks) is made");
        }
    }
}
