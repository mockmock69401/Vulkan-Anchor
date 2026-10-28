package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * W4: the atmosphere's per-frame state. {@link #block} turns the frame into the seven vec4 of atmosphere.glsl's struct Atmosphere
 * (see its header for every component); {@link Values#write} puts them into the composite's slots, with the fixed slots 0, 1, 2
 * and 8; {@link Values#writeShafts} gives the clean shafts.comp the sun's screen position.
 *
 * <p>Composite (scene_copy.comp) slots owned here: 3 sun, 4 sunLight, 5 moonLight, 6 depth, 7 ambient, 9 glow, 25 fog (the struct
 * Atmosphere in field order); 26 vanilla's sky colour, 27 vanilla's fog colour (scene units) and 28 the star gate, for keeping vanilla's
 * stars (atmosphere.glsl vanillaStars); 29 (final fix R1, R2, boundary 3.2) x the look weight every exposure the composite adds is
 * blended toward 1 by (the sun-shading setting / 100, 0 with the post-processing off), y 1 when the camera is inside water (the frame's
 * camera fluid), z the overcast share and w the mode as if the atmosphere were drawn (the lighting's weather, night and no-sky
 * exposures follow them also when nothing is drawn); 30..34 reserved, written as 0. The reflection takes the same block through
 * {@link #writeBlock} and {@link #block}.
 *
 * <p>Final fix R1: with every setting that draws something (fog strength, sun glow, sunset light, light shafts, moonlight, far clouds)
 * at 0 the composite's block is ATMOSPHERE_NONE (vanilla's sky and no haze: B20); the reflection's own block ({@link #block}) stays
 * the drawn sky, which reflected rays need.
 *
 * <p>Every tuning value below is one constant. Settings: fog strength scales both hazes' density, fog height is the ground fog's
 * scale height, sun glow scales the glow, sunset light is the saturation of the low sun's colours (0 = neutral), moonlight scales
 * the moon's light in the sky, light shafts gates the screen-space shafts (the final pass applies its strength).
 */
public final class AtmosphereUniforms {
    // ---- composite slots ----
    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, INV_VIEW = 2, DEPTH_PARAMS = 8;
    /** The struct Atmosphere's seven vec4 in the composite, in field order. */
    public static final int[] COPY_SLOTS = {3, 4, 5, 6, 7, 9, 25};
    /** rgb vanilla's sky colour and vanilla's fog colour at the camera, scene units, w 1 when known (for keeping vanilla's stars). */
    public static final int VANILLA_SKY = 26, VANILLA_FOG = 27;
    /** x the star weight (night() x (1 - rain)), y the strength of vanilla's sunrise/sunset fan (its sunrise colour's alpha, 0 without), zw 0. */
    public static final int STAR_GATE = 28;
    /** Final fix R1, R2: x the look weight, y 1 with the camera in water, z the overcast share, w the mode the exposures follow. */
    public static final int LOOK = 29;
    /** 2026-10-21: the border fog with no LODs drawn: x its start, y its end (horizontal blocks), z 1 when on, w 0. */
    public static final int BORDER = 30;
    /** The border fog starts at this share of the render distance and is full at its end. */
    public static final float BORDER_START_SHARE = 0.75f, BORDER_END_SHARE = 1.0f;
    /** Reserved composite slots, written as 0 (2026-10-28: 31 and 32 are LightingUniforms' block and moon light now). */
    public static final int RESERVED_FIRST = 34, RESERVED_LAST = 34;
    public static final int BLOCK_FLOATS = AtmosphereMath.Atmosphere.VEC4S * 4;

    // ---- the air (Java only; each is one tuning knob) ----
    /** Wavelengths (micrometres) standing for the red, green and blue primaries in the sky (as in Nishita et al. 1993 and Bruneton and Neyret 2008). */
    public static final double[] SKY_WAVELENGTHS = {0.68, 0.55, 0.44};
    /** Rayleigh optical depth of one air mass, times Hansen and Travis 1974's sea-level value. */
    public static final float RAYLEIGH_SCALE = 1.0f;
    /** Ozone's Chappuis band (vertical optical depth at about 300 Dobson units), absorbing red and green: the purple of twilight. */
    public static final float[] OZONE_DEPTH = {0.0098f, 0.0282f, 0.0013f};
    /**
     * The ozone absorption on the low sun's light is OZONE_SCALE times the real one. Tuning round 1 took 2 (more green taken: the
     * references' pink); 2026-10-24 (CTE2: "the sunset is only red", the user's pick v3) 1: with the green kept, the sky toward a setting
     * sun turns orange and gold instead of red and magenta.
     */
    public static final float OZONE_SCALE = 1.0f;
    /** Haze (aerosol) optical depth of one air mass in clear weather; full rain multiplies it by 1 + RAIN_HAZE. */
    public static final float HAZE_DEPTH = 0.03f, RAIN_HAZE = 3.0f;
    /** The sunlight scattered in the sky, scene units: sets the whole sky's brightness (noon zenith about 0.2, horizon about 0.65). */
    public static final float SKY_SUN = 0.85f;
    /**
     * Tuning round 2: the daytime sky's light is 1 + DAY_SKY_BOOST times brighter once the sun is above DAY_BOOST_HIGH, fading in
     * from DAY_BOOST_LOW (the sunset below it, which matches the references, keeps its brightness).
     */
    public static final float DAY_SKY_BOOST = 0.1f, DAY_BOOST_LOW = 0.25f, DAY_BOOST_HIGH = 0.5f;
    /** The sky's sunlight is reddened by SKY_REDDENING of the extinction along the sun's air mass, its dome light by AMBIENT_REDDENING. */
    public static final float SKY_REDDENING = AtmosphereMath.SKY_REDDENING, AMBIENT_REDDENING = 0.6f;
    /**
     * The isotropic sky dome (after Hillaire 2020's isotropic multiple-scattering idea), relative to the sky's sunlight: SKY_MULTI of
     * it in the blue of the Rayleigh depth raised to AMBIENT_BLUE (the multiply-scattered sky light), plus TWILIGHT_SKY of it uncoloured
     * while the sun is low (fading out by TWILIGHT_HEIGHT sun height): the bright pink-lavender sunset sky of refs S2/S13.
     */
    public static final float SKY_MULTI = 0.2f, AMBIENT_BLUE = 0.5f, TWILIGHT_SKY = 1.2f, TWILIGHT_HEIGHT = 0.45f;
    /** The moon's light relative to the sun's at the moonlight setting's 100. */
    public static final float MOON_SKY = 0.035f;
    /** Night-sky light (airglow, starlight): the dome's brightness on a moonless night, scene units. */
    public static final float[] NIGHT_SKY = {0.0015f, 0.0018f, 0.0027f};
    /** The sun's (moon's) light in the air fades in over this height (sine of elevation) range: twilight lasts until 0.1 below the horizon. */
    public static final float SUN_FADE_LOW = -0.1f, SUN_FADE_HIGH = 0.05f;
    /** The sun-side glow at the sun glow setting's 100, relative to the sky's sunlight (times the HG lobe, 45 at the sun). */
    public static final float GLOW_AMOUNT = 0.02f;
    /**
     * Tuning round 1: the low sun's colours (sky light, dome, glow, cloud tint) keep sunset light x SUNSET_SATURATION of their hue: the
     * player's 50 is the computed colour, the default 100 twice as saturated (the references' warm peach and pink).
     */
    public static final float SUNSET_SATURATION = 2.0f;
    /**
     * 2026-10-24 (CTE2: "the sunset is only red"): below this sun height (the tuning references' S2 sunset, about 10.7 degrees) the
     * sky's in-scatter and the twilight dome take the sunlight of the air column above the ground (fog.w, columnTint), fully with the
     * sun at the horizon; above it the references' ground-reddened colours stay as tuned.
     */
    public static final float LIFT_SUN_HEIGHT = 0.18f;
    /**
     * The twilight dome (isotropic: one colour over the whole sky) is cut by this share with the sun at the horizon, fading out
     * up to LIFT_SUN_HEIGHT, so the in-scatter's gradient (orange toward the sun, darker above) shows (the user's pick v3).
     */
    public static final float TWILIGHT_LIFT_CUT = 0.5f;
    /** Full rain keeps 1 - GLOW_RAIN_CUT of the glow. */
    public static final float GLOW_RAIN_CUT = 0.8f;
    /** Full rain: RAIN_GREY of the overcast grey, the light dimmed by RAIN_DARK; a thunderstorm dims by THUNDER_DARK more. */
    public static final float RAIN_GREY = 0.8f, RAIN_DARK = 0.52f, THUNDER_DARK = 0.35f;
    /** Full rain turns RAIN_DIFFUSE of the sky's sunlight into isotropic light (an overcast sky has no bright side toward the sun). */
    public static final float RAIN_DIFFUSE = 0.7f;
    /** Vanilla's sky colour tints the sky's hue by SKY_TINT of its ratio to vanilla's temperate sky (SKY_TINT_REFERENCE, display values). */
    public static final float SKY_TINT = 0.5f, SKY_TINT_MIN = 0.5f, SKY_TINT_MAX = 2.0f, SKY_TINT_FADE = 0.02f;
    public static final float[] SKY_TINT_REFERENCE = {121 / 255f, 167 / 255f, 1};

    // ---- haze ----
    /** Blocks of view distance per air mass at fog strength 100 (the aerial haze: terrain 3000 blocks away is behind one air mass). */
    public static final float AIR_MASS_BLOCKS = 3000;
    /** Ground fog density per block at sea level at fog strength 100; full rain multiplies it by 1 + RAIN_FOG. */
    public static final float FOG_DENSITY = 0.001f, RAIN_FOG = 1.5f;
    /** Uniform haze density per block in vanilla's environmental fog (vanilla already fogs; only far LODs need it) and without sky light. */
    public static final float ENV_FOG_DENSITY = 0.005f, NO_SKY_FOG_DENSITY = 0.0007f;

    // ---- screen-space light shafts ----
    /** Shaft colour (luminance per unit of the shaft image) at noon and with the sun low, (1 - height)^2 in between. */
    public static final float SHAFT_NOON = 0.03f, SHAFT_LOW = 0.07f;
    /** The shafts fade in over this sun height above the horizon, and out over SHAFT_MARGIN screens beyond the edge. */
    public static final float SHAFT_HORIZON_FADE = 0.05f, SHAFT_MARGIN = 0.25f;
    /** Full rain keeps 1 - SHAFT_RAIN_CUT of the shafts, a thunderstorm a further 1 - SHAFT_THUNDER_CUT. */
    public static final float SHAFT_RAIN_CUT = 0.8f, SHAFT_THUNDER_CUT = 0.5f;

    /**
     * One frame's atmosphere. block holds the struct Atmosphere (BLOCK_FLOATS floats); shafts and the sun's screen position (row 0 at
     * the top, 0..1 across the screen) are the screen-space shafts'; shaftColour is their colour with shafts applied.
     */
    public record Values(boolean lodValid, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f invView, float[] block,
                         float shafts, float[] shaftColour, float sunX, float sunY, float[] vanilla, float[] look, float[] border) {
        /**
         * 0 by day .. 1 at night: the sun's light fading out from SUN_FADE_HIGH to SUN_FADE_LOW sun height. Also by the sun's height under
         * an environmental fog in a dimension with sky light (ATMOSPHERE_FOG); always 0 without sky light (ATMOSPHERE_NO_SKY) and without a
         * frame (ATMOSPHERE_NONE).
         */
        public float night() {
            return this.block[11];
        }

        /** The light-shaft colour at full strength (before the light-shafts setting), scene units per unit of the shaft image; (0, 0, 0) whenever shafts() is 0. */
        public float[] shaftLight() {
            return this.shafts > 0 ? this.shaftColour.clone() : new float[3];
        }

        /** > 0 when the screen-space shafts run: the sun above the horizon and on or near the screen, the setting above 0. */
        @Override
        public float shafts() {
            return this.shafts;
        }

        @Override
        public float[] block() {
            return this.block.clone();
        }

        @Override
        public float[] shaftColour() {
            return this.shaftColour.clone();
        }

        /** VANILLA_SKY, VANILLA_FOG, then STAR_GATE (three vec4). */
        @Override
        public float[] vanilla() {
            return this.vanilla.clone();
        }

        /** Slot LOOK: the look weight, the camera in water, the overcast share, the mode the exposures follow. */
        @Override
        public float[] look() {
            return this.look.clone();
        }

        /** The mode (AtmosphereMath.ATMOSPHERE_*). */
        public float mode() {
            return this.block[3];
        }

        /** The block as the shader's struct, for the Java mirror. */
        public AtmosphereMath.Atmosphere atmosphere() {
            return AtmosphereMath.Atmosphere.of(this.block);
        }

        /** The composite's slots 0, 1, 2, 8 (fixed meanings), the struct Atmosphere (COPY_SLOTS), VANILLA_SKY, VANILLA_FOG, STAR_GATE and the reserved slots as 0. */
        public void write(LooseUniformWriter w) {
            if (w.has(INV_VANILLA_PROJ)) w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection);
            if (w.has(INV_LOD_PROJ)) w.putMat4(INV_LOD_PROJ, this.invLodProjection);
            if (w.has(INV_VIEW)) w.putMat4(INV_VIEW, this.invView);
            if (w.has(DEPTH_PARAMS)) w.putVec4(DEPTH_PARAMS, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, 0, 0);
            writeBlock(w, this.block, COPY_SLOTS);
            if (w.has(VANILLA_SKY)) w.putVec4(VANILLA_SKY, this.vanilla[0], this.vanilla[1], this.vanilla[2], this.vanilla[3]);
            if (w.has(VANILLA_FOG)) w.putVec4(VANILLA_FOG, this.vanilla[4], this.vanilla[5], this.vanilla[6], this.vanilla[7]);
            if (w.has(STAR_GATE)) w.putVec4(STAR_GATE, this.vanilla[8], this.vanilla[9], this.vanilla[10], this.vanilla[11]);
            if (w.has(LOOK)) w.putVec4(LOOK, this.look[0], this.look[1], this.look[2], this.look[3]);
            if (w.has(BORDER)) w.putVec4(BORDER, this.border[0], this.border[1], this.border[2], 0);
            for (int s = RESERVED_FIRST; s <= RESERVED_LAST; s++) if (w.has(s)) w.putVec4(s, 0, 0, 0, 0);
        }

        /** shafts.comp's location 0: xy the sun's screen position (row 0 at the top). */
        public void writeShafts(LooseUniformWriter w) {
            w.putVec4(0, this.sunX, this.sunY, 0, 0);
        }
    }

    private AtmosphereUniforms() {
    }

    /** The border fog's slot (BORDER): on in the sky mode with no LODs drawn and a known render distance (blocks). */
    public static float[] border(boolean lodValid, float mode, float renderDistance) {
        boolean on = !lodValid && mode == AtmosphereMath.ATMOSPHERE_SKY && renderDistance > 0 && Float.isFinite(renderDistance);
        return on ? new float[]{renderDistance * BORDER_START_SHARE, renderDistance * BORDER_END_SHARE, 1} : new float[3];
    }

    public static boolean active(AtmosphereFrame frame, long serial, boolean hasSky) {
        return frame != null && frame.serial() == serial && hasSky;
    }

    public static Values compute(PostProcessSettings settings, AtmosphereFrame frame, long serial, boolean hasSky, boolean lodSizeMatches) {
        if (frame == null || frame.serial() != serial) {
            return new Values(false, new Matrix4f(), new Matrix4f(), new Matrix4f(), new float[BLOCK_FLOATS], 0, new float[3], 0.5f, 0.5f, new float[12],
                    new float[4], new float[3]);
        }
        float[] block = block(settings, frame, hasSky);
        float[] look = look(settings, frame, block);
        if (drawsNothing(settings)) block = none(block);
        float[] screen = sunScreen(frame);
        float shafts = shafts(settings, frame, block, screen);
        float[] colour = new float[3];
        if (shafts > 0) {
            float h = sunDirection(frame).y;
            float[] tint = ColourMath.sunLight(h);
            float low = (1 - clamp01(h)) * (1 - clamp01(h));
            float amount = (SHAFT_NOON + (SHAFT_LOW - SHAFT_NOON) * low) * shafts;
            for (int i = 0; i < 3; i++) colour[i] = Math.max(0, tint[i] * amount);
        }
        boolean lodValid = lodSizeMatches && frame.lodDrawn();
        return new Values(lodValid, frame.invVanillaProjection(), frame.invLodProjection(), frame.invModelView(), block,
                shafts, colour, screen[0], screen[1], vanillaColours(frame, block), look, border(lodValid, block[3], frame.skyFogEnd()));
    }

    /** Final fix R1: true when every setting that makes the atmosphere draw something is 0 (the composite then keeps vanilla's sky). */
    public static boolean drawsNothing(PostProcessSettings s) {
        return s != null && !(s.fogStrength() > 0) && !(s.sunGlow() > 0) && !(s.sunsetLight() > 0) && !(s.lightShafts() > 0)
                && !(s.moonlight() > 0) && !s.farClouds();
    }

    /** ATMOSPHERE_NONE, keeping the sun's direction and night() (block() of the drawn atmosphere in). */
    static float[] none(float[] drawn) {
        float[] b = new float[BLOCK_FLOATS];
        b[0] = drawn[0];
        b[1] = drawn[1];
        b[2] = drawn[2];
        b[3] = AtmosphereMath.ATMOSPHERE_NONE;
        b[11] = drawn[11];
        return b;
    }

    /**
     * Slot LOOK for a frame: the look weight (sun shading, 0 with the post-processing off), the camera in water, and the drawn block's
     * overcast share (ATMOSPHERE_SKY only) and mode, which the composite's exposures follow whether or not the atmosphere is drawn.
     */
    static float[] look(PostProcessSettings s, AtmosphereFrame f, float[] drawn) {
        float weight = s != null && s.enabled() ? clamp01(s.sunShading()) : 0;
        float overcast = drawn[3] == AtmosphereMath.ATMOSPHERE_SKY ? drawn[7] : 0;
        return new float[]{weight, f.cameraInWater() ? 1 : 0, overcast, drawn[3]};
    }

    /**
     * VANILLA_SKY, VANILLA_FOG and STAR_GATE for a frame: vanilla's sky colour (w 1 when known) and fog colour (w 1) in scene units, the
     * star weight (night() x (1 - rain), only in ATMOSPHERE_SKY) and the fan's strength (vanilla's sunrise colour alpha).
     */
    static float[] vanillaColours(AtmosphereFrame f, float[] block) {
        float[] v = new float[12];
        if (block[3] == AtmosphereMath.ATMOSPHERE_SKY) {
            v[8] = clamp01(block[11] * (1 - clamp01(f.rain())));
            float[] sunrise = f.sunrise();
            v[9] = sunrise != null && sunrise.length >= 4 ? clamp01(sunrise[3]) : 0;
        }
        float[] sky = f.skyColour();
        if (sky != null && sky.length >= 3) {
            for (int i = 0; i < 3; i++) v[i] = vanillaToScene(sky[i]);
            v[3] = 1;
        }
        v[4] = vanillaToScene(f.fogRed());
        v[5] = vanillaToScene(f.fogGreen());
        v[6] = vanillaToScene(f.fogBlue());
        v[7] = 1;
        return v;
    }

    /** The block's seven vec4 into the given slots (one per vec4, in field order); slots the shader does not declare are skipped. W6 calls it for the reflection. */
    public static void writeBlock(LooseUniformWriter w, float[] block, int... slots) {
        for (int i = 0; i < Math.min(slots.length, AtmosphereMath.Atmosphere.VEC4S); i++) {
            if (w.has(slots[i])) w.putVec4(slots[i], block[i * 4], block[i * 4 + 1], block[i * 4 + 2], block[i * 4 + 3]);
        }
    }

    /**
     * The struct Atmosphere for a frame (BLOCK_FLOATS floats, finite): ATMOSPHERE_NONE without settings or frame, ATMOSPHERE_FOG when
     * vanilla's environmental fog covers everything, ATMOSPHERE_NO_SKY without sky light, else ATMOSPHERE_SKY. Public for the reflection
     * (W6), which receives the frame and settings but not this class's Values.
     */
    public static float[] block(PostProcessSettings s, AtmosphereFrame f, boolean hasSky) {
        float[] b = new float[BLOCK_FLOATS];
        if (s == null || f == null) return b;
        Vector3f sun = sunDirection(f);
        b[0] = sun.x;
        b[1] = sun.y;
        b[2] = sun.z;
        float fogStrength = Math.max(0, finite(s.fogStrength()));
        float dayFade = AtmosphereMath.smoothstep(SUN_FADE_LOW, SUN_FADE_HIGH, sun.y);
        if (f.fogCoversAllRendering() || !hasSky) {
            boolean env = f.fogCoversAllRendering();
            b[3] = env ? AtmosphereMath.ATMOSPHERE_FOG : AtmosphereMath.ATMOSPHERE_NO_SKY;
            b[4] = vanillaToScene(f.fogRed());
            b[5] = vanillaToScene(f.fogGreen());
            b[6] = vanillaToScene(f.fogBlue());
            b[11] = hasSky ? 1 - dayFade : 0;
            b[24] = (env ? ENV_FOG_DENSITY : NO_SKY_FOG_DENSITY) * fogStrength;
            return b;
        }
        b[3] = AtmosphereMath.ATMOSPHERE_SKY;
        float rain = clamp01(f.rain()), thunder = clamp01(f.thunder());
        float sunset = clamp01(s.sunsetLight()) * SUNSET_SATURATION, moonlight = clamp01(s.moonlight()), glow = Math.max(0, finite(s.sunGlow()));
        float[] tauR = rayleighDepths();
        float tauM = HAZE_DEPTH * (1 + RAIN_HAZE * rain);
        float ms = AtmosphereMath.airMass(Math.max(sun.y, 0)), mm = AtmosphereMath.airMass(Math.max(-sun.y, 0));
        //The moon's light fades in as the sun's fades out (none in the sky while the sun is up)
        float moonFade = 1 - dayFade;
        float[] ts = transmittance(tauR, tauM, SKY_REDDENING, ms, sunset), ta = transmittance(tauR, tauM, AMBIENT_REDDENING, ms, sunset);
        //2026-10-24 (CTE2: "the sunset is only red"): the twilight dome takes the hue of the light the whole air column scatters, not of
        // the light at the ground, at the ground light's brightness
        float lift = 1 - AtmosphereMath.smoothstep(0, LIFT_SUN_HEIGHT, sun.y);
        float[] tc = columnTint(tauR, tauM, AMBIENT_REDDENING, ms, sunset, ta);
        for (int i = 0; i < 3; i++) tc[i] = ta[i] + (tc[i] - ta[i]) * lift;
        float[] tm = transmittance(tauR, tauM, SKY_REDDENING, mm, sunset), tam = transmittance(tauR, tauM, AMBIENT_REDDENING, mm, sunset);
        float[] tint = skyTint(f.skyColour());
        float dim = (1 - RAIN_DARK * rain) * (1 - THUNDER_DARK * thunder);
        float sunAmount = SKY_SUN * dayFade * (1 + DAY_SKY_BOOST * AtmosphereMath.smoothstep(DAY_BOOST_LOW, DAY_BOOST_HIGH, sun.y)), moonAmount = SKY_SUN * MOON_SKY * moonlight * moonFade;
        float glowWeather = (1 - GLOW_RAIN_CUT * rain) * (1 - THUNDER_DARK * thunder);
        float diffuse = RAIN_DIFFUSE * rain;
        float twilight = TWILIGHT_SKY * (1 - AtmosphereMath.smoothstep(0, TWILIGHT_HEIGHT, sun.y));
        twilight *= 1 - TWILIGHT_LIFT_CUT * lift;
        float[] blue = ambientBlue(tauR);
        for (int i = 0; i < 3; i++) {
            float sunLight = sunAmount * ts[i] * tint[i] * dim, moonLight = moonAmount * tm[i] * tint[i] * dim;
            b[4 + i] = sunLight * (1 - diffuse);
            b[8 + i] = moonLight * (1 - diffuse);
            b[12 + i] = tauR[i];
            float dome = sunAmount * (ta[i] * SKY_MULTI * blue[i] + tc[i] * twilight) + moonAmount * tam[i] * SKY_MULTI * blue[i];
            b[16 + i] = (dome * tint[i] + NIGHT_SKY[i]) * dim + (sunLight + moonLight) * diffuse;
            b[20 + i] = sunAmount * GLOW_AMOUNT * glow * ts[i] * glowWeather;
        }
        b[7] = clamp01(RAIN_GREY * Math.max(rain, thunder));
        b[11] = 1 - dayFade;
        b[15] = tauM;
        b[19] = fogStrength / AIR_MASS_BLOCKS;
        b[23] = GLOW_AMOUNT * glow * (1 - GLOW_RAIN_CUT * rain);
        float fogHeight = Math.max(PostProcessSettings.FOG_HEIGHT_MIN, finite(s.fogHeight()));
        b[24] = FOG_DENSITY * fogStrength * (1 + RAIN_FOG * rain);
        b[25] = 1 / fogHeight;
        b[26] = Math.max(0, finite(f.cameraY()) - finite(f.seaLevel()));
        //The sun's reddening air mass (the sunset light setting scales it, as transmittance does): atmosphere.glsl's airlight lifts it for
        // light scattered high in the air
        b[27] = lift * sunset * ms;
        return b;
    }

    /** The dome's blue: the Rayleigh depth raised to AMBIENT_BLUE, luminance 1. */
    static float[] ambientBlue(float[] tauR) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = (float) Math.pow(tauR[i], AMBIENT_BLUE);
        return ColourMath.normalised(c);
    }

    /** Rayleigh optical depth of one air mass at SKY_WAVELENGTHS (Hansen and Travis 1974, PostMath.rayleighDepth) times RAYLEIGH_SCALE. */
    static float[] rayleighDepths() {
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = RAYLEIGH_SCALE * PostMath.rayleighDepth(SKY_WAVELENGTHS[i]);
        return t;
    }

    /**
     * Light through `fraction` of the extinction (Rayleigh plus haze) and the whole ozone absorption along `mass` air masses, at the
     * luminance of that light and the hue of `saturation` times its optical depths (the sunset light setting: 0 leaves only its
     * luminance, 1 the computed colour). 2026-10-24: scaling the depths, not the hue's distance from grey, keeps every channel above 0 and
     * lets atmosphere.glsl undo the reddening by the same scaled depth (Atmosphere.fog.w).
     */
    static float[] transmittance(float[] tauR, float tauM, float fraction, float mass, float saturation) {
        float[] t = new float[3], s = new float[3];
        for (int i = 0; i < 3; i++) {
            float depth = (fraction * (tauR[i] + tauM) + OZONE_SCALE * OZONE_DEPTH[i]) * mass;
            t[i] = (float) Math.exp(-depth);
            s[i] = (float) Math.exp(-saturation * depth);
        }
        float l = PostMath.luminance(t[0], t[1], t[2]), ls = PostMath.luminance(s[0], s[1], s[2]);
        for (int i = 0; i < 3; i++) s[i] = ls > 0 ? s[i] * l / ls : l;
        return s;
    }

    /**
     * The hue of the sunlight the whole air column scatters (each height h of an exponential atmosphere, weighted by its density,
     * gets the light through exp(-h/H) of the column's depth: the mean is (1 - exp(-depth)) / depth) with the ozone above it all,
     * at the luminance of `like`. Saturation as in transmittance.
     */
    static float[] columnTint(float[] tauR, float tauM, float fraction, float mass, float saturation, float[] like) {
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            float depth = saturation * fraction * (tauR[i] + tauM) * mass;
            float mean = depth > 0.0001f ? (1 - (float) Math.exp(-depth)) / depth : 1 - 0.5f * depth;
            c[i] = mean * (float) Math.exp(-saturation * OZONE_SCALE * OZONE_DEPTH[i] * mass);
        }
        float l = PostMath.luminance(c[0], c[1], c[2]), want = PostMath.luminance(like[0], like[1], like[2]);
        for (int i = 0; i < 3; i++) c[i] = l > 0 ? c[i] * want / l : want;
        return c;
    }

    /** The sky's sunlight colour at a sun height, luminance 1, its colour kept by the sunset light setting (the far clouds' lit tint). */
    static float[] sunTint(float sunHeight, float sunsetLight) {
        float[] tauR = rayleighDepths();
        return ColourMath.normalised(transmittance(tauR, HAZE_DEPTH, SKY_REDDENING, AtmosphereMath.airMass(Math.max(sunHeight, 0)), clamp01(sunsetLight) * SUNSET_SATURATION));
    }

    /** Vanilla's sky colour (display values) as a hue tint of luminance 1 relative to its temperate sky; white when there is none or it is dark. */
    static float[] skyTint(float[] vanillaSky) {
        if (vanillaSky == null || vanillaSky.length < 3) return new float[]{1, 1, 1};
        float[] lin = new float[3], ref = new float[3];
        for (int i = 0; i < 3; i++) {
            lin[i] = vanillaToScene(vanillaSky[i]);
            ref[i] = vanillaToScene(SKY_TINT_REFERENCE[i]);
        }
        float l = PostMath.luminance(lin[0], lin[1], lin[2]), lr = PostMath.luminance(ref[0], ref[1], ref[2]);
        float weight = SKY_TINT * AtmosphereMath.smoothstep(0, SKY_TINT_FADE, l);
        if (!(weight > 0)) return new float[]{1, 1, 1};
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) {
            float ratio = AtmosphereMath.clamp((lin[i] / l) / (ref[i] / lr), SKY_TINT_MIN, SKY_TINT_MAX);
            t[i] = 1 + (ratio - 1) * weight;
        }
        return ColourMath.normalised(t);
    }

    static Vector3f sunDirection(AtmosphereFrame f) {
        Vector3f sun = SunMath.sunDirection(finite(f.timeOfDay()));
        return sun.lengthSquared() > 0 ? sun.normalize() : new Vector3f(0, 1, 0);
    }

    /** The sun's screen position (x right, y down from the top, 0..1 across the screen); far off screen (-10) when it is behind the camera. */
    static float[] sunScreen(AtmosphereFrame f) {
        if (f.viewProjection() == null) return new float[]{-10, -10};
        Vector3f sun = sunDirection(f);
        Vector4f clip = f.viewProjection().transform(new Vector4f(sun.x, sun.y, sun.z, 0));
        if (!(clip.w > 1e-6f) || !Float.isFinite(clip.x) || !Float.isFinite(clip.y)) return new float[]{-10, -10};
        float x = 0.5f + 0.5f * clip.x / clip.w, y = 0.5f - 0.5f * clip.y / clip.w;
        return new float[]{AtmosphereMath.clamp(x, -10, 10), AtmosphereMath.clamp(y, -10, 10)};
    }

    /** How strongly the screen-space shafts run (0 = not at all): sun above the horizon, on or within SHAFT_MARGIN of the screen, setting above 0, sky mode. */
    static float shafts(PostProcessSettings s, AtmosphereFrame f, float[] block, float[] screen) {
        if (s == null || !s.enabled() || !(s.lightShafts() > 0) || block[3] != AtmosphereMath.ATMOSPHERE_SKY) return 0;
        float h = sunDirection(f).y;
        float height = AtmosphereMath.smoothstep(0, SHAFT_HORIZON_FADE, h);
        if (!(h > 0) || !(height > 0)) return 0;
        float out = Math.max(Math.max(-screen[0], screen[0] - 1), Math.max(-screen[1], screen[1] - 1));
        float edge = 1 - AtmosphereMath.smoothstep(0, SHAFT_MARGIN, Math.max(out, 0));
        float weather = (1 - SHAFT_RAIN_CUT * clamp01(f.rain())) * (1 - SHAFT_THUNDER_CUT * clamp01(f.thunder()));
        float v = height * edge * weather;
        return v > 0 && Float.isFinite(v) ? v : 0;
    }

    /** A vanilla colour component (display 0..1) in scene units (W2's vanillaToScene). */
    static float vanillaToScene(float display) {
        return PostMath.displayToLinear(clamp01(display));
    }

    private static float finite(float v) {
        return Float.isFinite(v) ? v : 0;
    }

    private static float clamp01(float v) {
        return AtmosphereMath.clamp01(v);
    }
}
