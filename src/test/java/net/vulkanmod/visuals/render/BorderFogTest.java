package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-21 (CTE2: with the LODs off the terrain ended against the sky): the composite's border fog. */
class BorderFogTest {
    @Test
    void onlyWithoutLodsInTheSkyModeWithAKnownRenderDistance() {
        assertArrayEquals(new float[]{144, 192, 1, 0}, AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_SKY, 192), 1e-4f);
        assertArrayEquals(new float[4], AtmosphereUniforms.border(true, AtmosphereMath.ATMOSPHERE_SKY, 192));
        assertArrayEquals(new float[4], AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_FOG, 192));
        assertArrayEquals(new float[4], AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_NO_SKY, 192));
        assertArrayEquals(new float[4], AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_SKY, 0));
        assertArrayEquals(new float[4], AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_SKY, Float.POSITIVE_INFINITY));
        //2026-10-28: .w carries the weather fog, with or without the border
        assertArrayEquals(new float[]{144, 192, 1, 0.012f}, AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_SKY, 192, 0.012f), 1e-6f);
        assertArrayEquals(new float[]{0, 0, 0, 0.012f}, AtmosphereUniforms.border(true, AtmosphereMath.ATMOSPHERE_SKY, 192, 0.012f), 1e-6f);
    }

    @Test
    void theShareGrowsWithTheHorizontalDistance() {
        float[] slot = AtmosphereUniforms.border(false, AtmosphereMath.ATMOSPHERE_SKY, 192);
        float[] level = {1, 0, 0};
        assertEquals(0, SceneCompositeMath.borderAmount(slot, level, 100, false), 0);
        assertEquals(0.5f, SceneCompositeMath.borderAmount(slot, level, 168, false), 1e-5f);
        assertEquals(1, SceneCompositeMath.borderAmount(slot, level, 300, false), 0);
        //Looking straight down from high up: no horizontal distance, no border
        assertEquals(0, SceneCompositeMath.borderAmount(slot, new float[]{0, -1, 0}, 300, false), 0);
        //LOD pixels and the switch off
        assertEquals(0, SceneCompositeMath.borderAmount(slot, level, 300, true), 0);
        assertEquals(0, SceneCompositeMath.borderAmount(new float[3], level, 300, false), 0);
    }

    @Test
    void theShaderFadesIntoTheSkyBeforeTheFarClouds() throws IOException {
        String comp;
        try (InputStream in = BorderFogTest.class.getResourceAsStream("/assets/vulkanmod/visuals/shaders/post/scene_copy.comp")) {
            comp = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(comp.contains("layout(location = 30) uniform vec4 borderFog;"));
        int border = comp.indexOf("lit = mix(lit, skyColour(a, dir) + glowLobeOpen(a, dir, sunOpen)*glowGain(skyAirMass(dir.y)), border);");
        int clouds = comp.indexOf("lit = farCloudsOver(a, lit, dir, dist, p);");
        assertTrue(border > 0 && clouds > border);
        assertTrue(comp.contains("return smoothstep(borderFog.x, borderFog.y, length(world.xz));"));
    }
}
