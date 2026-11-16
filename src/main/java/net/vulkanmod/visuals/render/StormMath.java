package net.vulkanmod.visuals.render;

/**
 * 2026-11-15 (shader roadmap session 25, the user: "천둥 날씨에서 사막에서는 모래폭풍, 눈 바이옴에서는 훨씬 강한 눈보라"): a thunderstorm over a
 * desert is a sandstorm, over snow a blizzard. Pure math (StormTest); weather.glsl mirrors the constants it shares (WeatherTest).
 * <ul>
 * <li>Strengths: sand = thunder level x the camera's desert share x the Sandstorms &amp; Blizzards setting, blizzard = thunder x the snow
 * share x the setting (BiomeSky's kernel shares, so both ramp over a border and with the thunder's own fade).</li>
 * <li>The air (AtmosphereUniforms): the ground fog FOG_SAND / FOG_SNOW times denser and the distance haze HAZE_SAND / HAZE_SNOW times,
 * the fog's colour and rain's overcast toward SAND_FOG_TINT (warm dust) or SNOW_FOG_TINT (cold white-blue).</li>
 * <li>The wind (wind): a direction that turns slowly around WIND_ANGLE (radians from +x toward +z; WIND_TURN either way over WIND_TURN_PERIOD
 * seconds) at WIND_SPEED blocks per second with gusts of up to GUST either way (two incommensurate sines); the drift each kind's drops
 * travel with is that wind integrated over time (VisualsRenderer, wrapped at WeatherMath.WEATHER_WRAP: the cells hash modulo it).</li>
 * <li>The snow in a blizzard: SNOW_EXTRA more cells hold a flake, they drift with blizzard x the wind and are drawn over
 * SNOW_STORM_EXPOSURE (short wind-blown streaks).</li>
 * <li>The sand: grains in SAND_SHARE of the cells over desert columns (the map's dust flag), drifting with the wind, drawn over
 * SAND_EXPOSURE (thin horizontal streaks), thick near the ground (weather.glsl SAND_HEIGHT).</li>
 * <li>Veils (weather.glsl stormVeil): drifting gusts of blown snow or dust, STORM_VEIL_ALPHA at most, along the view.</li>
 * </ul>
 */
public final class StormMath {
    /** The setting's range (percent). */
    public static final int STRENGTH_MAX = 200;
    public static final float FOG_SAND = 9.0f, FOG_SNOW = 6.0f, HAZE_SAND = 4.0f, HAZE_SNOW = 3.0f;
    public static final float[] SAND_FOG_TINT = {1.28f, 1.0f, 0.70f}, SNOW_FOG_TINT = {0.95f, 1.0f, 1.12f};
    public static final float WIND_ANGLE = 0.6f, WIND_TURN = 0.35f, WIND_TURN_PERIOD = 90f, WIND_SPEED = 11f, GUST = 0.35f;
    public static final float SNOW_EXTRA = 1.5f, SNOW_STORM_EXPOSURE = 0.014f, SAND_SHARE = 1.0f, SAND_EXPOSURE = 0.016f;
    /**
     * 2026-11-15 (the user: "모래폭풍이나 눈보라가 불 때는 해가 가려져서 물과 얼음의 반사면도 달라져야 해"): a storm hides the sun. cover is how much
     * (0..1): the sky's overcast share rises to it (so the sun's and the moon's discs and their glints on water and ice are gone, and the
     * reflected sky is the storm's dusty or white overcast), the sun-side glow and the caustics under water fade by it, and the water's
     * body takes WATER_SAND_TINT or WATER_SNOW_TINT (murky with dust, grey under a white-out).
     */
    public static final float[] WATER_SAND_TINT = {0.85f, 0.72f, 0.55f}, WATER_SNOW_TINT = {0.80f, 0.85f, 0.90f};

    /** The sun's cover by a storm 0..1 (sand and blizzard add up). */
    public static float cover(float sand, float blizzard) {
        return clamp01(Math.max(0, sand) + Math.max(0, blizzard));
    }

    /** The water body's tint in a storm: exactly 1 without one. */
    public static float[] waterTint(float sand, float blizzard) {
        float s = clamp01(sand), b = clamp01(blizzard);
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = (1 + (WATER_SAND_TINT[i] - 1) * s) * (1 + (WATER_SNOW_TINT[i] - 1) * b);
        return t;
    }

