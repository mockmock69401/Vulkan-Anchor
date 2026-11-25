package net.vulkanmod.visuals.render;

/**
 * 2026-11-23 (the user: the End's background like outer space, after four screenshots): the Java mirror of endsky.glsl (the End's
 * Space and Anime skies). Every const there is here under the same name, and its two gradients (EndSkyTest compares them). Colours
 * are display sRGB 0..255 in the consts, scene units (PostMath.displayToLinear) out of the functions; directions world-space unit
 * vectors {x, y, z}.
 */
public final class EndSkyMath {
    public static final float END_SKY_SPACE = 1.0f;
    public static final float END_SKY_ANIME = 2.0f;
    /** 2026-11-25: Horror's black End sky. */
    public static final float END_SKY_HORROR = 3.0f;
    //Space: the zenith, the haze on the horizon (its height scale: sine of the elevation), the void far below
    public static final float END_SPACE_ZENITH_R = 2.0f;
    public static final float END_SPACE_ZENITH_G = 5.0f;
    public static final float END_SPACE_ZENITH_B = 20.0f;
    public static final float END_SPACE_HAZE_R = 20.0f;
    public static final float END_SPACE_HAZE_G = 32.0f;
    public static final float END_SPACE_HAZE_B = 74.0f;
    public static final float END_SPACE_HAZE_HEIGHT = 0.16f;
    public static final float END_SPACE_DEEP_R = 1.0f;
    public static final float END_SPACE_DEEP_G = 2.0f;
    public static final float END_SPACE_DEEP_B = 9.0f;
    //How far below the horizon (sine) the haze fades into the void
    public static final float END_SPACE_DEEP_REACH = 0.35f;
    //Anime: the gradient's stops from the horizon up (display sRGB) and their elevations (radians)
    public static final float END_ANIME_HORIZON_R = 218.0f;
    public static final float END_ANIME_HORIZON_G = 186.0f;
    public static final float END_ANIME_HORIZON_B = 208.0f;
    public static final float END_ANIME_PINK_R = 200.0f;
    public static final float END_ANIME_PINK_G = 150.0f;
    public static final float END_ANIME_PINK_B = 198.0f;
    public static final float END_ANIME_PINK_AT = 0.06f;
    public static final float END_ANIME_LAVENDER_R = 122.0f;
    public static final float END_ANIME_LAVENDER_G = 110.0f;
    public static final float END_ANIME_LAVENDER_B = 202.0f;
    public static final float END_ANIME_LAVENDER_AT = 0.2f;
    public static final float END_ANIME_MID_R = 60.0f;
    public static final float END_ANIME_MID_G = 56.0f;
    public static final float END_ANIME_MID_B = 172.0f;
    public static final float END_ANIME_MID_AT = 0.55f;
    public static final float END_ANIME_ZENITH_R = 22.0f;
    public static final float END_ANIME_ZENITH_G = 20.0f;
    public static final float END_ANIME_ZENITH_B = 100.0f;
    //Below the horizon (the void): a dim violet at END_ANIME_DEEP_REACH (sine) and under
    public static final float END_ANIME_DEEP_R = 48.0f;
    public static final float END_ANIME_DEEP_G = 42.0f;
    public static final float END_ANIME_DEEP_B = 112.0f;
    public static final float END_ANIME_DEEP_REACH = 0.3f;
    //The horizon glow on the star's side: the lower stops times 1 - SIDE .. 1 + SIDE around the sky
    public static final float END_ANIME_SIDE = 0.18f;
    //The bright star (unit direction) and its look: the core's and the glow's widths (radians), their light, the spikes' length and light
    public static final float END_STAR_X = 0.5f;
    public static final float END_STAR_Y = 0.62f;
    public static final float END_STAR_Z = -0.6042f;
    public static final float END_STAR_CORE = 0.004f;
    public static final float END_STAR_GLOW = 0.035f;
    public static final float END_STAR_CORE_LIGHT = 40.0f;
    public static final float END_STAR_GLOW_LIGHT = 0.5f;
    public static final float END_STAR_SPIKE = 0.035f;
    public static final float END_STAR_SPIKE_LIGHT = 0.8f;
    //The stars of both styles (endStars), two layers on a cube-map grid like galaxy.glsl's: per layer the cells per face edge, the share
    // of cells with a star, the brightest star's light (scene units) and the width (Gaussian sigma, pixels). The overworld's stars are
    // vanilla's (about a pixel, at most display white); these are two to three pixels across and the bright layer's faintest still reaches
    // white at the bottom of its twinkle (EndSkyTest); the bloom gives them their glow
    public static final float END_STARS_BRIGHT_CELLS = 40.0f;
    public static final float END_STARS_BRIGHT_CHANCE = 0.13f;
    public static final float END_STARS_BRIGHT_LIGHT = 5.0f;
    public static final float END_STARS_BRIGHT_SIGMA = 1.1f;
    public static final float END_STARS_DIM_CELLS = 100.0f;
    public static final float END_STARS_DIM_CHANCE = 0.16f;
    public static final float END_STARS_DIM_LIGHT = 1.6f;
    public static final float END_STARS_DIM_SIGMA = 0.8f;
    //A star's light: the layer's light times MIN + its rank to POWER; its centre within the middle INSET..1 - INSET of its cell
    public static final float END_STARS_MIN = 0.35f;
    public static final float END_STARS_POWER = 3.0f;
    public static final float END_STARS_INSET = 0.2f;
    //The twinkle: the light dips by up to this share, at a speed (radians a second) between 1 and 1 + SPEED per star
    public static final float END_STARS_TWINKLE = 0.35f;
    public static final float END_STARS_TWINKLE_SPEED = 1.5f;
    //The galaxy over each style (galaxy.glsl): its band's light and its stars' (Anime: no band over the bright paint)
    public static final float END_SPACE_BAND = 1.0f;
    public static final float END_SPACE_STARS = 1.0f;
    public static final float END_ANIME_BAND = 0.0f;
    public static final float END_ANIME_STARS = 1.0f;
    //2026-11-24 (the user: "우주 오로라를 약하게 해봐줘"): the End aurora's share over each style (Space: the starfield shows through it, as in
    // the third and fourth screenshots; Anime and vanilla's sky as before)
    public static final float END_SPACE_AURORA = 0.3f;
    public static final float END_ANIME_AURORA = 1.0f;
    //2026-11-24 (the user: "애니메이션 프리셋의 별들이 지평선에 가까울수록 다소 흐릿해지고 있어. 조금 더 뚜렷하게 보이도록 만들어줘"): over the
    // Anime sky's bright pink horizon a star of the same light stood out far less than against the indigo overhead. The stars' light rises
    // with the sky's behind them (LIFT per unit of its luminance), so they keep about their contrast down to the horizon; the dark Space sky
    // barely changes
    public static final float END_STARS_LIFT = 5.0f;
    //2026-11-24 (the user: "엔드 지형이 하늘과 안 어울리게 탁해보여. 적절한 색감으로 튜닝해봐줘"): the End's terrain graded into its sky's
    // light. Vanilla's end stone reads as a flat olive green under either sky. Its ambient light (what no block light reaches) keeps
    // SATURATION of the texture's own colour and takes the sky's light colour (TINT, display sRGB, kept at the light's luminance) at
    // EXPOSURE: Space a cool moonlit navy (the third and fourth screenshots), Anime the lavender and pink of the first two. Block light
    // (torches, the crystals' fire) keeps its warmth: the grade fades out from BLOCK_FROM to BLOCK_TO block light. A grade, no textures
    // changed: the luminance pattern stays.
    public static final float END_SPACE_GROUND_R = 112.0f;
    public static final float END_SPACE_GROUND_G = 142.0f;
    public static final float END_SPACE_GROUND_B = 236.0f;
    public static final float END_SPACE_GROUND_SATURATION = 0.25f;
    public static final float END_SPACE_GROUND_EXPOSURE = 0.5f;
    public static final float END_ANIME_GROUND_R = 226.0f;
    public static final float END_ANIME_GROUND_G = 182.0f;
    public static final float END_ANIME_GROUND_B = 248.0f;
    public static final float END_ANIME_GROUND_SATURATION = 0.35f;
    public static final float END_ANIME_GROUND_EXPOSURE = 1.1f;
    public static final float END_HORROR_GROUND_R = 150.0f;
    public static final float END_HORROR_GROUND_G = 182.0f;
    public static final float END_HORROR_GROUND_B = 158.0f;
    public static final float END_HORROR_GROUND_SATURATION = 0.15f;
    public static final float END_HORROR_GROUND_EXPOSURE = 0.6f;
    public static final float END_GROUND_BLOCK_FROM = 3.0f;
    public static final float END_GROUND_BLOCK_TO = 12.0f;
    //2026-11-24 (the user: "엔드에서 가장 밝은 별로 인한 빛 그림자도 구현해줘. 달빛그림자 처럼 진하게 할 필요는 없지만 코러스 식물 등에 그림자가
    // 비쳐져야 해"): the Anime sky's bright star casts soft shadows (EndStarLight: the sun's shadow maps from its direction, looked up by
    // lighting.comp). starLit is how much of the star reaches the surface (lighting.comp's facing times visibility, 0 to 1: a face turned
    // away is in shadow too); strength the share of the light its shadow takes (endLightParams.y). The shadow is a little cooler (TINT, display
    // sRGB, kept at its luminance: the indigo overhead), and block light keeps its own (BLOCK_FROM to BLOCK_TO, as the ground's grade)
    public static final float END_STAR_SHADOW_TINT_R = 196.0f;
    public static final float END_STAR_SHADOW_TINT_G = 190.0f;
    public static final float END_STAR_SHADOW_TINT_B = 255.0f;
    //2026-11-24 (the user: "레퍼런스 이미지 중 노란 빛기둥이 있어. 이걸 엔더섬 정 가운데 빛기둥으로 구현해볼 수 있어?", the third screenshot;
    // then "빛기둥이 너무 인위적이야. 레퍼런스 이미지 처럼 불꽃을 기다랗게 한 것 같이 고쳐줘", and "빛기둥의 시작지점을 포탈 y좌표만큼 아래로
    // 이동해줘 ... 엔더드래곤 처치가 완료된 후 그 위치의 드래곤 알이 없어졌을 때만 ... 길이를 너무 높게 할 필요는 없어보여"): a long golden
    // flame straight up through the exit portal's bedrock column (block 0, 0: its axis at x 0.5, z 0.5), not a uniform rod. Its shape is
    // measured from the column's top (its foot), its light runs on down the portal's height below it (DimensionUniforms.pillar: the
    // island hides it there, its underside does not), and it is drawn once the dragon is dead (fading in). Three layers across it, each a
    // Gaussian of the distance from its axis (blocks): a white-gold core, a golden glow and a faint orange halo (no streaks: it reaches far
    // over the sky). Heights are from the column's top. It is widest at its foot
    // (BASE_WIDTH times the layers' widths), narrows to TOP_WIDTH times them by TAPER blocks up, and burns out about TOP blocks up, its tip
    // flickering (TIP: how much that height wavers). Along it run vertical streaks (STREAK_LENGTH blocks long, rising at STREAK_SPEED of
    // their lengths per second, the light STREAK either side of its mean) and its axis sways (SWAY blocks at full height, from SWAY_FROM up),
    // as a flame's tongues. Its colour warms upward (the core to gold, the glow to orange, over WARM blocks). LIGHT is each layer's peak
    // (scene units) seen square on at its foot; looking along the flame the ray crosses more of it (up to SLANT times as much). From far
    // away a layer is at least MIN_PIXELS pixels wide (its light spread to keep the flame's total). It rises over RISE blocks from the
    // column's top and fades with height (HEIGHT: e-folding, blocks). Hidden behind terrain (the ray's share of each layer in front of the
    // surface).
    public static final float END_PILLAR_CORE = 0.06f;
    public static final float END_PILLAR_GLOW = 0.32f;
    public static final float END_PILLAR_HALO = 1.3f;
    public static final float END_PILLAR_CORE_LIGHT = 3.0f;
    public static final float END_PILLAR_GLOW_LIGHT = 1.3f;
    public static final float END_PILLAR_HALO_LIGHT = 0.05f;
    public static final float END_PILLAR_CORE_R = 255.0f;
    public static final float END_PILLAR_CORE_G = 246.0f;
    public static final float END_PILLAR_CORE_B = 214.0f;
    public static final float END_PILLAR_GOLD_R = 255.0f;
    public static final float END_PILLAR_GOLD_G = 200.0f;
    public static final float END_PILLAR_GOLD_B = 96.0f;
    public static final float END_PILLAR_EMBER_R = 236.0f;
    public static final float END_PILLAR_EMBER_G = 138.0f;
    public static final float END_PILLAR_EMBER_B = 46.0f;
    public static final float END_PILLAR_WARM = 100.0f;
    public static final float END_PILLAR_BASE_WIDTH = 2.2f;
    public static final float END_PILLAR_TOP_WIDTH = 0.5f;
    public static final float END_PILLAR_TAPER = 100.0f;
    public static final float END_PILLAR_TOP = 115.0f;
    public static final float END_PILLAR_TIP = 0.25f;
    public static final float END_PILLAR_STREAK_LENGTH = 8.0f;
    public static final float END_PILLAR_STREAK_SPEED = 1.1f;
    public static final float END_PILLAR_STREAK = 0.75f;
    public static final float END_PILLAR_SWAY = 0.6f;
    public static final float END_PILLAR_SWAY_FROM = 6.0f;
    public static final float END_PILLAR_RISE = 1.0f;
    public static final float END_PILLAR_HEIGHT = 80.0f;
    public static final float END_PILLAR_MIN_PIXELS = 0.75f;
    public static final float END_PILLAR_SLANT = 4.0f;
    //2026-11-24 (the user: "엔더드래곤 처치 후 빛기둥이 생길 때 페이드인으로 구현해줘"): the share of its height the flame starts at as it fades in
    public static final float END_PILLAR_IGNITE = 0.2f;

