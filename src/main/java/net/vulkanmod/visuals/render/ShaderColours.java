package net.vulkanmod.visuals.render;

import java.util.Arrays;

/**
 * 2026-11-10 (session 8, the user: "the parts with set colours, such as the sunset and the sky's colour, as colour pickers in the settings
 * screen"): the shader colours a player can pick. Each is a display colour (0xRRGGBB, sRGB) whose default is the colour the renderer drew
 * before the pickers; the renderer takes a picked colour as a TINT, per channel the linear picked value over the linear default
 * ({@link #tint}), and multiplies the colour it tints by it. So the default is a tint of exactly 1 in every channel (x / x), every product
 * with it is unchanged and the picture is the same bit for bit; a picked colour moves that colour by the same ratio (a darker pick darkens
 * it, another hue turns it).
 *
 * <p>Two kinds of default: a colour the renderer computes (the sky's light by the sun's height, the fog) gets a representative swatch, the
 * colour players see there (measured in the session 0 to 7 screenshots, made a little more saturated for the swatch); a fixed colour in
 * a shader or a mirror class (the aurora's emission, the water's body colour, the moon's tint, rain and snow) gets that colour itself,
 * normalised to its largest channel ({@link #display}). Where each tint goes:
 * <ul>
 * <li>DAY_SKY, SUNSET, SUNRISE: the sky's sunlight and its dome light (AtmosphereUniforms.block): the day's tint while the sun is high,
 * the sunset's (the sun in the west) or the sunrise's (in the east) while it is low (AtmosphereMath.LOW_SUN_LOW .. LOW_SUN_HIGH, the low
 * haze band's range), and the low one also on the twilight dome. The sun disc, the sun-side glow, the haze and the realistic clouds take
 * the sky's sunlight, so they follow.</li>
 * <li>SUN_GLOW: the sun-side glow on top of the above. NIGHT_SKY: the moon's light in the sky, the moon's dome and the night sky's
 * airglow. STARS (2026-11-11, session 12, the user: "the star colour in the colour section too"): vanilla's stars, which the composite
 * keeps on the sky as white (atmosphere.glsl vanillaStars), outside vanilla's square moon (water.glsl vanillaStarTint; CelestialUniforms'
 * STAR_TINT, the composite's .TINTED build). A darker pick dims them, as with every tint.</li>
 * <li>FOG: the ground fog's colour (atmosphere.glsl fogColour: the morning mist, the fog band at the horizon, the rain's fog) and the
 * aerial haze over terrain, fading to none as its air mass reaches the sky's (far terrain still meets the sky without a seam).
 * OVERCAST: rain's grey (atmosphere.glsl overcast). CLOUDS: the far clouds' light (both styles). RAIN, SNOW: the shader weather's
 * drops.</li>
 * <li>SUNLIGHT (session 8 follow-up, the user: "light source colours in the pickers too"): direct sunlight on terrain and the PBR
 * highlights (LightingUniforms slot 22, ColourMath.sunLight; the light colour setting still says how much of it). SHADE_LIGHT: the sky's
 * light on faces the sun does not reach (slot 23, ColourMath.skyShade). Both are computed by the sun's height; the swatch is the one at
 * 37 degrees.</li>
 * <li>MOON: the round moon disc and its glints (water.glsl moonRadiance) and the moonlight on terrain (slot 32). BLOCK_LIGHT: torch light's
 * warm colour (slot 31; the block light colour setting still says how much of it); since the session 8 follow-up also the glow of light
 * sources' bright texels and of LabPBR emissive texels (CelestialUniforms' GLOW_TINT, the composite's .TINTED build), so a lamp glows in
 * the colour of the light it casts.</li>
 * <li>AURORA_MAIN, AURORA_UPPER, AURORA_LOWER: the Overworld aurora's green body, red top and violet lower fringe; AURORA_END: all three
 * of the End's.</li>
 * <li>WATER: deep water's body colour seen from above (water.glsl waterColour). UNDERWATER: the water's colour around the camera under
 * water (underwater.glsl, through the daylight slot it is lit by).</li>
 * </ul>
 * Colours are not performance settings: the presets leave them alone, and a picked colour costs nothing (uniforms only).
 */
