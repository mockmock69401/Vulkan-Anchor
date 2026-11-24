//2026-11-23 (the user: "엔드 배경은 우주 느낌이 나도록 만들어줘", with four screenshots: the Anime palette after the first two, the
// Default, Realistic and Cinematic palettes after the other two): the End's sky behind its galaxy and auroras, in place of vanilla's
// grey End sky box. No uniforms or bindings; import galaxy.glsl first (its value noise). EndSkyMath.java mirrors every const and function
// here: change both together (EndSkyTest compares them).
//
// UNITS. Colours are given as display sRGB 0..255 (as picked off the references) and returned in scene units (postmath.glsl
// displayToLinear: shown as given before the tone mapping). Directions are WORLD space unit vectors (y up) from the camera; the sky is at
// infinity. Angles are radians.
//
// THE STYLES (scene_copy.comp endSkyParams.x):
//   - SPACE (Default; Realistic and Cinematic too until they were retired): a near-black navy, a faint blue haze low over the horizon, darker again below it (the void).
//   - ANIME (Anime): a painted gradient from a deep indigo zenith through violet and lavender to a pink-white glow on the horizon,
//     brighter on the side of a bright star, and the star.
//   - VANILLA: vanilla's End sky (not drawn here).
//   2026-11-24 (the user: "애니메이션에서 지구는 지우고 하늘 색을 약간 더 어둡게 해줘. 전 프리셋에서 별을 구현해줘. 별은 오버월드보다 더
//   밝아야 해"): no planet; Anime's gradient about a seventh darker; both styles carry their own stars (endStars), wider and brighter
//   than the overworld's and the galaxy's, whatever the galaxy setting.

const float END_SKY_SPACE = 1.0;
const float END_SKY_ANIME = 2.0;

//Space: the zenith, the haze on the horizon (its height scale: sine of the elevation), the void far below
const float END_SPACE_ZENITH_R = 2.0;
const float END_SPACE_ZENITH_G = 5.0;
const float END_SPACE_ZENITH_B = 20.0;
const float END_SPACE_HAZE_R = 20.0;
const float END_SPACE_HAZE_G = 32.0;
const float END_SPACE_HAZE_B = 74.0;
const float END_SPACE_HAZE_HEIGHT = 0.16;
const float END_SPACE_DEEP_R = 1.0;
const float END_SPACE_DEEP_G = 2.0;
const float END_SPACE_DEEP_B = 9.0;
//How far below the horizon (sine) the haze fades into the void
const float END_SPACE_DEEP_REACH = 0.35;

//Anime: the gradient's stops from the horizon up (display sRGB) and their elevations (radians)
const float END_ANIME_HORIZON_R = 218.0;
const float END_ANIME_HORIZON_G = 186.0;
const float END_ANIME_HORIZON_B = 208.0;
const float END_ANIME_PINK_R = 200.0;
const float END_ANIME_PINK_G = 150.0;
const float END_ANIME_PINK_B = 198.0;
const float END_ANIME_PINK_AT = 0.06;
const float END_ANIME_LAVENDER_R = 122.0;
const float END_ANIME_LAVENDER_G = 110.0;
const float END_ANIME_LAVENDER_B = 202.0;
const float END_ANIME_LAVENDER_AT = 0.2;
const float END_ANIME_MID_R = 60.0;
const float END_ANIME_MID_G = 56.0;
const float END_ANIME_MID_B = 172.0;
const float END_ANIME_MID_AT = 0.55;
const float END_ANIME_ZENITH_R = 22.0;
const float END_ANIME_ZENITH_G = 20.0;
const float END_ANIME_ZENITH_B = 100.0;
//Below the horizon (the void): a dim violet at END_ANIME_DEEP_REACH (sine) and under
const float END_ANIME_DEEP_R = 48.0;
const float END_ANIME_DEEP_G = 42.0;
const float END_ANIME_DEEP_B = 112.0;
const float END_ANIME_DEEP_REACH = 0.3;
//The horizon glow on the star's side: the lower stops times 1 - SIDE .. 1 + SIDE around the sky
const float END_ANIME_SIDE = 0.18;

