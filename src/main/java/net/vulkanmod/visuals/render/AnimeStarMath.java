package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-16 (shader roadmap session 12 follow-up, the user: "add a way to show the night sky's stars like Genshin Impact"): the
 * anime star type (StarMath.TYPE_ANIME). Mirrors animestars.glsl's consts and its Java-checkable parts (AnimeStarTest compares them).
 * <p>
 * THE LOOK (from the user's screenshot only, clean room): a painted night, not a photograph. A Milky Way band across the sky, a soft
 * teal-cyan glow with darker gaps, packed with very fine dust stars (AnimeSkyMap bakes the band and the dust's density once into a small
 * map); crisp white points of a few distinct sizes over the whole sky, the brightest with a small soft glow; all fading toward the
 * horizon. The deep blue of the sky itself is the night sky colour (the palettes'), not this.
 * <p>
 * SCATTER (2026-11-17, the user: "the stars look lined up on a grid; scatter them more randomly, like Genshin"): a star lies anywhere in
 * its cell (it sat in the middle third, which read as rows), each layer's grid is turned its own way, and AnimeSkyMap's clump field
 * gathers the small and big stars in places and thins them in others. Above 100 % the count makes the grids finer instead of filling
 * every cell. animestars.glsl has the details.
 * <p>
 * THE SKY FRAME: the stars turn with the sun as vanilla's do (vanilla draws its stars and sun in one frame turning about the z axis), so
 * the band rises and sets through the night. skyDir turns a world direction into that frame: the sun's direction goes to straight up.
 * <p>
 * SLOT (the composite's ANIME_SLOT, scene_copy.comp animeStarParams): x the brightness (setting / 100; 0: the type is off or the
 * composite draws no sky: nothing evaluated), y the count (setting / 100), z the size (setting / 100), w 1 once the band's map is
 * uploaded (0: the stars alone). 2026-11-17: x negative: only the Milky Way (its glow and dust stars) at brightness -x, a desert's over the
 * Vanilla and Round star types (STAR_GATE z its share, w how far it turns lavender: BiomeSkyMath.band, lavender). The star weight (night, rain, a desert's boost) and the fan are the vanilla stars' (STAR_GATE); below 1
 * the weight is squared, as vanilla's stars add b x b, so they fade at dawn and dusk as vanilla's do.
 */
public final class AnimeStarMath {
    public static final int ANIME_SLOT = 76;

    //Star layers: cells per cube-map face edge, the chance a cell holds a star (the dust's times the band's density), the light
    public static final float ANIME_DUST_CELLS = 500.0f;
    public static final float ANIME_DUST_CHANCE = 0.16f;
    public static final float ANIME_DUST_LIGHT = 0.35f;
    public static final float ANIME_SMALL_CELLS = 120.0f;
    public static final float ANIME_SMALL_CHANCE = 0.22f;
    public static final float ANIME_SMALL_BAND = 0.18f;
    public static final float ANIME_SMALL_LIGHT = 1.0f;
    public static final float ANIME_BIG_CELLS = 40.0f;
    public static final float ANIME_BIG_CHANCE = 0.12f;
    public static final float ANIME_BIG_LIGHT = 3.2f;
    //A star's width (pixels, e^-1/2; anywhere in its cell, at most a sixth of a cell); the sizes of its class (x the width and the light):
    // most are SIZE_A, then SIZE_B, the rarest SIZE_C (the class from a hash: under CLASS_B, under CLASS_C, above)
    public static final float ANIME_STAR_SIGMA = 0.55f;
    public static final float ANIME_SIZE_A = 1.0f;
    public static final float ANIME_SIZE_B = 1.7f;
    public static final float ANIME_SIZE_C = 2.6f;
    public static final float ANIME_CLASS_B = 0.62f;
    public static final float ANIME_CLASS_C = 0.9f;
    //The big stars' glow: GLOW_WIDTH x their width (at most a sixth of a cell, as the star), GLOW_SHARE of their peak
    public static final float ANIME_GLOW_WIDTH = 3.0f;
    public static final float ANIME_GLOW_SHARE = 0.16f;
    //The stars' and the band's light (scene units at brightness 1, star weight 1), the band's colour
    public static final float ANIME_STAR_BRIGHTNESS = 0.11f;
    public static final float ANIME_BAND_BRIGHTNESS = 0.07f;
    public static final float ANIME_BAND_R = 0.32f;
    public static final float ANIME_BAND_G = 0.78f;
    public static final float ANIME_BAND_B = 1.0f;
    //2026-11-17: a desert's band and dust stars (BiomeSkyMath.lavender mixes toward them): a cool blue band with less green than the
    // teal one, which reads lavender over the violet sky
    public static final float ANIME_LAVENDER_R = 0.42f;
    public static final float ANIME_LAVENDER_G = 0.45f;
    public static final float ANIME_LAVENDER_B = 1.0f;
    public static final float ANIME_LAVENDER_DUST_R = 0.9f;
    public static final float ANIME_LAVENDER_DUST_G = 0.86f;
    public static final float ANIME_LAVENDER_DUST_B = 1.0f;
    //The dust stars' colour (a little cyan, the band's) and the other stars' cool white
    public static final float ANIME_DUST_R = 0.78f;
    public static final float ANIME_DUST_G = 0.95f;
    public static final float ANIME_DUST_B = 1.0f;
    public static final float ANIME_COOL_R = 0.9f;
    public static final float ANIME_COOL_G = 0.95f;
    public static final float ANIME_COOL_B = 1.0f;
    //Toward the horizon (world direction's y) everything fades: 0 at HORIZON_LOW, full at HORIZON_HIGH
    public static final float ANIME_HORIZON_LOW = 0.0f;
    public static final float ANIME_HORIZON_HIGH = 0.32f;
    //No stars within MOON_MARGIN x the round moon's radius (x vanilla's mask around the square moon); see moonKeep
    public static final float ANIME_MOON_MARGIN = 1.25f;
    //2026-11-17: the clump field's hold on the small and big stars' chance: x (LOW + GAIN x clump), the clump 0..1 (AnimeSkyMap's b)
    public static final float ANIME_CLUMP_LOW = 0.2f;
    public static final float ANIME_CLUMP_GAIN = 1.6f;

    private AnimeStarMath() {
    }

    /** animestars.glsl animeSkyDir: dir in the stars' frame, the sun (sunX, sunY: its world x, y) going to straight up. */
    public static float[] skyDir(float[] dir, float sunX, float sunY) {
        float l = (float) Math.sqrt(sunX * sunX + sunY * sunY);
        if (!(l > 1e-4f)) return dir.clone();
        float sx = sunX / l, sy = sunY / l;
        return new float[]{sy * dir[0] - sx * dir[1], sx * dir[0] + sy * dir[1], dir[2]};
    }

    /** animestars.glsl animeHorizon: the fade toward the horizon for a world direction's y. */
    public static float horizon(float y) {
        float t = Math.max(0, Math.min(1, (y - ANIME_HORIZON_LOW) / (ANIME_HORIZON_HIGH - ANIME_HORIZON_LOW)));
        return t * t * (3 - 2 * t);
    }

    /**
     * animestars.glsl animeMoonKeep (2026-11-17, session 15 follow-up: a dark square around the round moon over the band): what the moon
     * (direction moon, the celestial slot's shape and size) leaves of the band [0] and of the stars [1] along dir. The round moon's disc
     * hides both, and the stars stay MOON_MARGIN of its radius away; behind vanilla's additive square moon the band goes on and only the
     * stars stay out of its square.
     */
    public static float[] moonKeep(float[] dir, float[] moon, float[] celestial) {
        if (!WaterMath.celestialRound(celestial)) {
            return new float[]{1, 1 - WaterMath.discCover(dir, moon, WaterMath.VANILLA_MOON_MASK * ANIME_MOON_MARGIN)};
        }
        float[] uv = WaterMath.discPlane(dir, moon);
        if (uv == null) return new float[]{1, 1};
        float r = WaterMath.MOON_ROUND_RADIUS * WaterMath.moonScale(celestial);
        return new float[]{1 - WaterMath.roundCover(uv, r), 1 - WaterMath.roundCover(uv, r * ANIME_MOON_MARGIN)};
    }

    /** The size class's factor for a hash 0..1 (animestars.glsl animeSizeClass). */
    public static float sizeClass(float h) {
        return h < ANIME_CLASS_B ? ANIME_SIZE_A : (h < ANIME_CLASS_C ? ANIME_SIZE_B : ANIME_SIZE_C);
    }

    /**
     * The slot's four values for the settings c (null: the post-process off), whether the composite draws the sky this frame (the
     * atmosphere's ATMOSPHERE_SKY) and whether the band's map is uploaded. All 0 unless the type is anime and the sky is drawn.
     */
    public static float[] params(VisualsConfig c, boolean sky, boolean bandReady) {
        return params(c, sky, bandReady, 0);
    }

    /**
     * 2026-11-17: as params, and with another star type a desert's Milky Way alone (desertBand, BiomeSky.band: x the negative brightness,
     * once the map is up; nothing at share 0).
     */
    public static float[] params(VisualsConfig c, boolean sky, boolean bandReady, float desertBand) {
        if (c == null || !c.enabled || !sky) return new float[4];
        boolean anime = c.starType == StarMath.TYPE_ANIME;
        if (!anime && !(desertBand > 0 && bandReady)) return new float[4];
        float brightness = Math.max(0, Math.min(StarMath.BRIGHTNESS_MAX, c.starBrightness)) / 100f;
        if (!(brightness > 0)) return new float[4];
        float count = Math.max(0, Math.min(StarMath.COUNT_MAX, c.starCount)) / 100f;
        float size = Math.max(StarMath.SIZE_MIN, Math.min(StarMath.SIZE_MAX, c.starSize)) / 100f;
        return new float[]{anime ? brightness : -brightness, count, size, bandReady ? 1 : 0};
    }

    /** 2026-11-17 (animestars.glsl animeBandColour): the band's colour turned lavender by share 0..1. */
    public static float[] bandColour(float lavender) {
        float t = Math.max(0, Math.min(1, Float.isNaN(lavender) ? 0 : lavender));
        return new float[]{ANIME_BAND_R + (ANIME_LAVENDER_R - ANIME_BAND_R) * t, ANIME_BAND_G + (ANIME_LAVENDER_G - ANIME_BAND_G) * t,
                ANIME_BAND_B + (ANIME_LAVENDER_B - ANIME_BAND_B) * t};
    }
}
