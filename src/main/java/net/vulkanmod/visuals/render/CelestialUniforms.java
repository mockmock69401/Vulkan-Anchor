package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * 2026-11-09 (session 5, the user: round sun and moon, auroras at night and in the End): the sky's two extra slots.
 * <ul>
 * <li>CELESTIAL (composite 46, reflection 27): water.glsl's discs' shape: x the shape (SHAPE_SQUARE vanilla's squares, as before;
 * SHAPE_ROUND), y vanilla's moon phase 0..7 (Level.getMoonPhase), zw 0</li>
 * <li>AURORA (composite 47, reflection 28): aurora.glsl's parameters: x the strength (0: none, and the shaders evaluate nothing), y the
 * time in seconds wrapped at AuroraMath.AURORA_TIME_WRAP, z 1 in the End, w the jitter phase (turning by the golden ratio each frame with
 * TAA, else 0)</li>
 * </ul>
 * The aurora's strength is the setting's (aurora strength x the mode) times, in a dimension with a sun (the Overworld), the night (none
 * while the sun is above NIGHT_HIGH, full below NIGHT_LOW), the weather (only clear skies: gone once the rain level reaches RAIN_FADE,
 * 2026-11-10 at the user's word, snow too since it is the same level; a thunderstorm 1 - THUNDER_CUT more:
 * the overcast hides it) and the climate (cold biomes full, warm ones WARM_SHARE: auroras belong to high latitudes); in the End
 * always END_SHARE; nowhere else (the Nether).
 */
public final class CelestialUniforms {
    public static final int COPY_CELESTIAL = 46, COPY_AURORA = 47, REFLECTION_CELESTIAL = 27, REFLECTION_AURORA = 28;
    /** The celestial shape setting's values. */
    public static final int SHAPE_SQUARE = 0, SHAPE_ROUND = 1, SHAPE_MAX = 1;
    /** The aurora setting's values. */
    public static final int AURORA_OFF = 0, AURORA_END_ONLY = 1, AURORA_NIGHT_AND_END = 2, AURORA_MAX = 2;
    /** The aurora strength setting's range (percent; 100 = AuroraMath's tuning). */
    public static final int AURORA_STRENGTH_MAX = 200;
    /** The night by the sun's height (sine of elevation): no aurora above NIGHT_HIGH, all of it below NIGHT_LOW (about 17 degrees down). */
    public static final float NIGHT_HIGH = -0.05f, NIGHT_LOW = -0.3f;
    /**
     * 2026-11-10 (the user: "auroras only in clear weather; in rain or snow they must not show, even at night"): the aurora fades out as the
     * rain level (vanilla's, rain and snow alike) rises from 0 to RAIN_FADE (smoothstep; vanilla moves the level by 0.01 a tick, so it
     * fades over about 1.5 seconds as the weather turns, and back as it clears); a thunderstorm keeps 1 - THUNDER_CUT. Session 5 kept 20%
     * in full rain.
     */
    public static final float RAIN_FADE = 0.3f, THUNDER_CUT = 1.0f;
    /**
     * The climate: biomes at or below COLD_TEMPERATURE (snowy: vanilla's snow falls below 0.15) see all of it, those at or above
     * WARM_TEMPERATURE (plains 0.8, desert 2.0) WARM_SHARE; the share follows the camera's biome over CLIMATE_SECONDS (no jump at a border).
     */
    public static final float COLD_TEMPERATURE = 0.15f, WARM_TEMPERATURE = 0.95f, WARM_SHARE = 0.6f, CLIMATE_SECONDS = 4.0f;
    /** The End's aurora strength relative to the setting. */
    public static final float END_SHARE = 1.0f;
    /** The jitter phase's turn per frame with TAA (the golden ratio's fraction: well spread samples over frames). */
    public static final float GOLDEN_TURN = 0.618034f;

    /** The kind of sky the dimension has: a sun (the Overworld's), the End's, or neither (the Nether, a modded sky). */
    public enum Sky {
        SUN, END, NONE
    }

    public record Values(float[] celestial, float[] aurora) {
        /** The composite's two slots. */
        public void write(LooseUniformWriter w) {
            write(w, COPY_CELESTIAL, COPY_AURORA);
        }