//The bright star (unit direction) and its look: the core's and the glow's widths (radians), their light, the spikes' length and light
const float END_STAR_X = 0.5;
const float END_STAR_Y = 0.62;
const float END_STAR_Z = -0.6042;
const float END_STAR_CORE = 0.004;
const float END_STAR_GLOW = 0.035;
const float END_STAR_CORE_LIGHT = 40.0;
const float END_STAR_GLOW_LIGHT = 0.5;
const float END_STAR_SPIKE = 0.035;
const float END_STAR_SPIKE_LIGHT = 0.8;

//The stars of both styles (endStars), two layers on a cube-map grid like galaxy.glsl's: per layer the cells per face edge, the share
// of cells with a star, the brightest star's light (scene units) and the width (Gaussian sigma, pixels). The overworld's stars are
// vanilla's (about a pixel, at most display white); these are two to three pixels across and the bright layer's faintest still reaches
// white at the bottom of its twinkle (EndSkyTest); the bloom gives them their glow
const float END_STARS_BRIGHT_CELLS = 40.0;
const float END_STARS_BRIGHT_CHANCE = 0.13;
const float END_STARS_BRIGHT_LIGHT = 5.0;
const float END_STARS_BRIGHT_SIGMA = 1.1;
const float END_STARS_DIM_CELLS = 100.0;
const float END_STARS_DIM_CHANCE = 0.16;
const float END_STARS_DIM_LIGHT = 1.6;
const float END_STARS_DIM_SIGMA = 0.8;
//A star's light: the layer's light times MIN + its rank to POWER; its centre within the middle INSET..1 - INSET of its cell
const float END_STARS_MIN = 0.35;
const float END_STARS_POWER = 3.0;
const float END_STARS_INSET = 0.2;
//The twinkle: the light dips by up to this share, at a speed (radians a second) between 1 and 1 + SPEED per star
const float END_STARS_TWINKLE = 0.35;
const float END_STARS_TWINKLE_SPEED = 1.5;

//The galaxy over each style (galaxy.glsl): its band's light and its stars' (Anime: no band over the bright paint)
const float END_SPACE_BAND = 1.0;
const float END_SPACE_STARS = 1.0;
const float END_ANIME_BAND = 0.0;
const float END_ANIME_STARS = 1.0;

float endSkyBand(float style) {
    return abs(style - END_SKY_ANIME) < 0.5 ? END_ANIME_BAND : abs(style - END_SKY_SPACE) < 0.5 ? END_SPACE_BAND : 1.0;
}

float endSkyStars(float style) {
    return abs(style - END_SKY_ANIME) < 0.5 ? END_ANIME_STARS : abs(style - END_SKY_SPACE) < 0.5 ? END_SPACE_STARS : 1.0;
}

//2026-11-24 (the user: "우주 오로라를 약하게 해봐줘"): the End aurora's share over each style (Space: the starfield shows through it, as in
// the third and fourth screenshots; Anime and vanilla's sky as before)
const float END_SPACE_AURORA = 0.3;
const float END_ANIME_AURORA = 1.0;

float endSkyAurora(float style) {
    return abs(style - END_SKY_ANIME) < 0.5 ? END_ANIME_AURORA : abs(style - END_SKY_SPACE) < 0.5 ? END_SPACE_AURORA : 1.0;
}

vec3 endSkySrgb(float r, float g, float b) {
    return displayToLinear(vec3(r, g, b)/255.0);
}

//Space: the navy sky along dir
vec3 endSpaceSky(vec3 dir) {
    vec3 zenith = endSkySrgb(END_SPACE_ZENITH_R, END_SPACE_ZENITH_G, END_SPACE_ZENITH_B);
    vec3 haze = endSkySrgb(END_SPACE_HAZE_R, END_SPACE_HAZE_G, END_SPACE_HAZE_B);
    vec3 deep = endSkySrgb(END_SPACE_DEEP_R, END_SPACE_DEEP_G, END_SPACE_DEEP_B);
    if (dir.y >= 0.0) {
        return mix(zenith, haze, exp(-dir.y/END_SPACE_HAZE_HEIGHT));
    }
    return mix(haze, deep, smoothstep(0.0, END_SPACE_DEEP_REACH, -dir.y));
}

