package net.vulkanmod.mixin.render;

import net.vulkanmod.compat.gl.VanillaGlState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Vanilla's blend record (GlStateManager.BLEND) follows VulkanMod's blend factors: see {@link VanillaGlState}. The fields are found by
 * name, the development name or the SRG one (a {@code @Shadow} on this private nested class is not remapped: the game would not start).
 * This code runs as BlendState's own, so its fields need no access change.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$BlendState")
public abstract class GlBlendStateM implements VanillaGlState.BlendRecord {
    @Unique
    private static final String[][] VULKANMOD$NAMES = {{"srcRgb", "f_84578_"}, {"dstRgb", "f_84579_"}, {"srcAlpha", "f_84580_"}, {"dstAlpha", "f_84581_"}};
    @Unique
    private Field[] vulkanmod$factors;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanmod$register(CallbackInfo ci) {
        try {
            Field[] fields = new Field[4];
            for (int i = 0; i < 4; i++) fields[i] = VanillaGlState.field(((Object) this).getClass(), VULKANMOD$NAMES[i]);
            this.vulkanmod$factors = fields;
            VanillaGlState.registerBlend(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VanillaGlState.failed("GlStateManager.BlendState", e);
        }
    }

    @Override
    public void vulkanmod$setFactors(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) throws IllegalAccessException {
        Field[] f = this.vulkanmod$factors;
        f[0].setInt(this, srcRgb);
        f[1].setInt(this, dstRgb);
        f[2].setInt(this, srcAlpha);
        f[3].setInt(this, dstAlpha);
    }

    @Override
    public void vulkanmod$setRgbFactors(int srcRgb, int dstRgb) throws IllegalAccessException {
        Field[] f = this.vulkanmod$factors;
        f[0].setInt(this, srcRgb);
        f[1].setInt(this, dstRgb);
    }
}
