package net.vulkanmod.mixin.visuals;

import net.minecraft.client.particle.Particle;
import net.vulkanmod.visuals.particle.ParticleEffectTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 2026-11-11 (shader roadmap session 13): the look tag every particle carries (0 until the particle engine sets it). */
@Mixin(Particle.class)
public abstract class ParticleTagMixin implements ParticleEffectTag {
    @Unique
    private int vulkanmod$effectTag;

    @Override
    public int vulkanmod$effectTag() {
        return this.vulkanmod$effectTag;
    }

    @Override
    public void vulkanmod$setEffectTag(int tag) {
        this.vulkanmod$effectTag = tag;
    }
}
