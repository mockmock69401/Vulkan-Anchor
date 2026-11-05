package dev.vulkanfix.mixin.puffish;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vulkanfix.compat.PufferfishItemEmits;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Pufferfish's Skills bakes each item icon of the skill tree at the origin and draws it once per emit matrix, from an
 * inject inside BufferUploader._drawWithShader. Reforged's drawWithShader overwrite records the draw itself and never
 * calls that method, so every icon landed in the screen's top-left corner. The emits are drawn here instead.
 */
@Mixin(value = BufferUploader.class, priority = 1100, remap = false)
public abstract class BufferUploaderEmitsMixin {
    @Redirect(
            method = "m_231202_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/Drawer;draw(Ljava/nio/ByteBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;I)V",
                    remap = false),
            remap = false)
    private static void vulkanfix$drawAtEveryEmit(Drawer drawer, ByteBuffer vertices, VertexFormat.Mode mode,
                                                  VertexFormat format, int vertexCount,
                                                  BufferBuilder.RenderedBuffer renderedBuffer) {
        List<Matrix4f> emits = PufferfishItemEmits.of(renderedBuffer);
        if (emits == null) {
            drawer.draw(vertices, mode, format, vertexCount);
            return;
        }

        Renderer renderer = Renderer.getInstance();
        Pipeline pipeline = renderer.getBoundPipeline();
        Matrix4f modelView = RenderSystem.getModelViewMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        for (Matrix4f emit : emits) {
            VRenderSystem.applyMVP(PufferfishItemEmits.modelView(modelView, emit), projection);
            renderer.uploadAndBindUBOs(pipeline);
            drawer.draw(vertices, mode, format, vertexCount);
        }
        VRenderSystem.applyMVP(modelView, projection);
    }
}
