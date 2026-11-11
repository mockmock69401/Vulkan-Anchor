package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.ParticleEffect;
import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;

/**
 * 2026-11-09 (session 6, the user: vanilla's rain and snow look thick and too vanilla; shader-drawn rain and snow with an on/off setting):
 * weather.comp's slots. 0..3 the view (the inverse projections without the TAA jitter, the inverse view, the depth parameters), 4..7 the
 * light volume (LightVolumeUniforms.write at WEATHER_FIRST), 8..12 weatherPixel's camera, frac, params, rain and snow (weather.glsl), 13
 * and 14 the drops' tints, 15 the splashes (x the time wrapped at SPLASH_WRAP periods; 2026-11-10), 16 the rings on the water (x their
 * time, y the rain on the water: ReflectionUniforms' slot 25; zw RippleMath.offsets' first octave), whose drops throw up the crowns,
 * 17 (2026-11-11) each kind's type and size (ParticleEffect.RAIN, SNOW: x rain's type, y snow's, z rain's size, w snow's).
 * <p>
 * The strength follows vanilla's rain and thunder levels: the share of cells holding a drop is RAIN_SHARE (SNOW_SHARE) x the kind's amount
 * setting x the rain level, THUNDER_EXTRA more in a thunderstorm, and the coverage COVERAGE_BASE + (1 - COVERAGE_BASE) x the rain level
 * (rain fades in and out with it). The streaks are the drops' motion relative to the camera over EXPOSURE seconds: the fall (RAIN_FALL,
 * SNOW_FALL blocks per second) minus the camera's velocity (Garg and Nayar 2006).
 */
public final class WeatherUniforms {
    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, INV_VIEW = 2, DEPTH_PARAMS = 3, WEATHER_FIRST = 4, CAMERA = 8, FRAC = 9,
            PARAMS = 10, RAIN = 11, SNOW = 12, RAIN_TINT = 13, SNOW_TINT = 14, SPLASH = 15, WATER = 16, STYLE = 17;
    /** Fall speeds (blocks per second): vanilla's rain texture falls about this fast, its snow a little over a block a second. */
    public static final float RAIN_FALL = 14.0f, SNOW_FALL = 1.5f;
    /**
     * The exposure the streaks are drawn over (seconds): a 0.56 block streak for still rain; snow's shorter, so a still flake stays round
     * (its fall would draw it out to a grain of rice) while the camera's motion still draws it out.
     */
    public static final float EXPOSURE = 0.04f, SNOW_EXPOSURE = 0.012f;
    /** Cells with a drop at density 100 in full rain, and a thunderstorm's extra share. */
    public static final float RAIN_SHARE = 0.9f, SNOW_SHARE = 0.8f, THUNDER_EXTRA = 0.5f;
    /** The coverage at the faintest rain (rain level just above 0); full at level 1. */
    public static final float COVERAGE_BASE = 0.5f;
    /** The flakes' sway: radians per second, wrapped at SWAY_WRAP (sin of it and cos of 0.8 of it both repeat there). */
    public static final float SWAY_RATE = 1.3f;
    public static final double SWAY_WRAP = 10 * Math.PI;
    /** The drops' tints over the lightmap's light: rain a pale blue-grey (water refracting the sky), snow near white. */
    public static final float[] RAIN_COLOUR = {0.72f, 0.78f, 0.88f}, SNOW_COLOUR = {0.95f, 0.96f, 1.0f};
    /** The camera's velocity the streaks follow: smoothed over VELOCITY_SECONDS, and a jump over TELEPORT_BLOCKS in a frame resets it. */
    public static final float VELOCITY_SECONDS = 0.1f, TELEPORT_BLOCKS = 8.0f;

