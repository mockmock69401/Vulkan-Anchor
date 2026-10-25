package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shaders declare the uniforms PostUniforms writes, as push constants (the post-process does not upload a uniform block). */
class PostShaderUniformsTest {
    private static VulkanShaderMeta meta(String program, int stage) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(stage), ShaderSource.classpath()), p.defines()).meta();
    }


    @Test
    void everyBloomStepTakesItsParametersAsPushConstants() {
        for (String program : List.of("bloom.PREFILTER", "bloom.DOWN", "bloom.UP")) {
            LooseUniformWriter w = new LooseUniformWriter(meta(program, 0));
            assertFalse(w.inUniformBuffer(), program);
            assertTrue(w.has(PostUniforms.BLOOM_PARAMS), program);
        }
    }

    /** The copy's matrices exceed the push constant budget: a uniform block, which the post-process uploads. */
    @Test
    void theSceneCopyTakesTheAtmosphereAsAUniformBlock() {
        LooseUniformWriter w = new LooseUniformWriter(meta("postSceneCopy", 0));
        assertTrue(w.inUniformBuffer());
        for (int location = 0; location <= 34; location++) {//boundary.md 3.2: slots 0..34
            assertTrue(w.has(location), "location " + location);
        }
    }

    @Test
    void bothShaftStepsTakeTheSunPositionAsPushConstants() {
        for (String program : List.of("shafts.MASK", "shafts.BLUR")) {
            LooseUniformWriter w = new LooseUniformWriter(meta(program, 0));
            assertFalse(w.inUniformBuffer(), program);
            assertTrue(w.has(0), program);//boundary.md 3.7: location 0, the sun's screen position
        }
    }
}