//Anime: the painted gradient along dir (the lower stops brighter toward the bright star's side)
vec3 endAnimeSky(vec3 dir) {
    float e = asin(clamp(dir.y, -1.0, 1.0));
    vec2 across = dir.xz;
    vec2 starSide = normalize(vec2(END_STAR_X, END_STAR_Z));
    float side = dot(across, across) > 1e-8 ? dot(normalize(across), starSide) : 0.0;
    float glow = 1.0 + END_ANIME_SIDE*side;
    vec3 horizon = endSkySrgb(END_ANIME_HORIZON_R, END_ANIME_HORIZON_G, END_ANIME_HORIZON_B)*glow;
    vec3 pink = endSkySrgb(END_ANIME_PINK_R, END_ANIME_PINK_G, END_ANIME_PINK_B)*glow;
    vec3 lavender = endSkySrgb(END_ANIME_LAVENDER_R, END_ANIME_LAVENDER_G, END_ANIME_LAVENDER_B);
    vec3 mid = endSkySrgb(END_ANIME_MID_R, END_ANIME_MID_G, END_ANIME_MID_B);
    vec3 zenith = endSkySrgb(END_ANIME_ZENITH_R, END_ANIME_ZENITH_G, END_ANIME_ZENITH_B);
    if (e < 0.0) {
        return mix(horizon, endSkySrgb(END_ANIME_DEEP_R, END_ANIME_DEEP_G, END_ANIME_DEEP_B), smoothstep(0.0, END_ANIME_DEEP_REACH, -dir.y));
    }
    if (e < END_ANIME_PINK_AT) {
        return mix(horizon, pink, smoothstep(0.0, END_ANIME_PINK_AT, e));
    }
    if (e < END_ANIME_LAVENDER_AT) {
        return mix(pink, lavender, smoothstep(END_ANIME_PINK_AT, END_ANIME_LAVENDER_AT, e));
    }
    if (e < END_ANIME_MID_AT) {
        return mix(lavender, mid, smoothstep(END_ANIME_LAVENDER_AT, END_ANIME_MID_AT, e));
    }
    return mix(mid, zenith, smoothstep(END_ANIME_MID_AT, 1.5707964, e));
}