    public record Values(boolean on, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f invView, boolean lodValid, float[] camera,
                         float[] frac, float[] params, float[] rain, float[] snow, float[] splash, float[] water, float[] rainTint, float[] snowTint,
                         float[] style) {
        public void write(LooseUniformWriter w) {
            if (!this.on) return;
            w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection);
            w.putMat4(INV_LOD_PROJ, this.invLodProjection);
            w.putMat4(INV_VIEW, this.invView);
            w.putVec4(DEPTH_PARAMS, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, 0, 0);
            w.putVec4(CAMERA, this.camera[0], this.camera[1], this.camera[2], this.camera[3]);
            w.putVec4(FRAC, this.frac[0], this.frac[1], this.frac[2], this.frac[3]);
            w.putVec4(PARAMS, this.params[0], this.params[1], this.params[2], this.params[3]);
            w.putVec4(RAIN, this.rain[0], this.rain[1], this.rain[2], this.rain[3]);
            w.putVec4(SNOW, this.snow[0], this.snow[1], this.snow[2], this.snow[3]);
            //2026-11-10 (session 8): times the picked rain and snow colours' tints (exactly 1 at the defaults)
            w.putVec4(RAIN_TINT, RAIN_COLOUR[0] * this.rainTint[0], RAIN_COLOUR[1] * this.rainTint[1], RAIN_COLOUR[2] * this.rainTint[2], 0);
            w.putVec4(SNOW_TINT, SNOW_COLOUR[0] * this.snowTint[0], SNOW_COLOUR[1] * this.snowTint[1], SNOW_COLOUR[2] * this.snowTint[2], 0);
            w.putVec4(SPLASH, this.splash[0], 0, 0, 0);
            w.putVec4(WATER, this.water[0], this.water[1], this.water[2], this.water[3]);
            w.putVec4(STYLE, this.style[0], this.style[1], this.style[2], this.style[3]);
        }

