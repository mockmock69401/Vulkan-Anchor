package net.vulkanmod.visuals.render;

/**
 * 2026-11-15 (shader roadmap session 25, the user: "사막에서는 밤하늘이 조금 더 어둡고 채도가 높았으면", "사막에서는 구름이 없게"): how much of
 * a desert the camera stands in, and what that does to the sky. Pure math (BiomeSkyTest); BiomeSky reads the level and keeps the state.
 * <ul>
 * <li>A biome is a desert (1) or not (0) by classify, from an explicit list (2026-11-15 follow-up, the user: "구름이 있어야 하는 바이옴까지
 * 전부 구름이 없어지는 현상 ... 사막에 해당하는 바이옴만 정확하게 뽑아서"): it never rains or snows there (no precipitation) and either its id is
 * in DESERT_BIOMES (vanilla's desert and badlands, and the deserts, dunes, badlands, mesas and oases of every supported pack's mods, picked
 * from their biome data: session25-biomes.md in the notes) or a mod tags it a desert or badlands (c:is_desert, forge:is_desert,
 * minecraft:is_badlands, c:is_badlands, forge:is_badlands: a mod outside those packs). Anything else, an unknown modded biome included, is
 * not a desert: savannas, shrublands, steppes, beaches, volcanic and ashen land, cold deserts and biomes where it rains keep their sky.</li>
 * <li>The camera's desert share is the mean of a KERNEL x KERNEL grid of columns SPACING blocks apart around it, weighted by a Gaussian of
 * SIGMA blocks (kernel), so the share ramps over about 50 blocks of walking across a border instead of switching at the line; then it
 * follows that target over FOLLOW_SECONDS (follow), so a teleport fades too.</li>
 * <li>The desert's night sky: the night-sky light and the moon's scattered light (AtmosphereUniforms' nightTint) take DESERT_NIGHT_TINT,
 * darker and deep indigo-violet (luminance about 0.72 of it, blue more than doubled: dry, clear air), times the share and the Desert Night Sky setting
 * (nightTint); the stars' weight takes 1 + DESERT_STAR_BOOST of it (starBoost: the dry air keeps them crisp and bright).</li>
 * <li>The desert's clear sky: the far clouds (every style) fade by the share times the Desert Clear Sky setting (cloudClear), which
 * clouds.glsl's cloudColour applies to the colour and the coverage; the realistic and anime cumulus also shrink by it (cover).</li>
 * </ul>
 */
