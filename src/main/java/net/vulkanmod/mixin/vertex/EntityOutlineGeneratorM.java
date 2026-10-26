package net.vulkanmod.mixin.vertex;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OutlineBufferSource.EntityOutlineGenerator.class)
public class EntityOutlineGeneratorM implements ExtendedVertexBuilder {

    @Unique
    private ExtendedVertexBuilder extDelegate;

    @Unique
    private boolean canUseFastVertex = false;

    @Unique
    private int vulkanmod$outlineColor;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void getExtBuilder(VertexConsumer vertexConsumer, int r, int g, int b, int a, CallbackInfo ci) {
        if (vertexConsumer instanceof ExtendedVertexBuilder) {
            this.extDelegate = (ExtendedVertexBuilder) vertexConsumer;
            this.canUseFastVertex = true;
        }

        //2026-10-26: 1.20.1's generator has no color field (1.21 added it); the shadow kept this mixin from applying and the game
        // crashed the first time an outlined (glowing) entity was drawn. The colour comes from the constructor's r, g, b, a.
        this.vulkanmod$outlineColor = (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }

    @Override
    public boolean canUseFastVertex() {
        return this.canUseFastVertex && this.extDelegate != null;
    }

    @Override
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        this.extDelegate.vertex(x, y, z, this.vulkanmod$outlineColor, u, v, overlay, light, packedNormal);
    }
}