    /**
     * 2026-11-16 (session 25 follow-up, the user: "모래폭풍과 눈폭풍의 입자 굵기와 빈도를 조절할 수 있는 설정"): the Experimental page's tuning
     * (ExperimentalFeatures), each percent of the default: sizes TUNE_SIZE_MIN..TUNE_SIZE_MAX, amounts 0..TUNE_AMOUNT_MAX.
     */
    public static final int TUNE_SIZE_MIN = 25, TUNE_SIZE_MAX = 200, TUNE_AMOUNT_MAX = 200;

    /**
     * The storm's particles as factors of their defaults: the sand grains' width (weather.glsl SAND_RADIUS) and how many cells hold one
     * (SAND_SHARE); the snowflakes' size in a blizzard (over the snow's own size, by the blizzard's strength) and the extra flakes it adds
     * (SNOW_EXTRA).
     */
    public record Tuning(float grainSize, float grainAmount, float flakeSize, float flakeAmount) {
        public static final Tuning DEFAULT = new Tuning(1, 1, 1, 1);
    }

    /** The tuning from the settings (percent; each kept in its range). */
    public static Tuning tuning(int grainSize, int grainAmount, int flakeSize, int flakeAmount) {
        return new Tuning(size(grainSize), amount(grainAmount), size(flakeSize), amount(flakeAmount));
    }

    private static float size(int percent) {
        return Math.max(TUNE_SIZE_MIN, Math.min(TUNE_SIZE_MAX, percent)) / 100f;
    }

    private static float amount(int percent) {
        return Math.max(0, Math.min(TUNE_AMOUNT_MAX, percent)) / 100f;
    }

    /** The sand grains' colour over the lightmap's light (a pale warm sand). */
    public static final float[] SAND_COLOUR = {1.0f, 0.86f, 0.64f};

    private StormMath() {
    }

    /** A storm's strength 0..2: thunder (0..1) x the biome share (0..1) x the setting (percent). */
    public static float strength(float thunder, float share, int setting) {
        return clamp01(thunder) * clamp01(share) * Math.max(0, Math.min(STRENGTH_MAX, setting)) / 100f;
    }

    /** The wind's velocity (blocks per second, x and z) at the flow clock's seconds. */
    public static float[] wind(double seconds) {
        double t = Double.isFinite(seconds) ? seconds : 0;
        double angle = WIND_ANGLE + WIND_TURN * Math.sin(2 * Math.PI * t / WIND_TURN_PERIOD);
        double gust = GUST * (0.6 * Math.sin(t * 0.83) + 0.4 * Math.sin(t * 2.17 + 1.3));
        double speed = WIND_SPEED * (1 + gust);
        return new float[]{(float) (Math.cos(angle) * speed), (float) (Math.sin(angle) * speed)};
    }

    /** A drift (x, z) advanced by velocity x dt, wrapped at WeatherMath.WEATHER_WRAP blocks (dt clamped to 0..0.25 s: a pause or hitch). */
    public static float[] advance(float[] drift, float[] velocity, double dt, float scale) {
        double d = Double.isFinite(dt) ? Math.max(0, Math.min(0.25, dt)) : 0;
        float[] out = new float[2];
        for (int i = 0; i < 2; i++) {
            double v = drift[i] + velocity[i] * scale * d;
            double w = v - Math.floor(v / WeatherMath.WEATHER_WRAP) * WeatherMath.WEATHER_WRAP;
            out[i] = (float) (w >= WeatherMath.WEATHER_WRAP ? 0 : w);
        }
        return out;
    }

    /** The ground fog's factor: 1 without a storm. */
    public static float fog(float sand, float blizzard) {
        return 1 + FOG_SAND * Math.max(0, sand) + FOG_SNOW * Math.max(0, blizzard);
    }

    /** The distance haze's factor: 1 without a storm. */
    public static float haze(float sand, float blizzard) {
        return 1 + HAZE_SAND * Math.max(0, sand) + HAZE_SNOW * Math.max(0, blizzard);
    }

    /** The fog's and the overcast's tint: exactly 1 without a storm, the sand's or the snow's at full strength (the stronger one wins). */
    public static float[] tint(float sand, float blizzard) {
        float s = clamp01(sand), b = clamp01(blizzard);
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = (1 + (SAND_FOG_TINT[i] - 1) * s) * (1 + (SNOW_FOG_TINT[i] - 1) * b);
        return t;
    }

    static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