//Anime: the bright star's light along dir (pixelAngle: one pixel's angle, so the core stays at least a pixel wide)
vec3 endAnimeStar(vec3 dir, float pixelAngle) {
    vec3 s = normalize(vec3(END_STAR_X, END_STAR_Y, END_STAR_Z));
    float a = acos(clamp(dot(dir, s), -1.0, 1.0));
    float core = max(END_STAR_CORE, pixelAngle);
    float light = exp(-(a/core)*(a/core))*END_STAR_CORE_LIGHT*(END_STAR_CORE/core)*(END_STAR_CORE/core)
            + exp(-a/END_STAR_GLOW)*END_STAR_GLOW_LIGHT;
    //Four soft spikes along the sky's horizontal and vertical through the star
    vec3 right = normalize(cross(s, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(right, s);
    float x = dot(dir, right), y = dot(dir, up);
    if (dot(dir, s) > 0.0) {
        float w = max(pixelAngle, END_STAR_CORE*0.5);
        light += (exp(-abs(y)/w)*exp(-abs(x)/END_STAR_SPIKE) + exp(-abs(x)/w)*exp(-abs(y)/END_STAR_SPIKE))*END_STAR_SPIKE_LIGHT*0.5;
    }
    return vec3(1.0, 0.97, 0.94)*light;
}

//One layer of the End's stars along dir: the star of dir's cube-map cell (galaxyStars' grid and hash), sigma in pixels, t the time
// (seconds) for the twinkle
vec3 endStarLayer(vec3 dir, float cells, float chance, float light, int seed, float sigma, float pixelAngle, float t) {
    vec3 a = abs(dir);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);
    float major = dir[axis];
    int ua = axis == 0 ? 2 : 0;
    int va = axis == 1 ? 2 : 1;
    vec2 cell = floor((vec2(dir[ua], dir[va])/abs(major)*0.5 + 0.5)*cells);
    ivec3 id = ivec3(cell, (axis*2 + (major > 0.0 ? 1 : 0))*997 + seed*7919);
    float rank = galaxyHash(id);
    if (!(rank < chance)) {
        return vec3(0.0);
    }
    vec2 at = (cell + END_STARS_INSET + (1.0 - 2.0*END_STARS_INSET)*vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/cells*2.0 - 1.0;
    vec3 s = vec3(0.0);
    s[axis] = sign(major);
    s[ua] = at.x;
    s[va] = at.y;
    float d = length(dir - normalize(s))/max(pixelAngle, 1e-5);
    float phase = galaxyHash(id + ivec3(0, 0, 4));
    float twinkle = 1.0 - END_STARS_TWINKLE*0.5*(1.0 + sin(t*(1.0 + END_STARS_TWINKLE_SPEED*phase) + phase*6.2831853));
    float flux = light*(END_STARS_MIN + pow(rank/chance, END_STARS_POWER))*twinkle;
    float c = galaxyHash(id + ivec3(0, 0, 3));
    vec3 colour = c < 0.35 ? vec3(0.72, 0.82, 1.0) : (c < 0.65 ? vec3(1.0) : (c < 0.85 ? vec3(1.0, 0.78, 0.94) : vec3(1.0, 0.9, 0.7)));
    return colour*flux*exp(-d*d/(2.0*sigma*sigma));
}

//The End's stars along dir (both styles): a sparse bright layer and a denser dim one
vec3 endStars(vec3 dir, float pixelAngle, float t) {
    return endStarLayer(dir, END_STARS_BRIGHT_CELLS, END_STARS_BRIGHT_CHANCE, END_STARS_BRIGHT_LIGHT, 11, END_STARS_BRIGHT_SIGMA, pixelAngle, t)
            + endStarLayer(dir, END_STARS_DIM_CELLS, END_STARS_DIM_CHANCE, END_STARS_DIM_LIGHT, 12, END_STARS_DIM_SIGMA, pixelAngle, t);
}

//The End's sky along dir for style (END_SKY_SPACE or END_SKY_ANIME; anything else: black) with its stars; t the time (seconds) for
// their twinkle
vec3 endSky(vec3 dir, float style, float pixelAngle, float t) {
    if (abs(style - END_SKY_SPACE) < 0.5) {
        return endSpaceSky(dir) + endStars(dir, pixelAngle, t);
    }
    if (abs(style - END_SKY_ANIME) < 0.5) {
        return endAnimeSky(dir) + endAnimeStar(dir, pixelAngle) + endStars(dir, pixelAngle, t);
    }
    return vec3(0.0);
}

//2026-11-24 (the user: "엔드 지형이 하늘과 안 어울리게 탁해보여. 적절한 색감으로 튜닝해봐줘"): the End's terrain graded into its sky's
// light. Vanilla's end stone reads as a flat olive green under either sky. Its ambient light (what no block light reaches) keeps
// SATURATION of the texture's own colour and takes the sky's light colour (TINT, display sRGB, kept at the light's luminance) at
// EXPOSURE: Space a cool moonlit navy (the third and fourth screenshots), Anime the lavender and pink of the first two. Block light
// (torches, the crystals' fire) keeps its warmth: the grade fades out from BLOCK_FROM to BLOCK_TO block light. A grade, no textures
// changed: the luminance pattern stays.
const float END_SPACE_GROUND_R = 112.0;
const float END_SPACE_GROUND_G = 142.0;
const float END_SPACE_GROUND_B = 236.0;
const float END_SPACE_GROUND_SATURATION = 0.25;
const float END_SPACE_GROUND_EXPOSURE = 0.5;
const float END_ANIME_GROUND_R = 226.0;
const float END_ANIME_GROUND_G = 182.0;
const float END_ANIME_GROUND_B = 248.0;
const float END_ANIME_GROUND_SATURATION = 0.35;
const float END_ANIME_GROUND_EXPOSURE = 1.1;
const float END_GROUND_BLOCK_FROM = 3.0;
const float END_GROUND_BLOCK_TO = 12.0;

//The lit terrain colour lit (scene units) graded for style (anything but Space and Anime: unchanged); blockLight its block light 0..15
vec3 endGround(vec3 lit, float style, float blockLight) {
    bool space = abs(style - END_SKY_SPACE) < 0.5;
    if (!space && abs(style - END_SKY_ANIME) >= 0.5) {
        return lit;
    }
    vec3 lum = vec3(0.2126, 0.7152, 0.0722);
    vec3 tint = space ? endSkySrgb(END_SPACE_GROUND_R, END_SPACE_GROUND_G, END_SPACE_GROUND_B)
            : endSkySrgb(END_ANIME_GROUND_R, END_ANIME_GROUND_G, END_ANIME_GROUND_B);
    tint /= max(dot(tint, lum), 1e-4);
    float l = dot(lit, lum);
    vec3 graded = mix(vec3(l), lit, space ? END_SPACE_GROUND_SATURATION : END_ANIME_GROUND_SATURATION)*tint
            *(space ? END_SPACE_GROUND_EXPOSURE : END_ANIME_GROUND_EXPOSURE);
    return mix(graded, lit, smoothstep(END_GROUND_BLOCK_FROM, END_GROUND_BLOCK_TO, blockLight));
}

//2026-11-24 (the user: "레퍼런스 이미지 중 노란 빛기둥이 있어. 이걸 엔더섬 정 가운데 빛기둥으로 구현해볼 수 있어?", the third screenshot;
// then "빛기둥이 너무 인위적이야. 레퍼런스 이미지 처럼 불꽃을 기다랗게 한 것 같이 고쳐줘", and "빛기둥의 시작지점을 포탈 y좌표만큼 아래로
// 이동해줘 ... 엔더드래곤 처치가 완료된 후 그 위치의 드래곤 알이 없어졌을 때만 ... 길이를 너무 높게 할 필요는 없어보여"): a long golden
// flame straight up through the exit portal's bedrock column (block 0, 0: its axis at x 0.5, z 0.5), not a uniform rod. Its shape is
// measured from the column's top (its foot), its light runs on down the portal's height below it (DimensionUniforms.pillar: the
// island hides it there, its underside does not), and it is drawn only once the dragon is dead and its egg gone. Three layers across it, each a
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
const float END_PILLAR_CORE = 0.06;
const float END_PILLAR_GLOW = 0.32;
const float END_PILLAR_HALO = 1.3;
const float END_PILLAR_CORE_LIGHT = 3.0;
const float END_PILLAR_GLOW_LIGHT = 1.3;
const float END_PILLAR_HALO_LIGHT = 0.05;
const float END_PILLAR_CORE_R = 255.0;
const float END_PILLAR_CORE_G = 246.0;
const float END_PILLAR_CORE_B = 214.0;
const float END_PILLAR_GOLD_R = 255.0;
const float END_PILLAR_GOLD_G = 200.0;
const float END_PILLAR_GOLD_B = 96.0;
const float END_PILLAR_EMBER_R = 236.0;
const float END_PILLAR_EMBER_G = 138.0;
const float END_PILLAR_EMBER_B = 46.0;
const float END_PILLAR_WARM = 100.0;
const float END_PILLAR_BASE_WIDTH = 2.2;
const float END_PILLAR_TOP_WIDTH = 0.5;
const float END_PILLAR_TAPER = 100.0;
const float END_PILLAR_TOP = 115.0;
const float END_PILLAR_TIP = 0.25;
const float END_PILLAR_STREAK_LENGTH = 8.0;
const float END_PILLAR_STREAK_SPEED = 1.1;
const float END_PILLAR_STREAK = 0.75;
const float END_PILLAR_SWAY = 0.6;
const float END_PILLAR_SWAY_FROM = 6.0;
const float END_PILLAR_RISE = 1.0;
const float END_PILLAR_HEIGHT = 80.0;
const float END_PILLAR_MIN_PIXELS = 0.75;
const float END_PILLAR_SLANT = 4.0;

//One layer's weight: d the ray's closest horizontal distance to the axis, at ray length tc; a the ray's horizontal speed (|dir.xz|);
// the ray runs from the camera to tMax; sigma the layer's width, light its peak; pixelAngle one pixel's angle
float endPillarLayer(float d, float tc, float tMax, float a, float sigma, float light, float pixelAngle) {
    float wide = max(sigma, END_PILLAR_MIN_PIXELS*pixelAngle*max(tc, 0.0));
    float k = a/(1.41421356*wide);
    float share = 0.5*(tanh(1.2027606*k*(tMax - tc)) + tanh(1.2027606*k*tc));
    return light*(sigma/wide)*exp(-d*d/(2.0*wide*wide))*max(share, 0.0);
}

//The flame along the ray from the camera (rel: its position from the flame's axis, xz, and y above the column's top) along dir
// (world, unit) out to tMax (blocks; the sky: very far); depth how far below the column's top its light starts; t the time (seconds)
// for its flicker
vec3 endPillar(vec3 rel, vec3 dir, float tMax, float pixelAngle, float depth, float t) {
    float a2 = max(dot(dir.xz, dir.xz), 1e-6);
    float a = sqrt(a2);
    float tc = -dot(rel.xz, dir.xz)/a2;
    //The signed distance across the view from the axis (the flame is seen as a sheet facing the camera: its streaks and sway lie across)
    float across = (dir.x*rel.z - dir.z*rel.x)/a;
    float reach = 6.0*END_PILLAR_HALO*END_PILLAR_BASE_WIDTH + END_PILLAR_SWAY;
    if (abs(across) > reach + END_PILLAR_MIN_PIXELS*6.0*pixelAngle*max(tc, 0.0) || tc < -reach/a) {
        return vec3(0.0);
    }
    float h = rel.y + max(tc, 0.0)*dir.y;
    float tip = END_PILLAR_TOP*(1.0 + END_PILLAR_TIP*(2.0*galaxyNoise(vec3(t*0.7, 3.5, 9.5)) - 1.0));
    float height = smoothstep(-depth, END_PILLAR_RISE - depth, h)*exp(-max(h, 0.0)/END_PILLAR_HEIGHT)*(1.0 - smoothstep(0.45*tip, tip, h));
    if (!(height > 0.0)) {
        return vec3(0.0);
    }
    float width = mix(END_PILLAR_BASE_WIDTH, END_PILLAR_TOP_WIDTH, smoothstep(0.0, END_PILLAR_TAPER, h));
    float sway = END_PILLAR_SWAY*smoothstep(END_PILLAR_SWAY_FROM, END_PILLAR_SWAY_FROM + END_PILLAR_TAPER, h)
            *(2.0*galaxyNoise(vec3(h/40.0 - t*0.45, 1.5, 5.5)) - 1.0);
    float d = abs(across - sway);
    //The streaks: noise across the flame (in its own widths) and along it, rising
    float rise = h/END_PILLAR_STREAK_LENGTH - t*END_PILLAR_STREAK_SPEED;
    float streak = galaxyNoise(vec3((across - sway)/(END_PILLAR_GLOW*width)*1.3 + 17.0, rise, 2.5));
    streak = 0.6*streak + 0.4*galaxyNoise(vec3((across - sway)/(END_PILLAR_GLOW*width)*3.1 + 41.0, rise*1.7, 6.5));
    float tongue = mix(1.0 - END_PILLAR_STREAK, 1.0 + END_PILLAR_STREAK, smoothstep(0.25, 0.75, streak));
    float warm = smoothstep(0.0, END_PILLAR_WARM, h);
    vec3 gold = endSkySrgb(END_PILLAR_GOLD_R, END_PILLAR_GOLD_G, END_PILLAR_GOLD_B);
    vec3 ember = endSkySrgb(END_PILLAR_EMBER_R, END_PILLAR_EMBER_G, END_PILLAR_EMBER_B);
    float slant = min(1.0/a, END_PILLAR_SLANT);
    vec3 c = mix(endSkySrgb(END_PILLAR_CORE_R, END_PILLAR_CORE_G, END_PILLAR_CORE_B), gold, warm)
            *endPillarLayer(d, tc, tMax, a, END_PILLAR_CORE*width, END_PILLAR_CORE_LIGHT, pixelAngle)*mix(1.0, tongue, 0.6);
    c += mix(gold, ember, warm)*endPillarLayer(d, tc, tMax, a, END_PILLAR_GLOW*width, END_PILLAR_GLOW_LIGHT, pixelAngle)*tongue;
    c += ember*endPillarLayer(d, tc, tMax, a, END_PILLAR_HALO*width, END_PILLAR_HALO_LIGHT, pixelAngle);
    return c*height*slant;
}
