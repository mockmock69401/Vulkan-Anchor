package net.vulkanmod.visuals.particle;

import net.vulkanmod.visuals.config.ParticleEffect;

/**
 * 2026-11-11 (shader roadmap session 13): the particle look options' arithmetic, without the game: how a quad tells particle.vsh its shape
 * and group, the amount's copies and the realistic quad's size. particle.vsh / particle.fsh mirror the encoding (the constants' names).
 *
 * <p>The packed light (LightTexture.pack: block light in the low 16 bits, sky light in the high, 0..240 each) reaches the shader as UV2, two
 * shorts; bits 8..11 of each are free. PARTICLE_SHAPE_SHIFT puts the realistic shape (0 = the sprite) in the block half, PARTICLE_GROUP_SHIFT
 * the tint group (0 = no tint) in the sky half. A quad with neither carries the light unchanged, and the shader masks both halves to 8
 * bits before it reads the lightmap, so the default draws bit for bit as before.
 */
public final class ParticleLookMath {
    private ParticleLookMath() {
    }

    public static final int PARTICLE_SHAPE_SHIFT = 8, PARTICLE_GROUP_SHIFT = 16 + 8;
    /** The light's own bits in each half. */
    public static final int LIGHT_MASK = 0x00FF00FF;

    /** The type setting: vanilla sprites or the realistic shapes. */
    public static final int TYPE_VANILLA = 0, TYPE_REALISTIC = 1, TYPE_MAX = 1;

    /** Size and amount in percent: the settings' ranges (100 = vanilla). */
    public static final int SIZE_MIN = ParticleEffect.SIZE_MIN, SIZE_MAX = ParticleEffect.SIZE_MAX, AMOUNT_MAX = ParticleEffect.AMOUNT_MAX;
    /**
     * Brightness in percent (100 = vanilla): it multiplies the group's colour tint, so it scales the particle's colour and, for the glowing
     * ones (flames, embers, end rods, portals: full block light), their glow and bloom with it; apart from the colour, so a palette's hue
     * can be dimmed or boosted.
     */
    public static final int BRIGHTNESS_MAX = ParticleEffect.BRIGHTNESS_MAX;

    /** The tint with the brightness on it (exactly the tint at 100). */
    public static float[] brightTint(float[] tint, int brightnessPercent) {
        if (brightnessPercent == 100) return tint;
        float b = Math.max(0, Math.min(BRIGHTNESS_MAX, brightnessPercent)) / 100f;
        return new float[]{tint[0] * b, tint[1] * b, tint[2] * b};
    }
    /** Amount above 100: copies are thrown this far (blocks, each horizontal axis) from the original, so they do not sit on it. */
    public static final float COPY_SPREAD = 0.3f;

    /** The light with the shape (0..15) and the tint group (0..15) on it. */
    public static int light(int light, int shape, int group) {
        return (light & LIGHT_MASK) | ((shape & 0xF) << PARTICLE_SHAPE_SHIFT) | ((group & 0xF) << PARTICLE_GROUP_SHIFT);
    }

    /** particle.vsh: the light's levels without the flags. */
    public static int lightLevels(int encoded) {
        return encoded & LIGHT_MASK;
    }

    public static int shapeOf(int encoded) {
        return (encoded >> PARTICLE_SHAPE_SHIFT) & 0xF;
    }

    public static int groupOf(int encoded) {
        return (encoded >>> PARTICLE_GROUP_SHIFT) & 0xF;
    }

    /** Realistic quads carry the particle's seed (0..SEED_COUNT-1) in u: u = seed * 2 + corner (0 or 1); particle.vsh takes them apart. */
    public static final int SEED_COUNT = 1024;

    public static float seedU(int seed, int corner) {
        return Math.floorMod(seed, SEED_COUNT) * 2f + corner;
    }

    /** particle.vsh: the seed of u (any corner). */
    public static int seedOf(float u) {
        return (int) Math.floor(u * 0.5f + 0.25f);
    }

    /** particle.vsh: the corner of u (0 or 1). */
    public static float cornerOf(float u) {
        return u - 2f * seedOf(u);
    }

    /**
     * How many particles one spawn makes at amount percent: amount / 100 whole, the rest by chance (r in [0, 1)). 0 drops the original;
     * 1 is vanilla.
     */
    public static int copies(int amountPercent, float r) {
        int a = Math.max(0, Math.min(AMOUNT_MAX, amountPercent));
        int whole = a / 100;
        float rest = (a % 100) / 100f;
        return whole + (r < rest ? 1 : 0);
    }

    /** The size option as a factor on the quad's half size (100 = 1 exactly). */
    public static float sizeScale(int sizePercent) {
        if (sizePercent == 100) return 1f;
        return Math.max(SIZE_MIN, Math.min(SIZE_MAX, sizePercent)) / 100f;
    }

    /**
     * The realistic quad's half size: the shape is drawn so it covers about the area the sprite covered (the share of the sprite's texels
     * drawn, alpha-weighted: smoke's frames shrink as it fades, so does its puff): half size x sqrt(coverage / the shape's share of its
     * quad, SHAPE_AREA), bounded by MIN_GROWTH and MAX_GROWTH.
     */
    public static final float MAX_GROWTH = 1.8f, MIN_GROWTH = 0.35f;
    /** Each shape's share of its quad (particle.fsh realisticShape: pi r^2 / 4 for its radius r), by VanillaParticles.Shape ordinal. */
    public static final float[] SHAPE_AREA = {1f, 0.45f, 0.3f, 0.24f, 0.28f, 0.28f, 0.16f, 0.2f, 0.33f, 0.12f};
    /** The share assumed for a shape not in SHAPE_AREA. */
    public static final float SHAPE_COVER = 0.3f;

    public static float realisticSize(float halfSize, float coverage, int shape) {
        if (!(coverage > 0)) return halfSize;
        float area = shape > 0 && shape < SHAPE_AREA.length ? SHAPE_AREA[shape] : SHAPE_COVER;
        float g = (float) Math.sqrt(coverage / area);
        return halfSize * Math.max(MIN_GROWTH, Math.min(MAX_GROWTH, g));
    }
}
