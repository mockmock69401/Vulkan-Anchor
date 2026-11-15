package net.vulkanmod.mixin.render;

import net.vulkanmod.compat.gl.VanillaGlState;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Vanilla's GL_BLEND on/off record follows VulkanMod's: see {@link VanillaGlState}. Other capabilities are left alone. The field is found
 * by name (development or SRG), as in {@link GlBlendStateM}.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$BooleanState")
public abstract class GlBooleanStateM implements VanillaGlState.CapRecord {
    @Unique
    private Field vulkanmod$enabled;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanmod$register(int cap, CallbackInfo ci) {
        if (cap != GL11.GL_BLEND) return;
        try {
            this.vulkanmod$enabled = VanillaGlState.field(((Object) this).getClass(), new String[]{"enabled", "f_84586_"});
            VanillaGlState.registerBlendCap(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VanillaGlState.failed("GlStateManager.BooleanState", e);
        }
    }

    @Override
    public void vulkanmod$setEnabled(boolean enabled) throws IllegalAccessException {
        this.vulkanmod$enabled.setBoolean(this, enabled);
    }
}