public final class ShaderColours {
    /** The colours, in the settings page's order. key is the option key (PostProcessSettings.KEY_PREFIX + key). */
    public enum Colour {
        DAY_SKY("colour_day_sky", 0x7F96B8),
        SUNSET("colour_sunset", 0xF2A66A),
        SUNRISE("colour_sunrise", 0xF0C4A0),
        SUN_GLOW("colour_sun_glow", 0xFFE4C0),
        NIGHT_SKY("colour_night_sky", 0x2A3A58),
        //2026-11-11 (session 12): the stars, white as vanilla draws them
        STARS("colour_stars", 0xFFFFFF),
        FOG("colour_fog", 0xC0CAD4),
        OVERCAST("colour_overcast", Swatch.of(AtmosphereMath.OVERCAST_RED, AtmosphereMath.OVERCAST_GREEN, AtmosphereMath.OVERCAST_BLUE)),
        CLOUDS("colour_clouds", 0xFFFFFF),
        RAIN("colour_rain", Swatch.of(WeatherUniforms.RAIN_COLOUR[0], WeatherUniforms.RAIN_COLOUR[1], WeatherUniforms.RAIN_COLOUR[2])),
        SNOW("colour_snow", Swatch.of(WeatherUniforms.SNOW_COLOUR[0], WeatherUniforms.SNOW_COLOUR[1], WeatherUniforms.SNOW_COLOUR[2])),
        //Session 8 follow-up: ColourMath.sunLight and skyShade at the default sun warmth with the sun 37 degrees up (height 0.6), as swatches
        // (hex here: ColourMath's statics reach AtmosphereUniforms, which reads ShaderColours; ShaderColoursTest checks them)
        SUNLIGHT("colour_sunlight", 0xFFBF7D),
        SHADE_LIGHT("colour_shade_light", 0xFFF8E9),
        MOON("colour_moon", Swatch.of(WaterMath.MOON_RED, WaterMath.MOON_GREEN, WaterMath.MOON_BLUE)),
        BLOCK_LIGHT("colour_block_light", Swatch.of(ColourMath.BLOCK_LIGHT[0], ColourMath.BLOCK_LIGHT[1], ColourMath.BLOCK_LIGHT[2])),
        AURORA_MAIN("colour_aurora_main", Swatch.of(AuroraMath.AURORA_GREEN_R, AuroraMath.AURORA_GREEN_G, AuroraMath.AURORA_GREEN_B)),
        AURORA_UPPER("colour_aurora_upper", Swatch.of(AuroraMath.AURORA_RED_R, AuroraMath.AURORA_RED_G, AuroraMath.AURORA_RED_B)),
        AURORA_LOWER("colour_aurora_lower", Swatch.of(AuroraMath.AURORA_FRINGE_R, AuroraMath.AURORA_FRINGE_G, AuroraMath.AURORA_FRINGE_B)),
        AURORA_END("colour_aurora_end", Swatch.of(AuroraMath.AURORA_END_MID_R, AuroraMath.AURORA_END_MID_G, AuroraMath.AURORA_END_MID_B)),
        WATER("colour_water", Swatch.of(WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE)),
        UNDERWATER("colour_underwater", Swatch.of(WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE)),
        //2026-11-11 (session 13): the particle look groups (VanillaParticles.Group): the group's typical colour (its vanilla sprites' mean)
        PARTICLE_SMOKE("colour_particle_smoke", 0x7E7873),
        PARTICLE_FLAMES("colour_particle_flames", 0xFFA51B),
        PARTICLE_EMBERS("colour_particle_embers", 0xFFB425),
        PARTICLE_DRIPS("colour_particle_drips", 0x3F76E4),
        PARTICLE_MAGIC("colour_particle_magic", 0xB070F0),
        PARTICLE_AMBIENT("colour_particle_ambient", 0xA0A0A0),
        PARTICLE_COMBAT("colour_particle_combat", 0xE7E7E7);

        private final String key;
        private final int defaultRgb;

        Colour(String key, int defaultRgb) {
            this.key = key;
            this.defaultRgb = defaultRgb;
        }

        public String key() {
            return this.key;
        }

        /** The default display colour, 0xRRGGBB (every channel at least 1, so the tint's division is defined). */
        public int defaultRgb() {
            return this.defaultRgb;
        }

        /** The colour behind an option key, or null. */
        public static Colour byKey(String key) {
            for (Colour c : values()) if (c.key.equals(key)) return c;
            return null;
        }
    }

