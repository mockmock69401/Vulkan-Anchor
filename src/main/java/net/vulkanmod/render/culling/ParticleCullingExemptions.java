package net.vulkanmod.render.culling;

/**
 * 2026-10-28 (DeceasedCraft: no rain or snow): particles the add-time particle culling (ParticleEngineMixin) leaves alone. Weather
 * particle mods spawn their rain and snow around the camera and keep their own limit (Pretty Rain / Particle Rain: maxParticleAmount,
 * 1500 by default); the culling's view cone, distance and per-class cap of 24..128 dropped nearly all of them. Pure.
 */
public final class ParticleCullingExemptions {
    /** Packages whose particles are never culled: Pretty Rain (Forge port) and Particle Rain. */
    private static final String[] PACKAGES = {"com.leclowndu93150.particlerain.", "pigcart.particlerain."};

    private ParticleCullingExemptions() {
    }

    public static boolean exempt(Class<?> particleClass) {
        return exempt(particleClass.getName());
    }

    public static boolean exempt(String className) {
        for (String p : PACKAGES) {
            if (className.startsWith(p)) return true;
        }
        return false;
    }
}
