package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 7 (2026-11-10): the reflection pass is built twice; postReflection.LEAN leaves out the aurora and the realistic clouds and is
 * used on frames that draw neither (VkPostProcess.reflectionLeanOn).
 */
class LeanReflectionTest {
    private static String converted(String name) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(name);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).source();
    }

    private static Object meta(String name) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(name);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    @Test
    void theLeanPassTakesTheSameUniformsAndDropsOnlyTheAuroraAndTheRealisticClouds() {
        //VkPostProcess uses the lean program only with the full one's uniforms and bindings (one writer, one buffer)
        assertEquals(meta("postReflection"), meta("postReflection.LEAN"));
        String text = ShaderFiles.read("post/reflection.comp");
        //Exactly two branches are left out, each the whole of the feature's use in the pass
        assertEquals(2, text.split("#ifndef REFLECT_LEAN", -1).length - 1);
        assertTrue(text.contains("#ifndef REFLECT_LEAN\n    if (cloudRealistic()) {\n        return realMarch(") || text.contains("#ifndef REFLECT_LEAN\r\n    if (cloudRealistic()) {"));
        assertTrue(text.contains("#ifndef REFLECT_LEAN\n        if (auroraParams.x > 0.0) {") || text.contains("#ifndef REFLECT_LEAN\r\n        if (auroraParams.x > 0.0) {"));
        assertTrue(converted("postReflection.LEAN").contains("#define REFLECT_LEAN"));
        assertFalse(converted("postReflection").contains("#define REFLECT_LEAN"));
    }

    /** Session 8: the tinted builds apply the picked colours; the plain ones (the defaults) leave the tint arithmetic out, the composite's too. */
    @Test
    void theTintedBuildsTakeTheSameUniformsAndOnlyTheyAndTheCompositeApplyTheTints() {
        assertEquals(meta("postReflection"), meta("postReflection.TINTED"));
        assertEquals(meta("postReflection"), meta("postReflection.LEAN.TINTED"));
        assertTrue(converted("postReflection.TINTED").contains("#define ATMOSPHERE_TINTS"));
        assertTrue(converted("postReflection.LEAN.TINTED").contains("#define ATMOSPHERE_TINTS") && converted("postReflection.LEAN.TINTED").contains("#define REFLECT_LEAN"));
        assertEquals(meta("postSceneCopy"), meta("postSceneCopy.TINTED"));
        assertTrue(converted("postSceneCopy.TINTED").contains("#define ATMOSPHERE_TINTS"));
        assertFalse(converted("postSceneCopy").contains("#define ATMOSPHERE_TINTS"));
        assertFalse(converted("postReflection").contains("#define ATMOSPHERE_TINTS"));
        assertFalse(converted("postReflection.LEAN").contains("#define ATMOSPHERE_TINTS"));
        //which frames need them: a tint in the reflection's block, or an aurora tint
        PostProcessSettings def = PostProcessSettings.from(new net.vulkanmod.visuals.config.VisualsConfig());
        net.vulkanmod.visuals.config.VisualsConfig fog = new net.vulkanmod.visuals.config.VisualsConfig();
        fog.setColour(ShaderColours.Colour.FOG, 0xFF0000);
        float[] plain = AtmosphereUniforms.block(def, null, true);
        assertFalse(new ReflectionUniforms.Values(true, null, null, null, false, null, plain, new float[8], 1, 1, 1, false, 0, 0, 0, 0, 1).tinted());
        assertTrue(CelestialUniforms.off().tints().length == 20 && !CelestialUniforms.off().auroraTinted());
        net.vulkanmod.visuals.config.VisualsConfig green = new net.vulkanmod.visuals.config.VisualsConfig();
        green.setColour(ShaderColours.Colour.AURORA_MAIN, 0x00FF00);
        ShaderColours aurora = ShaderColours.of(green.colours());
        assertTrue(new CelestialUniforms.Values(new float[4], new float[4], CelestialUniforms.tints(aurora, false)).auroraTinted());
        assertFalse(new CelestialUniforms.Values(new float[4], new float[4], CelestialUniforms.tints(ShaderColours.DEFAULT, true)).auroraTinted());
        float[] tinted = plain.clone();
        tinted[AtmosphereUniforms.TINTS] = 0.5f;
        assertTrue(new ReflectionUniforms.Values(true, null, null, null, false, null, tinted, new float[8], 1, 1, 1, false, 0, 0, 0, 0, 1).tinted());
        assertFalse(new ReflectionUniforms.Values(false, null, null, null, false, null, tinted, new float[8], 1, 1, 1, false, 0, 0, 0, 0, 1).tinted());
    }

    @Test
    void theCloudBlockTellsTheRealisticStyle() {
        float[] slots = new float[4 * CloudUniforms.SLOTS];
        assertFalse(new CloudUniforms.Values(true, slots).realistic());
        slots[6] = 2.37f;//quality 2 (Medium) with a jitter phase
        assertTrue(new CloudUniforms.Values(true, slots).realistic());
        slots[6] = 0.99f;
        assertFalse(new CloudUniforms.Values(true, slots).realistic());
    }
}
