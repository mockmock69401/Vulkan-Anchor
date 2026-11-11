package net.vulkanmod.visuals.particle;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.vulkanmod.visuals.config.ParticleEffect;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 2026-11-11 (shader roadmap session 13): the particle look options at run time. The particle engine tags each particle it makes with its
 * group ({@link #tagOf}), drops or copies spawns by the group's amount ({@link #copies}), and the quad a particle draws (SingleQuadParticleM)
 * takes the group's size, its realistic shape and its colour tint from here.
 *
 * <p>The settings are read on each particle tick and particle draw ({@link #refresh}) into a snapshot. While every group is at its
 * default (vanilla type, 100% size, amount and brightness, the default colour) or the visuals are off, {@link #active} is false and nothing here touches
 * a particle: the picture is the vanilla one bit for bit (ParticleLookMath's light flags stay 0).
 */
public final class ParticleLook {
    private ParticleLook() {
    }

    private static final VanillaParticles.Group[] GROUPS = VanillaParticles.Group.values();


    private static volatile boolean active;
    private static final boolean[] REALISTIC = new boolean[GROUPS.length];
    private static final boolean[] TINTED = new boolean[GROUPS.length];
    private static final float[] SIZE = new float[GROUPS.length];
    private static final int[] AMOUNT = new int[GROUPS.length];
    //particle.fsh ParticleTints (groups 1..4 in columns 0..3) and ParticleTints2 (5..7)
    private static final MappedBuffer TINTS = new MappedBuffer(16 * 4), TINTS2 = new MappedBuffer(16 * 4);
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<ParticleType<?>, Integer> TAGS = new IdentityHashMap<>();
    private static final Map<Object, float[]> SPRITES = new WeakHashMap<>();

    static {
        for (int i = 0; i < 16; i++) {
            TINTS.putFloat(i * 4, 1f);
            TINTS2.putFloat(i * 4, 1f);
        }
        java.util.Arrays.fill(SIZE, 1f);
        java.util.Arrays.fill(AMOUNT, 100);
    }

    /** Reads the settings (once a frame; cheap). */
    public static void refresh() {
        VisualsConfig c = VisualsConfig.get();
        if (c == null || !c.enabled) {
            active = false;
            return;
        }
        ShaderColours colours = ShaderColours.of(c.colours());
        boolean any = false;
        for (int g = 0; g < GROUPS.length; g++) {
            REALISTIC[g] = GROUPS[g].effect().get(c, ParticleEffect.Part.TYPE) == ParticleLookMath.TYPE_REALISTIC;
            SIZE[g] = ParticleLookMath.sizeScale(GROUPS[g].effect().get(c, ParticleEffect.Part.SIZE));
            AMOUNT[g] = Math.max(0, Math.min(ParticleLookMath.AMOUNT_MAX, GROUPS[g].effect().get(c, ParticleEffect.Part.AMOUNT)));
            float[] t = ParticleLookMath.brightTint(colours.tint(GROUPS[g].effect().colour()), GROUPS[g].effect().get(c, ParticleEffect.Part.BRIGHTNESS));
            TINTED[g] = t[0] != 1f || t[1] != 1f || t[2] != 1f;
            MappedBuffer b = g < 4 ? TINTS : TINTS2;
            int col = (g % 4) * 16;
            b.putFloat(col, t[0]);
            b.putFloat(col + 4, t[1]);
            b.putFloat(col + 8, t[2]);
            b.putFloat(col + 12, 1f);
            any |= REALISTIC[g] || TINTED[g] || SIZE[g] != 1f || AMOUNT[g] != 100;
        }
        active = any;
    }

    /** Whether any group is off its default (else the particles draw as vanilla's). */
    public static boolean active() {
        return active;
    }

    /** particle.fsh's ParticleTints, once per particle draw: the settings are read here too, so a change shows while the game is paused. */
    public static MappedBuffer tints() {
        refresh();
        return TINTS;
    }

    public static MappedBuffer tints2() {
        return TINTS2;
    }

    /** The tag (VanillaParticles.tag) of particles of this type. */
    public static int tagOf(ParticleType<?> type) {
        synchronized (TAGS) {
            Integer t = TAGS.get(type);
            if (t == null) {
                ResourceLocation id = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
                t = id == null ? 0 : VanillaParticles.tag(id.toString());
                TAGS.put(type, t);
            }
            return t;
        }
    }

    private static int group(int tag) {
        return (tag & VanillaParticles.GROUP_MASK) - 1;
    }

    /** How many particles a spawn of this tag makes (1 = vanilla). */
    public static int copies(int tag) {
        if (!active || tag == 0) return 1;
        int a = AMOUNT[group(tag)];
        return a == 100 ? 1 : ParticleLookMath.copies(a, RANDOM.nextFloat());
    }

    /** A copy's offset from the original (blocks, one horizontal axis). */
    public static double copyOffset() {
        return (RANDOM.nextDouble() * 2 - 1) * ParticleLookMath.COPY_SPREAD;
    }

    /** The size factor of a tagged particle's quad. */
    public static float sizeScale(int tag) {
        return active && tag != 0 ? SIZE[group(tag)] : 1f;
    }

    /** The realistic shape a tagged particle draws now (0: its sprite). */
    public static int realisticShape(int tag) {
        if (!active || tag == 0 || !REALISTIC[group(tag)]) return 0;
        return VanillaParticles.shape(tag).ordinal();
    }

    /** The tint group particle.fsh multiplies by (0: none). */
    public static int tintGroup(int tag) {
        return active && tag != 0 && TINTED[group(tag)] ? group(tag) + 1 : 0;
    }

    /**
     * A sprite's mean colour and coverage: {r, g, b, coverage}, the colour the alpha-weighted mean of its drawn texels (alpha at least 0.1,
     * as particle.fsh draws them), the coverage their alpha's sum over the texel count. Kept per sprite contents (dropped with the atlas).
     */
    public static float[] spriteStats(Object contents, NativeImage image) {
        synchronized (SPRITES) {
            float[] s = SPRITES.get(contents);
            if (s == null) {
                s = measure(image);
                SPRITES.put(contents, s);
            }
            return s;
        }
    }

    private static float[] measure(NativeImage image) {
        if (image == null) return new float[]{1, 1, 1, ParticleLookMath.SHAPE_COVER};
        int w = image.getWidth(), h = image.getHeight();
        double r = 0, g = 0, b = 0, a = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                float al = ((abgr >>> 24) & 0xFF) / 255f;
                if (al < 0.1f) continue;
                r += (abgr & 0xFF) / 255f * al;
                g += ((abgr >> 8) & 0xFF) / 255f * al;
                b += ((abgr >> 16) & 0xFF) / 255f * al;
                a += al;
            }
        }
        if (a <= 0) return new float[]{1, 1, 1, ParticleLookMath.SHAPE_COVER};
        return new float[]{(float) (r / a), (float) (g / a), (float) (b / a), (float) (a / (w * (double) h))};
    }
}