public final class BiomeSkyMath {
    /** The settings' ranges (percent): the night sky's strength, the clouds' clearing. */
    public static final int NIGHT_MAX = 200, CLEAR_MAX = 100;
    /** The deserts by id (see the class comment); a biome on it still needs no precipitation. */
    public static final java.util.Set<String> DESERT_BIOMES = java.util.Set.of(
            //vanilla
            "minecraft:desert", "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands",
            //Atmospheric (BMC4, Prominence II)
            "atmospheric:dunes", "atmospheric:flourishing_dunes", "atmospheric:petrified_dunes", "atmospheric:rocky_dunes", "atmospheric:scrubland",
            "atmospheric:spiny_thicket",
            //Biomes O' Plenty
            "biomesoplenty:dryland", "biomesoplenty:wasteland",
            //Oh The Biomes We've Gone
            "biomeswevegone:atacama_outback", "biomeswevegone:mojave_desert", "biomeswevegone:windswept_desert",
            //Regions Unexplored
            "regions_unexplored:joshua_desert", "regions_unexplored:saguaro_desert", "regions_unexplored:outback", "regions_unexplored:arid_mountains",
            //Tensura
            "tensura:desert_of_death", "tensura:barren_land", "tensura:underworld_sands", "tensura:underworld_red_sands",
            //Terralith
            "terralith:ancient_sands", "terralith:bryce_canyon", "terralith:desert_canyon", "terralith:desert_oasis", "terralith:desert_spires",
            "terralith:lush_desert", "terralith:red_oasis", "terralith:sandstone_valley", "terralith:savanna_badlands", "terralith:warped_mesa",
            "terralith:white_mesa",
            //other dimensions' deserts (their own sky decides whether the desert sky applies)
            "allthemodium:desert", "allthemodium:desert_hills", "chronodawn:chronodawn_desert", "eternal_starlight:crystallized_desert",
            "blue_skies:crystal_dunes", "blue_skies:crystal_dunes_spikes");
    /** The sampling grid: KERNEL x KERNEL columns SPACING blocks apart, weighted by a Gaussian of SIGMA blocks. */
    public static final int KERNEL = 5, SPACING = 12;
    public static final float SIGMA = 20f;
    /** The share follows its target with this time constant (seconds); the grid is sampled again every RESAMPLE_SECONDS. */
    public static final float FOLLOW_SECONDS = 2.5f, RESAMPLE_SECONDS = 0.25f;
    /** The desert's night sky tint at full share and 100%: linear rgb on the night light (darker, toward indigo-violet). The Deep Indigo colour. */
    public static final float[] DESERT_NIGHT_TINT = {0.80f, 0.55f, 2.2f};
    /**
     * 2026-11-17 (the user: "사막에서 하늘 색도 선택할 수 있게 빼고 비슷하게 교정시켜줘", after a desert night in Genshin Impact: visual reference
     * only): the Desert Night Colour setting. STARLIT_VIOLET (the default): a rich blue-violet night, lighter and hazier toward the
     * horizon (the air's own scattering of the brighter night light), a lavender Milky Way packed with fine stars across it, a bright moon
     * with a soft glow. DEEP_INDIGO: session 25's darker deep indigo. MOONLIT_BLUE: a bright clear blue night with a pale blue Milky Way.
     * PALETTE: the palette's own night colour (the stars still brighter).
     */
    public static final int STARLIT_VIOLET = 0, DEEP_INDIGO = 1, MOONLIT_BLUE = 2, PALETTE = 3, COLOUR_MAX = 3;
    /**
     * Per colour: the night's own hue (linear rgb at luminance 1), which replaces the palette's night hue and keeps its brightness times
     * COLOUR_BRIGHT, so a desert night is the colour picked under any palette (and whatever the general night colour is); null: a fixed
     * tint on the palette's colour (Deep Indigo: DESERT_NIGHT_TINT; Palette: none).
     */
    static final float[][] COLOUR_HUE = {
            {1.07f, 0.57f, 4.9f},
            null,
            {0.09f, 0.89f, 4.85f},
            null};
    static final float[] COLOUR_BRIGHT = {2.6f, 1f, 3.4f, 1f};
    /** Per colour without a hue: its fixed tint. */
    static final float[][] COLOUR_TINT = {null, DESERT_NIGHT_TINT, null, {1f, 1f, 1f}};
    /** Per colour: the Milky Way's share over the Vanilla and Round star types (the Anime type always draws it), and its lavender share. */
    static final float[] COLOUR_BAND = {1f, 0f, 1f, 0f}, COLOUR_LAVENDER = {1f, 0f, 0f, 0f};
    /** Per colour: the moon's glow, times (1 at share 0). */
    static final float[] COLOUR_MOON_GLOW = {2.2f, 1f, 1.8f, 1f};
    /** The stars' extra weight at full share and 100%. */
    public static final float DESERT_STAR_BOOST = 1.5f;
    /** atmosphere.glsl vanillaStars' cap on the star weight (it was 1 before session 25, and the weight never passed 1 then). */
    public static final float STAR_GATE_MAX = 4.0f;

    private static final float[] WEIGHTS = weights();

    private BiomeSkyMath() {
    }

    /** 1 for a desert, 0 otherwise (see the class comment): id the biome's id (null: unknown), tagged a desert or badlands tag on it. */
    public static float classify(String id, boolean tagged, boolean precipitation) {
        if (precipitation) return 0;
        return (id != null && DESERT_BIOMES.contains(id)) || tagged ? 1 : 0;
    }

    /** The grid's offset in blocks of sample i (0 .. KERNEL^2 - 1): x for axis 0, z for axis 1. */
    public static int offset(int i, int axis) {
        int half = KERNEL / 2;
        return ((axis == 0 ? i % KERNEL : i / KERNEL) - half) * SPACING;
    }

    /** The normalised Gaussian weight of sample i. */
    public static float weight(int i) {
        return WEIGHTS[i];
    }

    private static float[] weights() {
        float[] w = new float[KERNEL * KERNEL];
        float sum = 0;
        for (int i = 0; i < w.length; i++) {
            float dx = offset(i, 0), dz = offset(i, 1);
            w[i] = (float) Math.exp(-(dx * dx + dz * dz) / (2 * SIGMA * SIGMA));
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] /= sum;
        return w;
    }

