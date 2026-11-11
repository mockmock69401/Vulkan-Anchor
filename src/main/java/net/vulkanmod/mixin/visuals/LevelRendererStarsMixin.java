package net.vulkanmod.mixin.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.visuals.render.StarMath;
import net.vulkanmod.visuals.render.StarSky;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 2026-11-11 (shader roadmap session 12): the star settings on vanilla's stars (StarMath, StarSky). drawStars: the number of tries and
 * the half size (both of its size constants times the factor, so each star keeps its random size). renderSky: the stars' shader
 * colour. At the defaults every value is vanilla's.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererStarsMixin {
    @ModifyConstant(method = "drawStars", constant = @Constant(intValue = StarMath.VANILLA_STARS))
    private int visuals$starTries(int vanilla) {
        return StarSky.tries();
    }

    @ModifyConstant(method = "drawStars", constant = @Constant(floatValue = StarMath.VANILLA_SIZE_BASE))
    private float visuals$starSizeBase(float vanilla) {
        return vanilla * StarSky.sizeScale();
    }

    @ModifyConstant(method = "drawStars", constant = @Constant(floatValue = StarMath.VANILLA_SIZE_SPREAD))
    private float visuals$starSizeSpread(float vanilla) {
        return vanilla * StarSky.sizeScale();
    }

    /**
     * 2026-11-11 (session 13): round stars (StarMath.TYPE_ROUND). The buffer is built with texture coordinates (each quad's corners) and
     * drawn with vanilla's position-texture shader and the round point; vanilla's squares otherwise, exactly as before.
     */
    //1.21.1: drawStars begins its buffer on the Tesselator and adds each corner as a Vector3f
    @Redirect(method = "drawStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/Tesselator;begin(Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;Lcom/mojang/blaze3d/vertex/VertexFormat;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"))
    private BufferBuilder visuals$starFormat(Tesselator tesselator, VertexFormat.Mode mode, VertexFormat format) {
        return tesselator.begin(mode, StarSky.type() == StarMath.TYPE_ROUND ? DefaultVertexFormat.POSITION_TEX : format);
    }

    @Redirect(method = "drawStars", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Vector3f;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer visuals$starCorner(BufferBuilder builder, Vector3f corner) {
        VertexConsumer v = builder.addVertex(corner);
        if (!StarSky.round()) return v;
        int i = StarSky.nextCorner();
        return v.setUv(StarMath.cornerU(i), StarMath.cornerV(i));
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"))
    private void visuals$starDraw(VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader) {
        if (StarSky.round() && buffer == ((LevelRendererStarsInvoker) this).visuals$starBuffer()) {
            RenderSystem.setShaderTexture(0, StarSky.roundTexture());
            buffer.drawWithShader(pose, projection, GameRenderer.getPositionTexShader());
            return;
        }
        buffer.drawWithShader(pose, projection, shader);
    }

    @Redirect(method = "renderSky", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"))
    private float visuals$starBrightness(ClientLevel level, float partialTick) {
        return StarSky.drawValue(level.getStarBrightness(partialTick));
    }
}
