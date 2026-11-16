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
 * THE SKY FRAME: the stars turn with the sun as vanilla's do (vanilla draws its stars and sun in one frame turning about the z axis), so
 * the band rises and sets through the night. skyDir turns a world direction into that frame: the sun's direction goes to straight up.
 * <p>
 * SLOT (the composite's ANIME_SLOT, scene_copy.comp animeStarParams): x the brightness (setting / 100; 0: the type is off or the
 * composite draws no sky: nothing evaluated), y the count (setting / 100), z the size (setting / 100), w 1 once the band's map is
 * uploaded (0: the stars alone). The star weight (night, rain, a desert's boost) and the fan are the vanilla stars' (STAR_GATE); below 1
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
    //A star's width (pixels, e^-1/2), its centre within the middle INSET..1 - INSET of its cell; the sizes of its class (x the width and
    // the light): most are SIZE_A, then SIZE_B, the rarest SIZE_C (the class from a hash: under CLASS_B, under CLASS_C, above)
    public static final float ANIME_STAR_SIGMA = 0.55f;
    public static final float ANIME_STAR_INSET = 0.32f;
    public static final float ANIME_SIZE_A = 1.0f;
    public static final float ANIME_SIZE_B = 1.7f;
    public static final float ANIME_SIZE_C = 2.6f;
    public static final float ANIME_CLASS_B = 0.62f;
    public static final float ANIME_CLASS_C = 0.9f;
    //The big stars' glow: GLOW_WIDTH x their width, at most GLOW_CELL of their cell (three times that stays inside the INSET margin),
    // GLOW_SHARE of their peak
    public static final float ANIME_GLOW_WIDTH = 3.0f;
    public static final float ANIME_GLOW_CELL = 0.08f;
    public static final float ANIME_GLOW_SHARE = 0.16f;
    //The stars' and the band's light (scene units at brightness 1, star weight 1), the band's colour
    public static final float ANIME_STAR_BRIGHTNESS = 0.11f;
    public static final float ANIME_BAND_BRIGHTNESS = 0.07f;
    public static final float ANIME_BAND_R = 0.32f;
    public static final float ANIME_BAND_G = 0.78f;
    public static final float ANIME_BAND_B = 1.0f;
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
    //Around the moon (its round radius or vanilla's mask, the larger) x MOON_MARGIN no stars
    public static final float ANIME_MOON_MARGIN = 1.25f;

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

    /** The size class's factor for a hash 0..1 (animestars.glsl animeSizeClass). */
    public static float sizeClass(float h) {
        return h < ANIME_CLASS_B ? ANIME_SIZE_A : (h < ANIME_CLASS_C ? ANIME_SIZE_B : ANIME_SIZE_C);
    }

    /**
     * The slot's four values for the settings c (null: the post-process off), whether the composite draws the sky this frame (the
     * atmosphere's ATMOSPHERE_SKY) and whether the band's map is uploaded. All 0 unless the type is anime and the sky is drawn.
     */
    public static float[] params(VisualsConfig c, boolean sky, boolean bandReady) {
        if (c == null || !c.enabled || !sky || c.starType != StarMath.TYPE_ANIME) return new float[4];
        float brightness = Math.max(0, Math.min(StarMath.BRIGHTNESS_MAX, c.starBrightness)) / 100f;
        if (!(brightness > 0)) return new float[4];
        float count = Math.max(0, Math.min(StarMath.COUNT_MAX, c.starCount)) / 100f;
        float size = Math.max(StarMath.SIZE_MIN, Math.min(StarMath.SIZE_MAX, c.starSize)) / 100f;
        return new float[]{brightness, count, size, bandReady ? 1 : 0};
    }
}
