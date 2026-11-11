package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.ShaderColours.Colour;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 2026-11-11 (the user: "the snow and rain particles' type (round, pixel, both), size, amount and colour", then "a brightness option too"):
 * a particle effect's look
 * options, one shared pattern for every effect (the vanilla particle effect options reuse it). Each effect has
 * <ul>
 * <li>four VisualsConfig int fields named prefix + "Type", prefix + "Size", prefix + "Amount", prefix + "Brightness" (found by name: an
 * effect is one constant here, its four fields and its strings), shown as the option layout items key + "_type" (a choice of typeMax + 1
 * entries, labelled vulkanmod.visuals.KEY_type.N), key + "_size", key + "_amount" and key + "_brightness" (percent, 100 = the tuned
 * look, the defaults; the brightness multiplies the effect's colour and is apart from it);</li>
 * <li>a ShaderColours colour (its picker is on the colours page, set by the palettes);</li>
 * <li>the switch its items depend on (requires).</li>
 * </ul>
 * The values reach the shaders as uniforms (PostProcessSettings.particles: ParticleStyles), so changing them compiles nothing. They are
 * look settings: the performance presets never set them (VisualsPresetsTest); an amount that costs GPU time says so in its tooltip.
 */
public enum ParticleEffect {
    /** Session 6's shader snow: 0 round flakes, 1 pixel flakes, 2 both (each flake one or the other). */
    SNOW("snow", "snow", 2, Colour.SNOW, "shader_weather"),
    /** Session 6's shader rain: 0 round streaks, 1 pixel drops, 2 both. */
    RAIN("rain", "rain", 2, Colour.RAIN, "shader_weather");

    /** The parts of an effect, by the option layout key's suffix and the config field's. */
    public enum Part {
        TYPE("_type", "Type"), SIZE("_size", "Size"), AMOUNT("_amount", "Amount"), BRIGHTNESS("_brightness", "Brightness");

        final String keySuffix, fieldSuffix;

        Part(String keySuffix, String fieldSuffix) {
            this.keySuffix = keySuffix;
            this.fieldSuffix = fieldSuffix;
        }
    }

    /** The size, amount and brightness ranges (percent) every effect shares; 100 is the tuned look, the defaults. */
    public static final int SIZE_MIN = 25, SIZE_MAX = 300, AMOUNT_MAX = 200, BRIGHTNESS_MAX = 200, DEFAULT_PERCENT = 100;

    private final String key, fieldPrefix, requires;
    private final int typeMax;
    private final Colour colour;
    private final Field[] fields = new Field[Part.values().length];

    ParticleEffect(String key, String fieldPrefix, int typeMax, Colour colour, String requires) {
        this.key = key;
        this.fieldPrefix = fieldPrefix;
        this.typeMax = typeMax;
        this.colour = colour;
        this.requires = requires;
    }

    public String key() {
        return this.key;
    }

    /** The highest type (the choice runs 0..typeMax; 0 is the default). */
    public int typeMax() {
        return this.typeMax;
    }

    public Colour colour() {
        return this.colour;
    }

    public String requires() {
        return this.requires;
    }

    public String itemKey(Part part) {
        return this.key + part.keySuffix;
    }

    /** The effect's rows for a block of the option layout: type, size, amount, brightness (each greyed out without requires). */
    public List<VisualsOptionLayout.Item> items(VisualsOptionLayout.Impact amountImpact) {
        return List.of(new VisualsOptionLayout.Item(this.itemKey(Part.TYPE), VisualsOptionLayout.Kind.CHOICE, 0, this.typeMax, VisualsOptionLayout.Impact.NONE, this.requires),
                new VisualsOptionLayout.Item(this.itemKey(Part.SIZE), VisualsOptionLayout.Kind.PERCENT, SIZE_MIN, SIZE_MAX, VisualsOptionLayout.Impact.NONE, this.requires),
                new VisualsOptionLayout.Item(this.itemKey(Part.AMOUNT), VisualsOptionLayout.Kind.PERCENT, 0, AMOUNT_MAX, amountImpact, this.requires),
                new VisualsOptionLayout.Item(this.itemKey(Part.BRIGHTNESS), VisualsOptionLayout.Kind.PERCENT, 0, BRIGHTNESS_MAX, VisualsOptionLayout.Impact.NONE, this.requires));
    }

    /** The VisualsConfig value of a part (unclamped). */
    public int get(VisualsConfig c, Part part) {
        try {
            return this.field(part).getInt(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public void set(VisualsConfig c, Part part, int value) {
        try {
            this.field(part).setInt(c, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private Field field(Part part) {
        Field f = this.fields[part.ordinal()];
        if (f == null) {
            try {
                f = VisualsConfig.class.getField(this.fieldPrefix + part.fieldSuffix);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("VisualsConfig has no " + this.fieldPrefix + part.fieldSuffix, e);
            }
            this.fields[part.ordinal()] = f;
        }
        return f;
    }

    /** The effect and part behind an option layout key, or null. */
    public record Target(ParticleEffect effect, Part part) {
    }

    public static Target byItemKey(String itemKey) {
        for (ParticleEffect e : values()) {
            for (Part p : Part.values()) if (e.itemKey(p).equals(itemKey)) return new Target(e, p);
        }
        return null;
    }
}
