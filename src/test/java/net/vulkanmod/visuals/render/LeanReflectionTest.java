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