        /** The reflection's two slots (after ReflectionUniforms wrote its own). */
        public void writeReflection(LooseUniformWriter w) {
            write(w, REFLECTION_CELESTIAL, REFLECTION_AURORA);
        }

        private void write(LooseUniformWriter w, int celestialSlot, int auroraSlot) {
            if (w.has(celestialSlot)) w.putVec4(celestialSlot, this.celestial[0], this.celestial[1], this.celestial[2], this.celestial[3]);
            if (w.has(auroraSlot)) w.putVec4(auroraSlot, this.aurora[0], this.aurora[1], this.aurora[2], this.aurora[3]);
        }

        public boolean auroraOn() {
            return this.aurora[0] > 0;
        }
    }

    private CelestialUniforms() {
    }

    /** Square discs and no aurora (the post-process off, no frame). */
    public static Values off() {
        return new Values(new float[4], new float[4]);
    }

    /**
     * s the settings; sky the dimension's; sunHeight the sine of the sun's elevation (SunMath; ignored outside Sky.SUN); rain and thunder
     * 0..1; climate the cold share 0..1 (follow(climate(temperature))); moonPhase vanilla's 0..7; seconds the real time (FlowClock); frame
     * the frame number and taa whether TAA blends frames (the jitter phase).
     */
    public static Values compute(PostProcessSettings s, Sky sky, float sunHeight, float rain, float thunder, float climate, int moonPhase,
                                 double seconds, long frame, boolean taa) {
        if (s == null || !s.enabled()) return off();
        float[] celestial = {s.celestialShape() == SHAPE_ROUND ? WaterMath.CELESTIAL_ROUND : 0, Math.floorMod(moonPhase, 8), 0, 0};
        float strength = auroraStrength(s, sky, sunHeight, rain, thunder, climate);
        float[] aurora = new float[4];
        if (strength > 0) {
            double t = seconds - Math.floor(seconds / AuroraMath.AURORA_TIME_WRAP) * AuroraMath.AURORA_TIME_WRAP;
            aurora[0] = strength;
            aurora[1] = Double.isFinite(t) ? (float) t : 0;
            aurora[2] = sky == Sky.END ? 1 : 0;
            aurora[3] = taa ? (float) ((frame * (double) GOLDEN_TURN) % 1.0) : 0;
            if (aurora[3] < 0) aurora[3] += 1;
        }
        return new Values(celestial, aurora);
    }

    /** The aurora's strength (see the class comment): 0 with the setting off, by day, in a thunderstorm and in the Nether. */
    public static float auroraStrength(PostProcessSettings s, Sky sky, float sunHeight, float rain, float thunder, float climate) {
        if (s == null || !s.enabled() || !(s.auroraStrength() > 0)) return 0;
        int mode = s.aurora();
        if (sky == Sky.END) return mode >= AURORA_END_ONLY ? s.auroraStrength() * END_SHARE : 0;
        if (sky != Sky.SUN || mode < AURORA_NIGHT_AND_END) return 0;
        float night = AtmosphereMath.smoothstep(NIGHT_HIGH, NIGHT_LOW, finite(sunHeight, 1));
        float weather = (1 - AtmosphereMath.smoothstep(0, RAIN_FADE, clamp01(rain))) * (1 - THUNDER_CUT * clamp01(thunder));
        float c = clamp01(climate);
        return s.auroraStrength() * night * weather * (WARM_SHARE + (1 - WARM_SHARE) * c);
    }

    /** The cold share of a biome temperature: 1 at or below COLD_TEMPERATURE, 0 at or above WARM_TEMPERATURE, linear between. */
    public static float climate(float temperature) {
        if (!Float.isFinite(temperature)) return 0;
        return clamp01((WARM_TEMPERATURE - temperature) / (WARM_TEMPERATURE - COLD_TEMPERATURE));
    }

    /** The climate share after dt seconds following target (exponential, CLIMATE_SECONDS); a NaN current jumps to the target. */
    public static float follow(float current, float target, float dt) {
        if (!Float.isFinite(current)) return clamp01(target);
        float k = dt > 0 && Float.isFinite(dt) ? 1 - (float) Math.exp(-dt / CLIMATE_SECONDS) : 0;
        return current + (clamp01(target) - current) * k;
    }

    private static float finite(float v, float fallback) {
        return Float.isFinite(v) ? v : fallback;
    }

    private static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
