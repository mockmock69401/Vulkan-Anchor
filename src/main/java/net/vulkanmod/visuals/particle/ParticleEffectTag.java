package net.vulkanmod.visuals.particle;

/** 2026-11-11 (session 13): a particle's look tag (VanillaParticles.tag), set when the particle engine makes it (ParticleTagMixin). */
public interface ParticleEffectTag {
    int vulkanmod$effectTag();

    void vulkanmod$setEffectTag(int tag);
}
