package net.vulkanmod.visuals.render;

/**
 * W4: atmosphere.glsl mirrored for the tests (change both together; AtmosphereTest compares every constant). The functions take the
 * seven vec4 of the shader's struct Atmosphere as an {@link Atmosphere} (AtmosphereUniforms builds it once per frame) and work in
 * W2's scene units, world directions (y up, unit) and blocks; see atmosphere.glsl's header for every component's meaning.
 */
public final class AtmosphereMath {
    // ---- the clean shafts.comp's constants (boundary 3.7) ----
    public static final int SHAFT_SAMPLES = 44;
    public static final float SHAFT_DECAY = 0.963f;
    public static final float SHAFT_THRESHOLD = 1.5f;

    // ---- modes (Atmosphere.sun w) ----
    public static final float ATMOSPHERE_NONE = 0, ATMOSPHERE_SKY = 1, ATMOSPHERE_NO_SKY = 2, ATMOSPHERE_FOG = 3;

    // ---- sky shape ----
    /** Kasten and Young 1989 relative air mass 1 / (sin e + A (e + B)^-C), e in degrees; the sky caps it at SKY_MAX_AIR_MASS. */
    public static final float AIR_MASS_A = 0.50572f, AIR_MASS_B = 6.07995f, AIR_MASS_C = 1.6364f;
    public static final float SKY_MAX_AIR_MASS = 16.0f;
    /** Henyey-Greenstein asymmetry of the sky's haze and of the sun-side glow lobe. */
    public static final float HAZE_G = 0.3f, GLOW_G = 0.93f;
    /** The glow grows toward the horizon like the in-scatter of a haze of this optical depth per air mass. */
    public static final float GLOW_DEPTH = 0.2f;
    /** The isotropic sky dome builds up along a path like a medium of this optical depth per air mass (flatter than Rayleigh's gradient). */
    public static final float AMBIENT_DEPTH = 0.7f;
    /**
     * 2026-11-09 (session 1a): the low haze band (lowBand; see atmosphere.glsl). Optical depth LOW_DEPTH m^2 / SKY_MAX_AIR_MASS along m
     * air masses, while the sun is below LOW_SUN_HIGH (full below LOW_SUN_LOW) and above LOW_NIGHT_LOW (full above LOW_NIGHT_HIGH).
     */
    public static final float LOW_DEPTH = 0.6f, LOW_SUN_LOW = 0.15f, LOW_SUN_HIGH = 0.45f, LOW_NIGHT_LOW = -0.2f, LOW_NIGHT_HIGH = -0.05f;
    /** The band's light: the sky's sunlight reddened LOW_REDDENING times more, up to the reddening of LOW_LIGHT_MASS air masses, at its brightness, times LOW_SCATTER. */
    public static final float LOW_REDDENING = 4.5f, LOW_LIGHT_MASS = 20.0f, LOW_SCATTER = 1.0f;
    /**
     * The band's phase: LOW_ISO isotropic, the rest a Henyey-Greenstein lobe of LOW_G; as the sun sinks from LOW_SHADOW_HIGH to LOW_SHADOW_LOW
     * the low air away from it falls into the earth's shadow (lit only within LOW_SHADOW_COS of the sun's direction, fully above it).
     */
    public static final float LOW_G = 0.3f, LOW_ISO = 0.3f, LOW_SHADOW_LOW = -0.03f, LOW_SHADOW_HIGH = 0.12f, LOW_SHADOW_COS = 0.7f;
    /** The band also scatters LOW_DOME of the dome's light (the sky lights it too: the earth's shadow is blue-grey, not black). */
    public static final float LOW_DOME = 0.5f;
    /** 2026-11-09 (session 1b): the band's depth along part of a sky path grows as (share of the path)^LOW_PATH_POWER. */
    public static final float LOW_PATH_POWER = 1.5f;
    /** The sky's sunlight is reddened by this share of the extinction along the sun's air mass (AtmosphereUniforms.SKY_REDDENING). */
    public static final float SKY_REDDENING = 0.7f;
    /** Vanilla's sunrise/sunset fan is suppressed on the sun's side: fully above FAN_SIDE_HIGH horizontal cosine to the sun, not below FAN_SIDE_LOW. */
    public static final float FAN_SIDE_LOW = -0.3f, FAN_SIDE_HIGH = 0.1f;
    /**
     * The haze's optical depths use d^3 / (d^2 + HAZE_ONSET^2) for a view distance d: gentle right by the camera, unchanged far away.
     * 2026-11-09 (session 1b): 400 kept everything within 150 blocks clear; 32 starts the haze a few blocks out.
     */
    public static final float HAZE_ONSET = 32.0f;
    /**
     * 2026-11-09 (session 1b): the aerial veil (atmosphere.glsl hazeMass). Near the camera the aerial haze's air mass builds VEIL_GAIN times
     * faster than ambient.w, leveling off at VEIL_GAIN x VEIL_RANGE blocks' worth after a few VEIL_RANGE blocks of air weighted by
     * exp(-height / VEIL_HEIGHT) above sea level.
     */
    public static final float VEIL_GAIN = 35.0f, VEIL_RANGE = 300.0f, VEIL_HEIGHT = 200.0f;
    /** Rain's overcast tint (luminance 1). */
    public static final float OVERCAST_RED = 0.96f, OVERCAST_GREEN = 1.0f, OVERCAST_BLUE = 1.08f;
    /** Numerical guards (see atmosphere.glsl). */
    public static final float SKY_FOG_MIN_SLOPE = 0.001f, FOG_SERIES = 0.001f, HAZE_MAX_DISTANCE = 1000000.0f, MIN_DEPTH = 0.000001f;

