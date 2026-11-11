package net.vulkanmod.visuals.render;

/**
 * 2026-11-11 (shader roadmap session 12, the user: "set the number, size and brightness of the stars at night"): the star settings.
 * The stars are vanilla's own (LevelRenderer.drawStars: VANILLA_STARS tries of a random point in a cube, kept inside the unit ball, a
 * square of half size VANILLA_SIZE_BASE + r VANILLA_SIZE_SPREAD blocks at 100 blocks, drawn additively with the shader colour
 * (b, b, b, b), b = ClientLevel.getStarBrightness x (1 - rain)), which the composite keeps on the sky (atmosphere.glsl vanillaStars).
 * So the settings change vanilla's star buffer and draw (StarSky, LevelRendererStarsMixin), not the shaders, and cost nothing per pixel:
 * <ul>
 * <li>count (percent): the number of tries, from the same seed, so 100 is vanilla's sky and any count keeps the stars of a smaller one;
 * vanilla keeps about pi/6 of the tries (about 785 stars at 100)</li>
 * <li>size (percent): every star's half size</li>
 * <li>brightness (percent): the light each star adds. Vanilla's draw adds b x b (the colour times its alpha), so the colour b becomes
 * b sqrt(brightness): the added light scales with the setting, and at BRIGHTNESS_MAX the night's b (at most 0.5) reaches 1, not past
 * it (clamped)</li>
 * </ul>
 * At 100 every value is vanilla's exactly (x 1.0f), so the defaults draw the same sky as before. The night, dawn, rain and the far
 * clouds still fade or cover them as before.
 */
public final class StarMath {
    /** Vanilla's tries, seed and size (LevelRenderer.drawStars). */
    public static final int VANILLA_STARS = 1500;
    public static final float VANILLA_SIZE_BASE = 0.15f, VANILLA_SIZE_SPREAD = 0.1f;
    /** ClientLevel.getStarBrightness's largest value (deep night). */
    public static final float VANILLA_BRIGHTNESS_MAX = 0.5f;
    /** The settings' ranges (percent, 100 = vanilla's). */
    public static final int COUNT_MAX = 400, SIZE_MIN = 50, SIZE_MAX = 300, BRIGHTNESS_MAX = 400;
    /**
     * The most tries the buffer may hold: vanilla's QUADS index buffer (AutoIndexBuffer QUAD_U16_MAX_VERTEX_COUNT, 65536 vertices) has
     * room for 16384 quads; every try that is kept is one quad.
     */
    public static final int MAX_TRIES = 16384;

    private StarMath() {
    }

    /**
     * 2026-11-11 (session 13): the stars' shape. TYPE_VANILLA: vanilla's flat squares. TYPE_ROUND: each star's quad carries its corners'
     * texture coordinates and is drawn with a soft round point (StarSky.roundTexture: opaque inside ROUND_CORE of its half size, fading to
     * nothing at its edge) through vanilla's position-texture shader and the same additive draw; the half size is ROUND_SIZE x larger so a
     * round star adds about the light its square did (the point's alpha sums to about 1 / ROUND_SIZE^2 of the square's).
     */
    public static final int TYPE_VANILLA = 0, TYPE_ROUND = 1, TYPE_MAX = 1;
    public static final float ROUND_CORE = 0.35f, ROUND_SIZE = 1.6f;
    /** The round point's texture size (texels a side). */
    public static final int ROUND_TEXTURE = 32;

    /** The round point's alpha at d (0 centre .. 1 edge of the quad's inscribed circle). */
    public static float roundAlpha(float d) {
        if (d >= 1f) return 0f;
        if (d <= ROUND_CORE) return 1f;
        float t = (d - ROUND_CORE) / (1f - ROUND_CORE);
        float s = 1f - t;
        return s * s * (3f - 2f * s);
    }

    /** The texture u, v of corner i (0..3) of vanilla's star quad (its corners go (-1, -1), (-1, 1), (1, 1), (1, -1)). */
    public static float cornerU(int i) {
        return (i & 2) == 0 ? 0f : 1f;
    }

    public static float cornerV(int i) {
        return ((i + 1) & 2) == 0 ? 0f : 1f;
    }

    /** The number of tries for a count setting (percent): VANILLA_STARS at 100. */
    public static int tries(int countPercent) {
        int p = Math.max(0, Math.min(COUNT_MAX, countPercent));
        if (p == 100) return VANILLA_STARS;
        return Math.min(MAX_TRIES, Math.round(VANILLA_STARS * (p / 100f)));
    }

    /** The factor on every star's half size for a size setting (percent): exactly 1 at 100. */
    public static float sizeScale(int sizePercent) {
        int p = Math.max(SIZE_MIN, Math.min(SIZE_MAX, sizePercent));
        return p == 100 ? 1f : p / 100f;
    }

    /**
     * The shader colour's value for the stars' draw: vanilla's b (getStarBrightness x (1 - rain)) at a brightness setting (percent).
     * The draw adds the value squared, so this is b sqrt(brightness / 100), at most 1; exactly b at 100, 0 at 0 (no draw).
     */
    public static float drawValue(float vanilla, int brightnessPercent) {
        int p = Math.max(0, Math.min(BRIGHTNESS_MAX, brightnessPercent));
        if (p == 100) return vanilla;
        if (p == 0 || !(vanilla > 0)) return 0f;
        return Math.min(1f, vanilla * (float) Math.sqrt(p / 100.0));
    }

    /** The light one star adds (the draw's value times its alpha, the same value): what the brightness setting scales. */
    public static float addedLight(float drawValue) {
        return drawValue * drawValue;
    }
}
