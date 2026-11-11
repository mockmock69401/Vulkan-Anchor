package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ParticleEffect;
import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.Arrays;

/**
 * 2026-11-11: the particle effects' look settings as the shaders take them (ParticleEffect's type, size, amount and brightness per
 * effect), clamped to the settings' ranges: the type an index, the others ratios (1 = 100%, the tuned look). Equal values are equal objects (the
 * post-process compares its settings). Uniforms only: changing them compiles nothing.
 */
public final class ParticleStyles {
    public static final ParticleStyles DEFAULT = new ParticleStyles(new int[ParticleEffect.values().length], filled(1), filled(1), filled(1));

    private final int[] types;
    private final float[] sizes, amounts, brightness;

    private ParticleStyles(int[] types, float[] sizes, float[] amounts, float[] brightness) {
        this.types = types;
        this.sizes = sizes;
        this.amounts = amounts;
        this.brightness = brightness;
    }

    private static float[] filled(float v) {
        float[] a = new float[ParticleEffect.values().length];
        Arrays.fill(a, v);
        return a;
    }

    public static ParticleStyles of(VisualsConfig c) {
        int n = ParticleEffect.values().length;
        int[] types = new int[n];
        float[] sizes = new float[n], amounts = new float[n], brightness = new float[n];
        for (ParticleEffect e : ParticleEffect.values()) {
            int i = e.ordinal();
            types[i] = Math.max(0, Math.min(e.typeMax(), e.get(c, ParticleEffect.Part.TYPE)));
            sizes[i] = Math.max(ParticleEffect.SIZE_MIN, Math.min(ParticleEffect.SIZE_MAX, e.get(c, ParticleEffect.Part.SIZE))) / 100f;
            amounts[i] = Math.max(0, Math.min(ParticleEffect.AMOUNT_MAX, e.get(c, ParticleEffect.Part.AMOUNT))) / 100f;
            brightness[i] = Math.max(0, Math.min(ParticleEffect.BRIGHTNESS_MAX, e.get(c, ParticleEffect.Part.BRIGHTNESS))) / 100f;
        }
        ParticleStyles s = new ParticleStyles(types, sizes, amounts, brightness);
        return s.equals(DEFAULT) ? DEFAULT : s;
    }

    public int type(ParticleEffect e) {
        return this.types[e.ordinal()];
    }

    public float size(ParticleEffect e) {
        return this.sizes[e.ordinal()];
    }

    public float amount(ParticleEffect e) {
        return this.amounts[e.ordinal()];
    }

    /** The effect's brightness: its colour times this (1 = the colour as picked). */
    public float brightness(ParticleEffect e) {
        return this.brightness[e.ordinal()];
    }

    /** For the settings' log line. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        for (ParticleEffect e : ParticleEffect.values()) {
            if (b.length() > 0) b.append(", ");
            b.append(e.key()).append(' ').append(this.type(e)).append('/').append(Math.round(this.size(e) * 100)).append("%/")
                    .append(Math.round(this.amount(e) * 100)).append("%/").append(Math.round(this.brightness(e) * 100)).append('%');
        }
        return b.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParticleStyles s && Arrays.equals(this.types, s.types) && Arrays.equals(this.sizes, s.sizes) && Arrays.equals(this.amounts, s.amounts)
                && Arrays.equals(this.brightness, s.brightness);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Arrays.hashCode(this.types) + Arrays.hashCode(this.sizes)) + Arrays.hashCode(this.amounts)) + Arrays.hashCode(this.brightness);
    }
}
