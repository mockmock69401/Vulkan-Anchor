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
     * 2026-11-13 (session 13 follow-up, the user: "the realistic particles look low-effort: use real images, as the moon does"): the
     * realistic quad's half size. Each shape is an image tile of the particle atlas (ParticleSprites) drawn at SHAPE_SCALE times the
     * vanilla quad (the images leave transparent room around them: a flame is a tall narrow tongue in its square tile, a glow a soft
     * dot); a smoke puff also follows the share of its vanilla frame drawn (coverage, alpha-weighted: smoke's frames shrink as it fades,
     * so does the puff), sqrt(coverage / PUFF_COVER) bounded by MIN_GROWTH and MAX_GROWTH.
     */
    public static final float MAX_GROWTH = 1.6f, MIN_GROWTH = 0.35f;
    /** Each shape's scale on the vanilla quad, by VanillaParticles.Shape ordinal (tuned in game against the vanilla sprites). */
    public static final float[] SHAPE_SCALE = {1f, 1.25f, 1.35f, 1.8f, 1.15f, 1.0f, 1.6f, 1.3f, 1.0f, 1.8f};
    /** A vanilla smoke frame's coverage at which the puff has its SHAPE_SCALE size (about the largest frame's). */
    public static final float PUFF_COVER = 0.35f;
    /** The coverage assumed when a sprite cannot be read. */
    public static final float SHAPE_COVER = PUFF_COVER;

    public static float realisticSize(float halfSize, float coverage, int shape) {
        float scale = shape > 0 && shape < SHAPE_SCALE.length ? SHAPE_SCALE[shape] : 1f;
        if (shape == 1 && coverage > 0) {
            scale *= Math.max(MIN_GROWTH, Math.min(MAX_GROWTH, (float) Math.sqrt(coverage / PUFF_COVER)));
        }
        return halfSize * scale;
    }
}