        /** 2026-11-11: both kinds round (slot 17's types): VkPostProcess dispatches postWeather.ROUND, the pixel sprites compiled out. */
        public boolean roundOnly() {
            return this.on && this.style[0] == WeatherMath.WEATHER_TYPE_ROUND && this.style[1] == WeatherMath.WEATHER_TYPE_ROUND;
        }
    }

    private WeatherUniforms() {
    }

    public static Values off() {
        return new Values(false, null, null, null, false, null, null, null, null, null, null, null, null, null, null);
    }

    /** Whether the shader draws the weather this frame instead of vanilla (the setting on, the post-process on, a frame to draw). */
    public static boolean wanted(PostProcessSettings s) {
        return s != null && s.enabled() && s.shaderWeather();
    }

    /**
     * The overlay's values, off (nothing dispatched) without the setting, with no rain (rain level 0: clear, the Nether, the End), with
     * the camera in a fluid (the water's surface hides the rain above it) or without a view. camera the eye (world), velocity the
     * camera's (blocks per second), seconds the flow clock (stopped while paused); invVanillaProjection unjittered; rainNear / snowNear
     * whether the precipitation map holds that kind (PrecipitationMap.hasRain, hasSnow: the shader walks only the kinds there are); water the
     * rings on the water (slot 16; null: none).
     */
    public static Values compute(PostProcessSettings s, float rain, float thunder, boolean inFluid, Matrix4f invVanillaProjection, Matrix4f invLodProjection,
                                 Matrix4f invView, boolean lodValid, int height, double x, double y, double z, float[] velocity, double seconds,
                                 boolean rainNear, boolean snowNear, float[] water) {
        if (!wanted(s) || !(rain > 0) || inFluid || invVanillaProjection == null || invView == null || height <= 0) return off();
        float r = Math.min(rain, 1), th = Float.isFinite(thunder) ? Math.max(0, Math.min(thunder, 1)) : 0;
        ParticleStyles p = s.particles();
        float boost = r * (1 + THUNDER_EXTRA * th);
        float[] params = {rainNear ? Math.min(1, RAIN_SHARE * p.amount(ParticleEffect.RAIN) * boost) : 0,
                snowNear ? Math.min(1, SNOW_SHARE * p.amount(ParticleEffect.SNOW) * boost) : 0,
                COVERAGE_BASE + (1 - COVERAGE_BASE) * r, (float) wrap(seconds * SWAY_RATE, SWAY_WRAP)};
        double bx = Math.floor(x), by = Math.floor(y), bz = Math.floor(z);
        float[] camera = {Math.floorMod((long) bx, WeatherMath.WEATHER_WRAP), (float) by, Math.floorMod((long) bz, WeatherMath.WEATHER_WRAP), (float) y};
        //The pixel's angle: 2 tan(fov / 2) / height (the inverse projection's m11 is tan(fov / 2))
        float pixelAngle = 2 * Math.abs(invVanillaProjection.m11()) / height;
        float[] frac = {(float) (x - bx), (float) (y - by), (float) (z - bz), pixelAngle};
        float[] v = velocity != null && velocity.length == 3 ? velocity : new float[3];
        return new Values(true, invVanillaProjection, invLodProjection != null ? invLodProjection : invVanillaProjection, invView, lodValid, camera, frac,
                params, motion(RAIN_FALL, v, WeatherMath.RAIN_PERIOD, seconds, EXPOSURE), motion(SNOW_FALL, v, WeatherMath.SNOW_PERIOD, seconds, SNOW_EXPOSURE),
                new float[]{(float) wrap(seconds, (double) WeatherMath.SPLASH_PERIOD * WeatherMath.SPLASH_WRAP), 0, 0, 0},
                water != null && water.length == 4 ? water : new float[4], brightened(s.colours().tint(ShaderColours.Colour.RAIN), p.brightness(ParticleEffect.RAIN)),
                brightened(s.colours().tint(ShaderColours.Colour.SNOW), p.brightness(ParticleEffect.SNOW)), style(p));
    }

    /** 2026-11-11: a kind's colour tint times its brightness setting (the drops, and the rain's splashes and crowns; 1 leaves it exact). */
    static float[] brightened(float[] tint, float brightness) {
        return brightness == 1 ? tint : new float[]{tint[0] * brightness, tint[1] * brightness, tint[2] * brightness};
    }

    /** Slot 17: the rain's and the snow's type (WeatherMath.WEATHER_TYPE_*) and size (1 = tuned). */
    static float[] style(ParticleStyles p) {
        return new float[]{p.type(ParticleEffect.RAIN), p.type(ParticleEffect.SNOW), p.size(ParticleEffect.RAIN), p.size(ParticleEffect.SNOW)};
    }

    /**
     * A kind's slot: the half streak (u EXPOSURE / 2, u = the fall minus the camera's velocity) and the fall offset (fall x seconds, wrapped
     * at period x WEATHER_FALL_WRAP, a whole number of every layer's cells, which weather.glsl hashes modulo that span).
     */
    static float[] motion(float fall, float[] velocity, float period, double seconds, float exposure) {
        float k = exposure / 2;
        return new float[]{-velocity[0] * k, (-fall - velocity[1]) * k, -velocity[2] * k, (float) wrap(seconds * fall, period * WeatherMath.WEATHER_FALL_WRAP)};
    }

    static double wrap(double v, double period) {
        if (!Double.isFinite(v)) return 0;
        double w = v - Math.floor(v / period) * period;
        return w >= period ? 0 : w;
    }

    /**
     * The camera's velocity after a frame of dt seconds that moved it by (dx, dy, dz): followed over VELOCITY_SECONDS; a teleport (a jump
     * over TELEPORT_BLOCKS) or no time gives the old velocity (a pause) or none (a teleport).
     */
    public static float[] followVelocity(float[] current, double dx, double dy, double dz, double dt) {
        if (Math.abs(dx) > TELEPORT_BLOCKS || Math.abs(dy) > TELEPORT_BLOCKS || Math.abs(dz) > TELEPORT_BLOCKS || !Double.isFinite(dx + dy + dz)) return new float[3];
        if (!(dt > 1e-4)) return current;
        float k = (float) (1 - Math.exp(-dt / VELOCITY_SECONDS));
        return new float[]{current[0] + ((float) (dx / dt) - current[0]) * k, current[1] + ((float) (dy / dt) - current[1]) * k,
                current[2] + ((float) (dz / dt) - current[2]) * k};
    }

    /** The unjittered inverse of a jittered inverse projection (jitter: TaaJitter.appliedNdc, zero without TAA). */
    public static Matrix4f unjitteredInverse(Matrix4f invProjection, float[] jitter) {
        if (invProjection == null || jitter == null || (jitter[0] == 0 && jitter[1] == 0)) return invProjection;
        Matrix4f projection = new Matrix4f(invProjection).invert();
        return TaaJitter.unjittered(projection, jitter).invert();
    }
}