    /** The weighted mean of the grid's samples (each 0..1; KERNEL^2 of them), 0..1. */
    public static float kernel(float[] samples) {
        float s = 0;
        for (int i = 0; i < WEIGHTS.length && i < samples.length; i++) s += WEIGHTS[i] * clamp01(samples[i]);
        return clamp01(s);
    }

    /** The share after dt seconds following target (exponential, FOLLOW_SECONDS); a NaN current jumps to the target. */
    public static float follow(float current, float target, float dt) {
        if (!Float.isFinite(current)) return clamp01(target);
        float k = dt > 0 && Float.isFinite(dt) ? 1 - (float) Math.exp(-dt / FOLLOW_SECONDS) : 0;
        return current + (clamp01(target) - current) * k;
    }

    /** The night sky's tint for a desert share (0..1) and the Desert Night Sky setting (percent), Deep Indigo: exactly 1 at share 0 or setting 0. */
    public static float[] nightTint(float desert, int setting) {
        return nightTint(desert, setting, DEEP_INDIGO, new float[]{1, 1, 1});
    }

    /**
     * The night sky's tint for a desert share, the Desert Night Sky setting and the Desert Night Colour, on base (the palette's night
     * colour, linear rgb: AtmosphereUniforms' NIGHT_SKY x its night tint): exactly 1 at share 0 or setting 0; a colour with a hue turns
     * base into that hue at base's luminance x COLOUR_BRIGHT.
     */
    public static float[] nightTint(float desert, int setting, int colour, float[] base) {
        float e = strength(desert, setting);
        int k = colour(colour);
        float[] full = COLOUR_TINT[k];
        if (COLOUR_HUE[k] != null) {
            full = new float[3];
            float l = Math.max(luminance(base), 1e-6f) * COLOUR_BRIGHT[k] / luminance(COLOUR_HUE[k]);
            for (int i = 0; i < 3; i++) full[i] = COLOUR_HUE[k][i] * l / Math.max(base[i], 1e-6f);
        }
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) t[i] = Math.max(0, 1 + (full[i] - 1) * e);
        return t;
    }

    /** The Rec. 709 luminance of linear rgb (0 for a missing or non-finite colour). */
    static float luminance(float[] c) {
        if (c == null || c.length < 3) return 0;
        float l = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        return Float.isFinite(l) ? l : 0;
    }

    /** The desert Milky Way's share over the Vanilla and Round star types (0..1): 0 at share 0, setting 0, or a colour without it. */
    public static float band(float desert, int setting, int colour) {
        return Math.min(1, strength(desert, setting)) * COLOUR_BAND[colour(colour)];
    }

    /** How far the Milky Way's colour turns lavender (0..1). */
    public static float lavender(float desert, int setting, int colour) {
        return Math.min(1, strength(desert, setting)) * COLOUR_LAVENDER[colour(colour)];
    }

    /** The moon's glow factor (exactly 1 at share 0 or setting 0). */
    public static float moonGlow(float desert, int setting, int colour) {
        return 1 + (COLOUR_MOON_GLOW[colour(colour)] - 1) * strength(desert, setting);
    }

    /** The colour setting kept in range (anything else: the default). */
    public static int colour(int colour) {
        return colour >= 0 && colour <= COLOUR_MAX ? colour : STARLIT_VIOLET;
    }

    private static float strength(float desert, int setting) {
        return clamp01(desert) * Math.max(0, Math.min(NIGHT_MAX, setting)) / 100f;
    }

    /** The stars' weight factor for a desert share and the Desert Night Sky setting: exactly 1 at share 0 or setting 0. */
    public static float starBoost(float desert, int setting) {
        return 1 + DESERT_STAR_BOOST * clamp01(desert) * Math.max(0, Math.min(NIGHT_MAX, setting)) / 100f;
    }

    /** The far clouds' clearing (0 all kept .. 1 none) for a desert share and the Desert Clear Sky setting (percent). */
    public static float cloudClear(float desert, int setting) {
        return clamp01(desert) * Math.max(0, Math.min(CLEAR_MAX, setting)) / 100f;
    }

    static float clamp01(float v) {
        return Float.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }
}
