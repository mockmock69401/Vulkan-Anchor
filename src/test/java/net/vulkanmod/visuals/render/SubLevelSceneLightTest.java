package net.vulkanmod.visuals.render;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Sable's terrain shader keeps the scene light as VulkanMod's terrain does, so the composite reads a sub-level's own sky light. */
class SubLevelSceneLightTest {
    private static String shader(String name) {
        try (InputStream in = SubLevelSceneLightTest.class.getResourceAsStream("/assets/vulkanmod/shaders/vulkanmod/compat/sable/terrain/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theFragmentShaderWritesAfterTheCutoutOnlyWhenAsked() {
        String fsh = shader("terrain.fsh");
        String write = "writeSceneLight(lightLevels, vec4(texel.rgb * albedoTint, texel.a), vertexDistance <= FogStart, terrainTinted(albedoTint));";
        assertTrue(fsh.contains("#define SCENE_LIGHT_BINDING 4\n#include \"terrain_light.glsl\""), fsh);
        assertTrue(fsh.contains("if (LightOut > 0.5) {"), fsh);
        assertTrue(fsh.indexOf("discard;") < fsh.indexOf(write), "a cut-out texel writes nothing");
        assertTrue(fsh.contains("float AlphaCutout;\n    float LightOut;\n"), "as terrain.json lays the block out");
    }

    /** A shader that does not compile would only show in game, as Sable's structures falling back to the bound pipeline. */
    @Test
    void bothStagesCompile() {
        assertNotNull(SPIRVUtils.compileShader("terrain.vsh", shader("terrain.vsh"), SPIRVUtils.ShaderKind.VERTEX_SHADER));
        assertNotNull(SPIRVUtils.compileShader("terrain.fsh", shader("terrain.fsh"), SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
    }

    @Test
    void theVertexShaderGivesTheSkyLightSableScaled() {
        String vsh = shader("terrain.vsh");
        assertTrue(vsh.indexOf("* clamp(SableSkyLightScale") < vsh.indexOf("lightLevels = vec2(lightCoordinates.y, lightCoordinates.x);"), vsh);
        assertTrue(vsh.contains("albedoTint = Color.rgb;"), vsh);
    }

    @Test
    void thePipelineNamesTheImagesAfterItsSamplersAndTheSwitchAfterTheCutout() {
        String json = shader("terrain.json");
        assertTrue(json.indexOf("\"samplers\"") < json.indexOf("\"storageImages\""));
        assertTrue(json.contains("{ \"name\": \"" + TerrainLightTarget.NAME + "\" }"));
        assertTrue(json.contains("{ \"name\": \"" + TerrainLightTarget.ALBEDO_NAME + "\" }"));
        assertTrue(json.indexOf("\"AlphaCutout\"") < json.indexOf("\"LightOut\""));
    }
}