    /**
     * The shader's struct Atmosphere: ten vec4 (each a float[4]); see atmosphere.glsl for the components. 2026-11-10 (session 8): the last
     * three are the picked colours' tints (fog, overcast, moon), (1, 1, 1, 0) at the defaults.
     */
    public record Atmosphere(float[] sun, float[] sunLight, float[] moonLight, float[] depth, float[] ambient, float[] glow, float[] fog,
                             float[] fogTint, float[] overcastTint, float[] moonTint) {
        public static final int VEC4S = 10;
        /** The first vec4 of the tints (fogTint), and the tints' default. */
        public static final int TINTS = 7;
        static final float[] NO_TINT = {1, 1, 1, 0};

        /** The seven vec4 before session 8, with the default tints. */
        public Atmosphere(float[] sun, float[] sunLight, float[] moonLight, float[] depth, float[] ambient, float[] glow, float[] fog) {
            this(sun, sunLight, moonLight, depth, ambient, glow, fog, NO_TINT.clone(), NO_TINT.clone(), NO_TINT.clone());
        }

        /** From the 40 floats of AtmosphereUniforms.block, in field order; a shorter block (the 28 before session 8) gets the default tints. */
        public static Atmosphere of(float[] block) {
            float[][] v = new float[VEC4S][];
            for (int i = 0; i < VEC4S; i++) {
                v[i] = block.length >= i * 4 + 4 ? java.util.Arrays.copyOfRange(block, i * 4, i * 4 + 4) : i >= TINTS ? NO_TINT.clone() : new float[4];
            }
            return new Atmosphere(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
        }

        public float mode() {
            return this.sun[3];
        }
    }

    /** The shader's struct CloudLight. */
    public record CloudLight(float[] lit, float[] ambient, float[] light) {
    }

    /** haze()'s two outputs. */
    public record Haze(float[] transmittance, float[] inscatter) {
    }

    private AtmosphereMath() {
    }

    public static boolean atmosphereOn(Atmosphere a) {
        return a.sun[3] > 0.5f;
    }

    public static boolean atmosphereSky(Atmosphere a) {
        return Math.abs(a.sun[3] - ATMOSPHERE_SKY) < 0.5f;
    }

    /** SKY: 1 for LOD pixels, else the sky light 0..1; FOG: 1 for LOD pixels, else 0; NO_SKY: 1; NONE: 0; NaN sky light counts as 0. */
    public static float hazeAmount(Atmosphere a, float skyLight, boolean isLod) {
        if (!atmosphereOn(a)) return 0;
        if (Math.abs(a.sun[3] - ATMOSPHERE_FOG) < 0.5f) return isLod ? 1 : 0;
        if (!atmosphereSky(a) || isLod) return 1;
        return skyLight > 0 ? Math.min(skyLight, 1) : 0;
    }

    /** Relative air mass (Kasten and Young 1989) for a sine of elevation, clamped to 0..1, uncapped (about 38 at the horizon). */
    public static float airMass(float mu) {
        double h = clamp01(mu);
        double e = Math.toDegrees(Math.asin(h));
        return (float) (1 / (h + AIR_MASS_A * Math.pow(e + AIR_MASS_B, -AIR_MASS_C)));
    }

    public static float skyAirMass(float mu) {
        return Math.min(airMass(mu), SKY_MAX_AIR_MASS);
    }

    public static float rayleighPhase(float c) {
        return 0.75f * (1 + c * c);
    }

    public static float hgPhase(float c, float g) {
        float d = Math.max(1 + g * g - 2 * g * c, MIN_DEPTH);
        return (1 - g * g) / (d * (float) Math.sqrt(d));
    }

    /** 2026-11-10 (session 8): the overcast tint times the picked overcastTint. */
    public static float[] overcast(Atmosphere a, float[] c) {
        float l = PostMath.luminance(c[0], c[1], c[2]);
        float s = a.sunLight[3];
        float[] t = a.overcastTint;
        return new float[]{mix(c[0], OVERCAST_RED * t[0] * l, s), mix(c[1], OVERCAST_GREEN * t[1] * l, s), mix(c[2], OVERCAST_BLUE * t[2] * l, s)};
    }

    public static float[] airlight(Atmosphere a, float[] dir, float mass) {
        float tauM = a.depth[3];
        float cs = dot(dir, a.sun);
        float pr = rayleighPhase(cs), ph = hgPhase(cs, HAZE_G), prm = rayleighPhase(-cs), phm = hgPhase(-cs, HAZE_G);
        float m = Math.max(mass, 0);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float tauR = a.depth[i];
            float tau = Math.max(tauR + tauM, MIN_DEPTH);
            float sun = a.sunLight[i] * (tauR * pr + tauM * ph), moon = a.moonLight[i] * (tauR * prm + tauM * phm);
            //The sunlight at a point x air masses along the view, x/M of the way up the column, is less reddened: exp(+tauS x / M)
            float k = tau - SKY_REDDENING * (tauR + tauM) * a.fog[3] / skyAirMass(dir[1]), km = k * m;
            float sunPath = m * (Math.abs(km) > FOG_SERIES ? (1 - (float) Math.exp(-km)) / km : 1 - 0.5f * km);
            out[i] = sun * sunPath + moon * (1 - (float) Math.exp(-tau * m)) / tau + a.ambient[i] * (1 - (float) Math.exp(-AMBIENT_DEPTH * m));
        }
        //2026-11-09 (session 1a): the low haze band in front of it all
        float band = lowBand(a, m, skyAirMass(dir[1]));
        if (band > 0) {
            float t = (float) Math.exp(-band);
            float[] light = lowLight(a);
            float lit = smoothstep(LOW_SHADOW_LOW, LOW_SHADOW_HIGH, a.sun[1]);
            float phase = (LOW_ISO + (1 - LOW_ISO) * hgPhase(cs, LOW_G)) * (lit + (1 - lit) * smoothstep(0, LOW_SHADOW_COS, cs));
            for (int i = 0; i < 3; i++) out[i] = out[i] * t + (LOW_SCATTER * light[i] * phase + LOW_DOME * a.ambient[i]) * (1 - t);
        }
        return out;
    }

