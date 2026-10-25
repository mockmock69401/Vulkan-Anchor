package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.PostUniforms;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The draws set loose uniforms by their GL location; the writer puts them where the converted shader reads them. */
class LooseUniformWriterTest {
    private static VulkanShaderMeta stage(String program, int index) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(index), ShaderSource.classpath()), p.defines()).meta();
    }

    /**
     * W2: the final pass (post_final.frag and post_ldr.comp, both through postfinal.glsl) takes PostUniforms' vec4s as push
     * constants, and PostUniforms.Values.write fills every location the shader declares.
     */
    @Test
    void theFinalPassUniformsFitInPushConstantsAndAreAllWritten() {
        PostUniforms.Values values = PostUniforms.compute(PostProcessSettings.from(new VisualsConfig()), 2560, 1440, 0.2f, true, 7,
                new float[]{1, 0.8f, 0.6f});
        for (VulkanShaderMeta meta : new VulkanShaderMeta[]{stage("postFinal", 1), stage("postLdr", 0)}) {
            LooseUniformWriter w = new LooseUniformWriter(meta);
            assertFalse(w.inUniformBuffer());
            assertTrue(meta.pushSize() <= GlslForVulkan.MAX_PUSH_CONSTANT_BYTES, "push size " + meta.pushSize());
            assertFalse(meta.push().isEmpty());
            //Every declared member starts as NaN; after write none may be left
            for (VulkanShaderMeta.PushMember m : meta.push()) {
                assertEquals("vec4", m.type(), m.name());
                w.putVec4(m.location(), Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
            values.write(w);
            ByteBuffer b = w.buffer();
            for (VulkanShaderMeta.PushMember m : meta.push()) {
                for (int i = 0; i < 4; i++) {
                    float f = b.getFloat(m.offset() + 4 * i);
                    assertFalse(Float.isNaN(f), "location " + m.location() + " (" + m.name() + ") component " + i + " not written");
                }
            }
            assertTrue(w.has(0));
        }
        //Both programs declare the same block (one postfinal.glsl)
        assertEquals(stage("postFinal", 1).push(), stage("postLdr", 0).push());
    }

    @Test
    void theBlitsSharpenStrengthSitsAtTheStart() {
        LooseUniformWriter w = new LooseUniformWriter(stage("postBlit", 1));
        assertFalse(w.inUniformBuffer());
        w.putVec4(0, 0.5f, 0, 0, 0);
        assertEquals(16, w.buffer().remaining());
        assertEquals(0.5f, w.buffer().getFloat(0));
    }

    @Test
    void theEdgeCoverMatrixAndCameraFractionFillOnePushBlock() {
        LooseUniformWriter w = new LooseUniformWriter(stage("edgeCover", 0));
        assertFalse(w.inUniformBuffer());
        w.putMat4(0, new Matrix4f().translation(1, 2, 3)).putVec4(1, 0.1f, 0.2f, 0.3f, 0.4f);
        ByteBuffer b = w.buffer();
        assertEquals(80, b.remaining());
        assertEquals(1f, b.getFloat(48));
        assertEquals(2f, b.getFloat(52));
        assertEquals(3f, b.getFloat(56));
        assertEquals(0.1f, b.getFloat(64));
        assertEquals(0.4f, b.getFloat(76));
    }

    @Test
    void uniformsAboveThePushLimitUseTheUniformBlockAtStd140Offsets() {
        VulkanShaderMeta meta = GlslForVulkan.pushConstants(
                "#version 460 core\nlayout(location = 1) uniform mat4 a;\nlayout(location = 2) uniform mat4 b;\nlayout(location = 4) uniform vec4 d;\n"
                        + "layout(location = 5) uniform vec4 e;\n", Map.of()).meta();
        LooseUniformWriter w = new LooseUniformWriter(meta);
        assertTrue(w.inUniformBuffer());
        w.putMat4(1, new Matrix4f().translation(1, 2, 3)).putMat4(2, new Matrix4f().scaling(5)).putVec4(4, 0.5f, -1f, 0.75f, 0).putVec4(5, 0.1f, 0.2f, 0.3f, 1);
        ByteBuffer b = w.buffer();
        assertEquals(160, b.remaining());
        assertEquals(1f, b.getFloat(48));
        assertEquals(3f, b.getFloat(56));
        assertEquals(5f, b.getFloat(64));
        assertEquals(0.5f, b.getFloat(128));
        assertEquals(-1f, b.getFloat(132));
        assertEquals(0.3f, b.getFloat(152));
    }

    @Test
    void rejectsAWrongTypeOrAnUnknownLocation() {
        LooseUniformWriter w = new LooseUniformWriter(stage("postBlit", 1));
        assertThrows(IllegalArgumentException.class, () -> w.putMat4(0, new Matrix4f()));
        assertThrows(IllegalArgumentException.class, () -> w.putVec4(9, 0, 0, 0, 0));
    }
}
