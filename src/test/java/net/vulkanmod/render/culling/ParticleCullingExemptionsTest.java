package net.vulkanmod.render.culling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleCullingExemptionsTest {
    @Test
    void weatherParticleModsAreNeverCulled() {
        //2026-10-28 (DeceasedCraft: Pretty Rain's rain and snow were culled at spawn to almost nothing)
        assertTrue(ParticleCullingExemptions.exempt("com.leclowndu93150.particlerain.particle.RainParticle"));
        assertTrue(ParticleCullingExemptions.exempt("com.leclowndu93150.particlerain.particle.SnowParticle"));
        assertTrue(ParticleCullingExemptions.exempt("pigcart.particlerain.particle.RainParticle"));
        //everything else keeps the culling
        assertFalse(ParticleCullingExemptions.exempt("net.minecraft.client.particle.SmokeParticle"));
        assertFalse(ParticleCullingExemptions.exempt("com.leclowndu93150.otherparticles.Rain"));
        assertFalse(ParticleCullingExemptions.exempt(Object.class));
    }
}