    /**
     * The low haze band's optical depth along m air masses of a sky path of skyMass: LOW_DEPTH skyMass^2 / SKY_MAX_AIR_MASS (m / skyMass)^LOW_PATH_POWER
     * by the sun's height and the overcast. 2026-11-09 (session 1b): power 1.5 along part of a path (it was m^2), so terrain a few hundred
     * blocks away toward a low sun takes the band's warm light.
     */
    public static float lowBand(Atmosphere a, float m, float skyMass) {
        float w = (1 - smoothstep(LOW_SUN_LOW, LOW_SUN_HIGH, a.sun[1])) * smoothstep(LOW_NIGHT_LOW, LOW_NIGHT_HIGH, a.sun[1]) * (1 - clamp01(a.sunLight[3]));
        float sm = Math.max(skyMass, 0);
        float share = sm > 0 ? Math.min(Math.max(m / sm, 0), 1) : 0;
        return LOW_DEPTH * w * sm * sm / SKY_MAX_AIR_MASS * (float) Math.pow(share, LOW_PATH_POWER);
    }

    /** The low haze band's light: the sky's sunlight reddened LOW_REDDENING times more, up to LOW_LIGHT_MASS air masses in all, at its luminance. */
    public static float[] lowLight(Atmosphere a) {
        float[] light = new float[3];
        float w = Math.max(a.fog[3], 0), mass = Math.min(LOW_REDDENING * w, Math.max(LOW_LIGHT_MASS - w, 0));
        for (int i = 0; i < 3; i++) light[i] = a.sunLight[i] * (float) Math.exp(-SKY_REDDENING * (a.depth[i] + a.depth[3]) * mass);
        float ll = PostMath.luminance(light[0], light[1], light[2]), ls = PostMath.luminance(a.sunLight[0], a.sunLight[1], a.sunLight[2]);
        float k = ll > MIN_DEPTH ? ls / ll : 0;
        for (int i = 0; i < 3; i++) light[i] *= k;
        return light;
    }