    private EndSkyMath() {
    }

    private static float[] srgb(float r, float g, float b) {
        return new float[]{(float) PostMath.displayToLinear(r / 255f), (float) PostMath.displayToLinear(g / 255f), (float) PostMath.displayToLinear(b / 255f)};
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static float[] scale(float[] a, float s) {
        return new float[]{a[0] * s, a[1] * s, a[2] * s};
    }

    /** endSpaceSky: the Space sky along dir. */
    public static float[] endSpaceSky(float[] dir) {
        float[] zenith = srgb(END_SPACE_ZENITH_R, END_SPACE_ZENITH_G, END_SPACE_ZENITH_B);
        float[] haze = srgb(END_SPACE_HAZE_R, END_SPACE_HAZE_G, END_SPACE_HAZE_B);
        float[] deep = srgb(END_SPACE_DEEP_R, END_SPACE_DEEP_G, END_SPACE_DEEP_B);
        if (dir[1] >= 0) return mix(zenith, haze, (float) Math.exp(-dir[1] / END_SPACE_HAZE_HEIGHT));
        return mix(haze, deep, smoothstep(0, END_SPACE_DEEP_REACH, -dir[1]));
    }

    /** endAnimeSky: the Anime gradient along dir (no star or planet). */
    public static float[] endAnimeSky(float[] dir) {
        float e = (float) Math.asin(Math.max(-1, Math.min(1, dir[1])));
        float ax = dir[0], az = dir[2];
        double sl = Math.hypot(END_STAR_X, END_STAR_Z), al = Math.hypot(ax, az);
        float side = al > 1e-4 ? (float) ((ax * END_STAR_X + az * END_STAR_Z) / (al * sl)) : 0;
        float glow = 1 + END_ANIME_SIDE * side;
        float[] horizon = scale(srgb(END_ANIME_HORIZON_R, END_ANIME_HORIZON_G, END_ANIME_HORIZON_B), glow);
        float[] pink = scale(srgb(END_ANIME_PINK_R, END_ANIME_PINK_G, END_ANIME_PINK_B), glow);
        float[] lavender = srgb(END_ANIME_LAVENDER_R, END_ANIME_LAVENDER_G, END_ANIME_LAVENDER_B);
        float[] mid = srgb(END_ANIME_MID_R, END_ANIME_MID_G, END_ANIME_MID_B);
        float[] zenith = srgb(END_ANIME_ZENITH_R, END_ANIME_ZENITH_G, END_ANIME_ZENITH_B);
        if (e < 0) return mix(horizon, srgb(END_ANIME_DEEP_R, END_ANIME_DEEP_G, END_ANIME_DEEP_B), smoothstep(0, END_ANIME_DEEP_REACH, -dir[1]));
        if (e < END_ANIME_PINK_AT) return mix(horizon, pink, smoothstep(0, END_ANIME_PINK_AT, e));
        if (e < END_ANIME_LAVENDER_AT) return mix(pink, lavender, smoothstep(END_ANIME_PINK_AT, END_ANIME_LAVENDER_AT, e));
        if (e < END_ANIME_MID_AT) return mix(lavender, mid, smoothstep(END_ANIME_LAVENDER_AT, END_ANIME_MID_AT, e));
        return mix(mid, zenith, smoothstep(END_ANIME_MID_AT, 1.5707964f, e));
    }

    /** endSkyBand: the galaxy band's weight over style (1 for vanilla's sky). */
    public static float endSkyBand(float style) {
        if (Math.abs(style - END_SKY_HORROR) < 0.5f) return 0;
        return Math.abs(style - END_SKY_ANIME) < 0.5f ? END_ANIME_BAND : Math.abs(style - END_SKY_SPACE) < 0.5f ? END_SPACE_BAND : 1;
    }

    /** endSkyStars: the galaxy stars' weight over style (1 for vanilla's sky). */
    public static float endSkyStars(float style) {
        if (Math.abs(style - END_SKY_HORROR) < 0.5f) return 0;
        return Math.abs(style - END_SKY_ANIME) < 0.5f ? END_ANIME_STARS : Math.abs(style - END_SKY_SPACE) < 0.5f ? END_SPACE_STARS : 1;
    }
}
