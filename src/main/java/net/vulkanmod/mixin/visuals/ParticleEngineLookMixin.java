package net.vulkanmod.mixin.visuals;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.vulkanmod.visuals.particle.ParticleEffectTag;
import net.vulkanmod.visuals.particle.ParticleLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 2026-11-11 (shader roadmap session 13): the particle look options in the particle engine. Every particle it makes is tagged with its group
 * (ParticleLook.tagOf); a spawn makes as many particles as its group's amount says (0: the particle is made, so a caller that sets it up
 * still gets one, but never added; copies stand a little apart). Only client particles: the server, the vanilla particle setting and the
 * level's random are untouched. A realistic particle stays on its own sheet (the shapes are solid with a one-pixel rim: particle.fsh particleEdge). The settings
 * are read on each particle tick and each particle draw.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineLookMixin {
    //createParticle calling itself for the amount: the inner calls take the vanilla path; with dropAdd the made particle is not added.
    // Only public methods are called (Forge's ParticleEngine has makeParticle under another shape at run time)
    @Unique
    private static boolean visuals$nested, visuals$dropAdd;

    @Inject(method = "createParticle", at = @At("RETURN"))
    private void visuals$tag(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        Particle p = cir.getReturnValue();
        if (p != null) ((ParticleEffectTag) p).vulkanmod$setEffectTag(ParticleLook.tagOf(options.getType()));
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void visuals$amount(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        if (visuals$nested || !ParticleLook.active()) return;
        int n = ParticleLook.copies(ParticleLook.tagOf(options.getType()));
        if (n == 1) return;
        Particle p;
        visuals$nested = true;
        try {
            visuals$dropAdd = n == 0;
            p = ((ParticleEngine) (Object) this).createParticle(options, x, y, z, dx, dy, dz);
            visuals$dropAdd = false;
            for (int i = 1; i < n; i++) ((ParticleEngine) (Object) this).createParticle(options, x + ParticleLook.copyOffset(), y, z + ParticleLook.copyOffset(), dx, dy, dz);
        } finally {
            visuals$nested = false;
            visuals$dropAdd = false;
        }
        cir.setReturnValue(p);
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void visuals$drop(Particle particle, CallbackInfo ci) {
        if (visuals$dropAdd) ci.cancel();
    }

    //Forge adds an overload of render (with a frustum) that the level calls: the settings are read on the tick and when the tints are bound
    // (ParticleLook.tints), not from render
    @Inject(method = "tick", at = @At("HEAD"))
    private void visuals$refresh(CallbackInfo ci) {
        ParticleLook.refresh();
    }
}