    public static float[] fogColour(Atmosphere a, float[] dir) {
        if (!atmosphereSky(a)) return new float[]{Math.max(a.sunLight[0], 0), Math.max(a.sunLight[1], 0), Math.max(a.sunLight[2], 0)};
        float l = (float) Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]);
        float[] horizon = l > 0.0001f ? new float[]{dir[0] / l, 0, dir[2] / l} : new float[]{0, 0, 1};
        float[] c = overcast(a, airlight(a, horizon, skyAirMass(0)));
        return new float[]{c[0] * a.fogTint[0], c[1] * a.fogTint[1], c[2] * a.fogTint[2]};
    }

    /** 2026-11-10 (session 8): atmosphere.glsl's hazeTint: 1 + (fogTint - 1) x the share of the sky's air the path does not hold. */
    public static float[] hazeTint(Atmosphere a, float mass, float skyMass) {
        float share = skyMass > 0 ? clamp01(1 - mass / skyMass) : 0;
        return new float[]{1 + (a.fogTint[0] - 1) * share, 1 + (a.fogTint[1] - 1) * share, 1 + (a.fogTint[2] - 1) * share};
    }

    /** The mean of exp(-height / H) along d blocks from a camera camHeight above sea level with dir.y = dirY, invH = 1 / H (heights clamped at 0). */
    public static float heightMean(float camHeight, float dirY, float d, float invH) {
        float h0 = camHeight * invH;
        float h1 = Math.max(camHeight + dirY * d, 0) * invH;
        float dh = h1 - h0;
        float e0 = (float) Math.exp(-h0);
        return Math.abs(dh) > FOG_SERIES ? (e0 - (float) Math.exp(-h1)) / dh : e0 * (1 - 0.5f * dh);
    }

    public static float groundFogDepth(Atmosphere a, float dirY, float dist) {
        float d = dist > 0 ? Math.min(dist, HAZE_MAX_DISTANCE) : 0;
        return Math.max(a.fog[0] * d * heightMean(a.fog[2], dirY, d, a.fog[1]), 0);
    }

    /** The aerial haze's air mass over a haze distance d: the far rate ambient.w per block plus the near veil (VEIL_GAIN). */
    public static float hazeMass(Atmosphere a, float dirY, float d) {
        float p = d * heightMean(a.fog[2], dirY, d, 1 / VEIL_HEIGHT);
        return Math.max(a.ambient[3], 0) * (d + VEIL_GAIN * VEIL_RANGE * (1 - (float) Math.exp(-p / VEIL_RANGE)));
    }

    public static float[] skyColour(Atmosphere a, float[] dir) {
        if (!atmosphereSky(a)) return fogColour(a, dir);
        float[] air = overcast(a, airlight(a, dir, skyAirMass(dir[1])));
        float tf = (float) Math.exp(-a.fog[0] * Math.exp(-a.fog[2] * a.fog[1]) / (Math.max(a.fog[1], MIN_DEPTH) * Math.max(dir[1], SKY_FOG_MIN_SLOPE)));
        float[] fog = fogColour(a, dir);
        return new float[]{air[0] * tf + fog[0] * (1 - tf), air[1] * tf + fog[1] * (1 - tf), air[2] * tf + fog[2] * (1 - tf)};
    }

    /** The glow's lobe toward dir (sun and moon). */
    public static float[] glowLobe(Atmosphere a, float[] dir) {
        float cs = dot(dir, a.sun);
        float ps = hgPhase(cs, GLOW_G), pm = hgPhase(-cs, GLOW_G);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = a.glow[i] * ps + a.moonLight[i] * a.glow[3] * pm;
        return out;
    }

    /** How the glow grows along mass air masses (1 at one air mass). */
    public static float glowGain(float mass) {
        return (1 - (float) Math.exp(-GLOW_DEPTH * Math.max(mass, 0))) / (1 - (float) Math.exp(-GLOW_DEPTH));
    }

    public static float[] glowAlong(Atmosphere a, float[] dir, float mass) {
        float[] lobe = glowLobe(a, dir);
        float gain = glowGain(mass);
        return new float[]{lobe[0] * gain, lobe[1] * gain, lobe[2] * gain};
    }

    public static float[] sunGlow(Atmosphere a, float[] dir) {
        if (!atmosphereSky(a)) return new float[3];
        return glowAlong(a, dir, skyAirMass(dir[1]));
    }

    public static float hazeDistance(float d) {
        float d2 = d * d;
        return d2 * d / (d2 + HAZE_ONSET * HAZE_ONSET);
    }

    /** The shader's haze; final fix R5: no amount returns at once, the sky's air mass and the glow's lobe are evaluated once. */
    public static Haze haze(Atmosphere a, float[] dir, float dist, float amount) {
        return hazeOpen(a, dir, dist, amount, 1);
    }

    /** 2026-11-10: glowLobe with the sun's part times sunOpen (0..1, sunopen.glsl's share); the moon's part stays. */
    public static float[] glowLobeOpen(Atmosphere a, float[] dir, float sunOpen) {
        float cs = dot(dir, a.sun);
        float open = sunOpen > 0 ? Math.min(sunOpen, 1) : 0;
        float ps = hgPhase(cs, GLOW_G) * open, pm = hgPhase(-cs, GLOW_G);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = a.glow[i] * ps + a.moonLight[i] * a.glow[3] * pm;
        return out;
    }

    /** 2026-11-10: haze with the sun's glow (both media) at sunOpen. */
    public static Haze hazeOpen(Atmosphere a, float[] dir, float dist, float amount, float sunOpen) {
        float k = amount > 0 ? Math.min(amount, 1) : 0;
        if (!atmosphereOn(a) || k <= 0) return new Haze(new float[]{1, 1, 1}, new float[3]);
        float d = hazeDistance(dist > 0 ? Math.min(dist, HAZE_MAX_DISTANCE) : 0);
        float tf = (float) Math.exp(-groundFogDepth(a, dir[1], d) * k);
        if (!atmosphereSky(a)) {
            float[] fog = fogColour(a, dir);
            return new Haze(new float[]{tf, tf, tf}, new float[]{fog[0] * (1 - tf), fog[1] * (1 - tf), fog[2] * (1 - tf)});
        }
        float skyMass = skyAirMass(dir[1]);
        float mass = Math.min(hazeMass(a, dir[1], d), skyMass) * k;
        float[] t = new float[3];
        float band = (float) Math.exp(-lowBand(a, mass, skyMass));
        for (int i = 0; i < 3; i++) t[i] = (float) Math.exp(-Math.max(a.depth[i] + a.depth[3], MIN_DEPTH) * mass) * tf * band;
        float[] air = overcast(a, airlight(a, dir, mass));
        float[] tint = hazeTint(a, mass, skyMass);
        float[] lobe = glowLobeOpen(a, dir, sunOpen);
        float along = glowGain(mass), sky = glowGain(skyMass);
        float[] in = new float[3];
        for (int i = 0; i < 3; i++) in[i] = (air[i] * tint[i] + lobe[i] * along) * tf;
        if (tf < 1) {
            float[] fog = fogColour(a, dir);
            for (int i = 0; i < 3; i++) in[i] += (fog[i] + lobe[i] * sky) * (1 - tf);
        }
        return new Haze(t, in);
    }

    public static float[] applyHaze(Atmosphere a, float[] colour, float[] dir, float dist, float amount) {
        return applyHazeOpen(a, colour, dir, dist, amount, 1);
    }

    public static float[] applyHazeOpen(Atmosphere a, float[] colour, float[] dir, float dist, float amount, float sunOpen) {
        Haze h = hazeOpen(a, dir, dist, amount, sunOpen);
        return new float[]{colour[0] * h.transmittance[0] + h.inscatter[0], colour[1] * h.transmittance[1] + h.inscatter[1],
                colour[2] * h.transmittance[2] + h.inscatter[2]};
    }

    /** vanillaStars: vanilla's white residue above its sky and fog colours, dropped on the sun's side by the fan, times the star weight. */
    public static float[] vanillaStars(Atmosphere a, float[] dir, float[] pixel, float[] vanillaSky, float[] vanillaFog, float[] gate) {
        if (!atmosphereSky(a) || !(gate[0] > 0)) return new float[3];
        float white = Float.MAX_VALUE;
        for (int i = 0; i < 3; i++) white = Math.min(white, Math.max(pixel[i] - Math.max(vanillaSky[i], vanillaFog[i]), 0));
        float ld = (float) Math.sqrt(dir[0] * dir[0] + dir[2] * dir[2]), ls = (float) Math.sqrt(a.sun[0] * a.sun[0] + a.sun[2] * a.sun[2]);
        float side = ld > 0.0001f && ls > 0.0001f ? (dir[0] * a.sun[0] + dir[2] * a.sun[2]) / (ld * ls) : 0;
        float fan = gate[1] > 0 ? Math.min(gate[1], 1) : 0;
        float keep = 1 - fan * smoothstep(FAN_SIDE_LOW, FAN_SIDE_HIGH, side);
        float v = white * keep * Math.min(gate[0], 1);
        return new float[]{v, v, v};
    }

    /**
     * cloudColour: premultiplied rgb and coverage from a farCloudMarch result (sunlit weight, sky-lit weight, coverage, distance); a
     * three-value vanilla-shape march (light-weighted coverage, coverage, distance) is taken as CloudMath.four makes it.
     */
    public static float[] cloudColour(CloudLight c, float[] march) {
        float[] m = CloudMath.four(march);
        float x = m[0] > 0 ? m[0] : 0, y = m[1] > 0 ? m[1] : 0, z = m[2] > 0 ? Math.min(m[2], 1) : 0;
        return new float[]{x * c.lit[0] + y * c.ambient[0], x * c.lit[1] + y * c.ambient[1], x * c.lit[2] + y * c.ambient[2], z};
    }

    public static float[] cloudOver(Atmosphere a, CloudLight c, float[] march, float[] dir, float amount) {
        float[] cloud = cloudColour(c, march);
        Haze h = haze(a, dir, CloudMath.four(march)[3], amount);
        float[] out = new float[4];
        for (int i = 0; i < 3; i++) out[i] = cloud[i] * h.transmittance[i] + h.inscatter[i] * cloud[3];
        out[3] = cloud[3];
        return out;
    }

    // ---- helpers ----

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float mix(float a, float b, float t) {
        return a * (1 - t) + b * t;
    }

    static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    static float clamp01(float v) {
        return clamp(v, 0, 1);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
