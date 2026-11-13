package net.vulkanmod.visuals.particle;

/**
 * 2026-11-13 (session 13 follow-up, the user: "the realistic particles look low-effort: use real images, as the moon does"): the realistic
 * particles' image tiles in the particle atlas (visuals/textures/particles/particle_atlas.png, baked by tools/particles/bake_particle_atlas.py from
 * CC0 images: Unity's free VFX flipbooks, Kenney's Particle Pack and Smoke Particles; THIRD_PARTY_NOTICES.md). Keep in step with the bake.
 *
 * <p>The atlas is ATLAS_WIDTH x ATLAS_HEIGHT, tiles of TILE texels: rows 0..3 the FLAME_FRAMES flame frames (a looping flipbook), row 4
 * PUFFS smoke puffs, row 5 the single sprites (GLOW .. MAGIC). Which tile a particle draws (VanillaParticles.Shape):
 * <ul>
 * <li>FLAME: the flipbook at FLAME_FPS from a frame its seed picks, by the particle's age</li>
 * <li>PUFF: one of the puffs by its seed, mirrored for half the seeds</li>
 * <li>EMBER: EMBER (a hot dot), DROP: DROP, BUBBLE: RING, ORB: GLOW, MOTE: GLOW, PETAL: PETAL, GLINT: STAR (crit's: SPARKLE for half)</li>
 * </ul>
 * FLAME, EMBER, ORB and GLINT are light (drawn adding); the others cover what is behind them (DeferredParticles).
 */
public final class ParticleSprites {
    private ParticleSprites() {
    }

    public static final int ATLAS_WIDTH = 1024, ATLAS_HEIGHT = 768, TILE = 128, COLS = 8;
    public static final int FLAME_FRAMES = 32, PUFFS = 8;
    /** The flipbook's frames a second (Flame03 is a 64-frame loop at about 30; every other frame is kept). */
    public static final float FLAME_FPS = 15f;
    /** Row 5's tiles. */
    public static final int GLOW = 0, EMBER = 1, STAR = 2, SPARKLE = 3, RING = 4, DROP = 5, PETAL = 6, MAGIC = 7;
    private static final int SPRITE_ROW = 5, PUFF_ROW = 4;

    /** The tile's index (row * COLS + column) a particle of shape draws, with its seed and age (ticks, with the partial tick). */
    public static int tile(int shape, int seed, float ageTicks) {
        VanillaParticles.Shape[] shapes = VanillaParticles.Shape.values();
        VanillaParticles.Shape s = shape >= 0 && shape < shapes.length ? shapes[shape] : VanillaParticles.Shape.SPRITE;
        int h = seed & 0x7FFFFFFF;
        return switch (s) {
            case FLAME -> (int) (Math.floorMod((long) Math.floor(ageTicks / 20f * FLAME_FPS) + h, FLAME_FRAMES));
            case PUFF -> PUFF_ROW * COLS + h % PUFFS;
            case EMBER -> SPRITE_ROW * COLS + EMBER;
            case DROP -> SPRITE_ROW * COLS + DROP;
            case BUBBLE -> SPRITE_ROW * COLS + RING;
            case PETAL -> SPRITE_ROW * COLS + PETAL;
            case GLINT -> SPRITE_ROW * COLS + ((h & 1) == 0 ? STAR : SPARKLE);
            case ORB, MOTE -> SPRITE_ROW * COLS + GLOW;
            default -> SPRITE_ROW * COLS + GLOW;
        };
    }

    /** Whether shape is drawn as light (added) rather than over what is behind it. */
    public static boolean additive(int shape) {
        VanillaParticles.Shape[] shapes = VanillaParticles.Shape.values();
        if (shape <= 0 || shape >= shapes.length) return false;
        return switch (shapes[shape]) {
            case FLAME, EMBER, ORB, GLINT -> true;
            default -> false;
        };
    }

    /** Whether the tile is mirrored left to right for this seed (the puffs: twice the variety). */
    public static boolean mirrored(int shape, int seed) {
        return shape == VanillaParticles.Shape.PUFF.ordinal() && ((seed >> 4) & 1) != 0;
    }

    /** The tile's u0, u1, v0, v1 in the atlas (0..1). */
    public static float[] uv(int tile) {
        int col = tile % COLS, row = tile / COLS;
        float u0 = col * TILE / (float) ATLAS_WIDTH, u1 = (col + 1) * TILE / (float) ATLAS_WIDTH;
        float v0 = row * TILE / (float) ATLAS_HEIGHT, v1 = (row + 1) * TILE / (float) ATLAS_HEIGHT;
        return new float[]{u0, u1, v0, v1};
    }
}