    public static final int COUNT = Colour.values().length;
    //before DEFAULT: its constructor's tints are clones of it
    private static final float[] ONE = {1, 1, 1};
    /** Every colour at its default (every tint exactly 1). */
    public static final ShaderColours DEFAULT = new ShaderColours(defaults());
    private static volatile ShaderColours last = DEFAULT;

    private final int[] rgb;
    private final float[][] tints;
    private final boolean allDefault;

    private ShaderColours(int[] rgb) {
        this.rgb = rgb;
        this.tints = new float[COUNT][];
        boolean all = true;
        for (Colour c : Colour.values()) {
            this.tints[c.ordinal()] = tint(rgb[c.ordinal()], c.defaultRgb);
            all &= (rgb[c.ordinal()] & 0xFFFFFF) == c.defaultRgb;
        }
        this.allDefault = all;
    }

    /** The colours in Colour order (0xRRGGBB each; a missing entry is its default). Reuses the last instance when nothing changed (per frame). */
    public static ShaderColours of(int... rgb) {
        int[] v = defaults();
        for (int i = 0; i < Math.min(COUNT, rgb == null ? 0 : rgb.length); i++) v[i] = rgb[i] & 0xFFFFFF;
        if (Arrays.equals(DEFAULT.rgb, v)) return DEFAULT;
        ShaderColours l = last;
        if (Arrays.equals(l.rgb, v)) return l;
        ShaderColours made = new ShaderColours(v);
        last = made;
        return made;
    }

    /** The defaults in Colour order. */
    public static int[] defaults() {
        int[] v = new int[COUNT];
        for (Colour c : Colour.values()) v[c.ordinal()] = c.defaultRgb;
        return v;
    }

    /** The picked colour, 0xRRGGBB. */
    public int rgb(Colour c) {
        return this.rgb[c.ordinal()];
    }

    /** The colour's tint (see the class comment): per channel linear(picked) / linear(default); exactly 1 at the default. A copy. */
    public float[] tint(Colour c) {
        return this.tints[c.ordinal()].clone();
    }

    /** True when every colour is its default (every tint exactly 1). */
    public boolean allDefault() {
        return this.allDefault;
    }

    /**
     * The tint of rgb against the default colour, per channel PostMath.displayToLinear(picked) / displayToLinear(default). x / x is exactly
     * 1 in floating point, so the default gives (1, 1, 1). A default channel of 0 (none here) would divide by 0: it gives 1.
     */
    public static float[] tint(int rgb, int defaultRgb) {
        if ((rgb & 0xFFFFFF) == (defaultRgb & 0xFFFFFF)) return ONE.clone();
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) {
            int shift = 16 - 8 * i;
            float d = PostMath.displayToLinear(((defaultRgb >> shift) & 0xFF) / 255f);
            float p = PostMath.displayToLinear(((rgb >> shift) & 0xFF) / 255f);
            t[i] = d > 0 ? p / d : 1;
        }
        return t;
    }

    /** a + (b - a) t per channel: exactly 1 when a and b are both 1 (unlike a (1 - t) + b t). */
    public static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** a linear colour as a display swatch: scaled so its largest channel is 1, sRGB encoded, every channel at least 1 of 255. */
    public static int display(float r, float g, float b) {
        return Swatch.of(r, g, b);
    }

    //Apart from ShaderColours' own statics: Colour's constants call it while ShaderColours (whose statics list the constants) is not set up
    private static final class Swatch {
        static int of(float r, float g, float b) {
            float m = Math.max(r, Math.max(g, b));
            if (!(m > 0)) return 0xFFFFFF;
            int out = 0;
            for (float v : new float[]{r, g, b}) {
                int c = Math.round(PostMath.linearToDisplay(Math.max(v, 0) / m) * 255);
                out = (out << 8) | Math.max(1, Math.min(255, c));
            }
            return out;
        }
    }

    /** "#RRGGBB". */
    public static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** "the hexes that are not their default", for the log line ("defaults" when none). */
    public String describe() {
        StringBuilder b = new StringBuilder();
        for (Colour c : Colour.values()) {
            if (this.rgb[c.ordinal()] == c.defaultRgb) continue;
            if (b.length() > 0) b.append(' ');
            b.append(c.key).append(' ').append(hex(this.rgb[c.ordinal()]));
        }
        return b.length() == 0 ? "defaults" : b.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShaderColours s && Arrays.equals(this.rgb, s.rgb);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.rgb);
    }

    @Override
    public String toString() {
        return "ShaderColours[" + this.describe() + "]";
    }
}
