package net.vulkanmod.mixin.visuals;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.vulkanmod.visuals.camera.StepSmoothing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Session 22: the step smoothing (StepSmoothing) on the camera's height. Camera.setup places the camera at the entity's interpolated
 * eye with setPosition(x, y, z) before the third-person pull-back; the offset is added to that y. The entity, its hitbox and the
 * crosshair's pick (GameRenderer.pick, from the entity's own eye) are untouched.
 */
@Mixin(Camera.class)
public abstract class CameraStepSmoothMixin {
    @Inject(method = "setup", at = @At("HEAD"))
    private void visuals$stepSetup(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        StepSmoothing.setup(level, entity, partialTick);
    }

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V", ordinal = 0), index = 1)
    private double visuals$stepHeight(double y) {
        return y + StepSmoothing.offset();
    }
}
