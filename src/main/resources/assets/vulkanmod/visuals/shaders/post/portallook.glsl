//2026-11-24 (the user: "엔드 포탈이 렌더되지 않고 있어. 네더포탈과 엔드포탈을 일반/애니메에 적절하게 각각 만들어줘"): the nether portal's and the
// End portal's own looks, one for the Default palette and one for Anime (scene_copy.comp endLightParams.z: PORTAL_LOOK_*; 0 keeps
// vanilla's). Imported after galaxy.glsl (its hash and noise), endsky.glsl (endSkySrgb) and portal.glsl.
//
// THE NETHER PORTAL (the composite knows its surface: portal.glsl's boxes). Vanilla's swirl texture gives the pattern (its luminance);
// over it a slow whirl turns about the portal's middle. Default: a deep violet whirl brightening to magenta and a pale pink, its rim
// glowing along the frame. Anime: the same in flat pastel bands (lavender, pink, a creamy white) with a white rim and twinkling sparkles.
// Both keep vanilla's brightness (its glow and pulse: portal.glsl) as their scale.
//
// THE END PORTAL (and the gateway; their shader marks their pixels END_PORTAL_ALPHA_CODE): a window into space below its surface. Star
// layers lie DEPTHS blocks behind the face and slide against each other as the view moves (parallax), over a nebula. Default: a
// near-black navy with teal and blue nebula and white stars; Anime: indigo to lavender with pink clouds and soft sparkling stars.

const float PORTAL_LOOK_DEFAULT = 1.0;
const float PORTAL_LOOK_ANIME = 2.0;
//rendertype_end_portal.fsh's alpha mark (round(alpha*255))
const int END_PORTAL_ALPHA_CODE = 244;

//The nether portal: vanilla's texture luminance that reads as its brightest swirl (scene units), the whirl's scale (per block), its turn
// (radians per second) and its share against the texture, the rim's width (blocks) and light
const float NETHER_LOOK_LUMA = 0.45;
const float NETHER_LOOK_WHIRL_SCALE = 0.9;
const float NETHER_LOOK_WHIRL_SPEED = 0.35;
const float NETHER_LOOK_WHIRL = 0.45;
const float NETHER_LOOK_RIM = 0.3;
const float NETHER_LOOK_RIM_LIGHT = 0.8;
//The look's light per unit of vanilla's glow gain
const float NETHER_LOOK_EXPOSURE = 0.3;
//Default's ramp (display sRGB): deep, violet, magenta, pale
const float NETHER_DEEP_R = 34.0;
const float NETHER_DEEP_G = 6.0;
const float NETHER_DEEP_B = 78.0;
const float NETHER_VIOLET_R = 118.0;
const float NETHER_VIOLET_G = 36.0;
const float NETHER_VIOLET_B = 224.0;
const float NETHER_MAGENTA_R = 214.0;
const float NETHER_MAGENTA_G = 82.0;
const float NETHER_MAGENTA_B = 255.0;
const float NETHER_PALE_R = 255.0;
const float NETHER_PALE_G = 206.0;
const float NETHER_PALE_B = 255.0;
//Anime's bands (display sRGB) and where they change, their edge's softness; the sparkles per block and their share of cells
const float NETHER_ANIME_LOW_R = 146.0;
const float NETHER_ANIME_LOW_G = 112.0;
const float NETHER_ANIME_LOW_B = 255.0;
const float NETHER_ANIME_MID_R = 255.0;
const float NETHER_ANIME_MID_G = 140.0;
const float NETHER_ANIME_MID_B = 232.0;
const float NETHER_ANIME_HIGH_R = 255.0;
const float NETHER_ANIME_HIGH_G = 238.0;
const float NETHER_ANIME_HIGH_B = 255.0;
const float NETHER_ANIME_BAND_LOW = 0.4;
const float NETHER_ANIME_BAND_HIGH = 0.72;
const float NETHER_ANIME_SOFT = 0.035;
const float NETHER_ANIME_SPARKLE_CELLS = 5.0;
const float NETHER_ANIME_SPARKLE_CHANCE = 0.12;
const float NETHER_ANIME_SPARKLE_LIGHT = 2.5;

//The End portal: the star layers' depths behind its face (blocks), the stars per block on the nearest layer, their share of cells, their
// light; the nebula's scale (per block) and light
const vec3 END_PORTAL_DEPTHS = vec3(0.6, 2.0, 5.0);
const float END_PORTAL_STAR_CELLS = 5.0;
const float END_PORTAL_STAR_CHANCE = 0.22;
const float END_PORTAL_STAR_LIGHT = 2.2;
const float END_PORTAL_NEBULA_SCALE = 0.35;
const float END_PORTAL_NEBULA_LIGHT = 1.0;
//Default (display sRGB): the deep base, the nebula's teal and blue
const float END_PORTAL_BASE_R = 3.0;
const float END_PORTAL_BASE_G = 8.0;
const float END_PORTAL_BASE_B = 22.0;
const float END_PORTAL_TEAL_R = 20.0;
const float END_PORTAL_TEAL_G = 120.0;
const float END_PORTAL_TEAL_B = 130.0;
const float END_PORTAL_BLUE_R = 40.0;
const float END_PORTAL_BLUE_G = 60.0;
const float END_PORTAL_BLUE_B = 170.0;
//Anime (display sRGB): the indigo base, the lavender and pink clouds
const float END_PORTAL_ANIME_BASE_R = 28.0;
const float END_PORTAL_ANIME_BASE_G = 20.0;
const float END_PORTAL_ANIME_BASE_B = 96.0;
const float END_PORTAL_LAVENDER_R = 150.0;
const float END_PORTAL_LAVENDER_G = 120.0;
const float END_PORTAL_LAVENDER_B = 240.0;
const float END_PORTAL_PINK_R = 240.0;
const float END_PORTAL_PINK_G = 140.0;
const float END_PORTAL_PINK_B = 220.0;

//Where p (0..1) falls on a four-stop ramp
vec3 portalRamp(vec3 a, vec3 b, vec3 c, vec3 d, float p) {
    if (p < 0.33) {
        return mix(a, b, smoothstep(0.0, 0.33, p));
    }
    if (p < 0.66) {
        return mix(b, c, smoothstep(0.33, 0.66, p));
    }
    return mix(c, d, smoothstep(0.66, 1.0, p));
}

//The nether portal's look at its surface: vanilla its colour there (scene units), uv the point on its face from the box's corner
// (blocks: across, up), size the face's size, t the time (seconds), look PORTAL_LOOK_*, gain vanilla's glow there (portal.glsl)
vec3 netherPortalLook(vec3 vanilla, vec2 uv, vec2 size, float t, float look, float gain) {
    float pattern = clamp(luminance(vanilla)/NETHER_LOOK_LUMA, 0.0, 1.0);
    vec2 c = uv - 0.5*size;
    float r = length(c);
    float a = atan(c.y, c.x) + t*NETHER_LOOK_WHIRL_SPEED + r*0.8;
    vec2 w = vec2(cos(a), sin(a))*r*NETHER_LOOK_WHIRL_SCALE;
    float whirl = galaxyNoise(vec3(w*1.7, t*0.2)) * 0.6 + galaxyNoise(vec3(w*4.1 + 7.0, t*0.35)) * 0.4;
    float p = clamp(mix(pattern, whirl, NETHER_LOOK_WHIRL), 0.0, 1.0);
    vec2 edge = min(uv, size - uv);
    float rim = 1.0 - smoothstep(0.0, NETHER_LOOK_RIM, min(edge.x, edge.y));
    vec3 colour;
    if (abs(look - PORTAL_LOOK_ANIME) < 0.5) {
        vec3 low = endSkySrgb(NETHER_ANIME_LOW_R, NETHER_ANIME_LOW_G, NETHER_ANIME_LOW_B);
        vec3 mid = endSkySrgb(NETHER_ANIME_MID_R, NETHER_ANIME_MID_G, NETHER_ANIME_MID_B);
        vec3 high = endSkySrgb(NETHER_ANIME_HIGH_R, NETHER_ANIME_HIGH_G, NETHER_ANIME_HIGH_B);
        colour = mix(low, mid, smoothstep(NETHER_ANIME_BAND_LOW - NETHER_ANIME_SOFT, NETHER_ANIME_BAND_LOW + NETHER_ANIME_SOFT, p));
        colour = mix(colour, high, smoothstep(NETHER_ANIME_BAND_HIGH - NETHER_ANIME_SOFT, NETHER_ANIME_BAND_HIGH + NETHER_ANIME_SOFT, p));
        colour = mix(colour, high, 0.6*smoothstep(0.82, 1.0, rim));
        //Sparkles: a few cells hold a four-pointed twinkle
        vec2 cell = floor(uv*NETHER_ANIME_SPARKLE_CELLS);
        ivec3 id = ivec3(cell, 71);
        if (galaxyHash(id) < NETHER_ANIME_SPARKLE_CHANCE) {
            vec2 at = (cell + 0.2 + 0.6*vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/NETHER_ANIME_SPARKLE_CELLS;
            vec2 d = abs(uv - at)*NETHER_ANIME_SPARKLE_CELLS;
            float star = exp(-d.x*40.0)*exp(-d.y*6.0) + exp(-d.y*40.0)*exp(-d.x*6.0);
            float twinkle = 0.5 + 0.5*sin(t*3.0 + galaxyHash(id + ivec3(0, 0, 3))*6.2831853);
            colour += vec3(1.0)*star*twinkle*NETHER_ANIME_SPARKLE_LIGHT;
        }
    } else {
        colour = portalRamp(endSkySrgb(NETHER_DEEP_R, NETHER_DEEP_G, NETHER_DEEP_B), endSkySrgb(NETHER_VIOLET_R, NETHER_VIOLET_G, NETHER_VIOLET_B),
                endSkySrgb(NETHER_MAGENTA_R, NETHER_MAGENTA_G, NETHER_MAGENTA_B), endSkySrgb(NETHER_PALE_R, NETHER_PALE_G, NETHER_PALE_B), p);
        colour += endSkySrgb(NETHER_MAGENTA_R, NETHER_MAGENTA_G, NETHER_MAGENTA_B)*rim*NETHER_LOOK_RIM_LIGHT;
    }
    //vanilla's glow and pulse (portal.glsl) on it
    return colour*gain*NETHER_LOOK_EXPOSURE;
}

//One End portal star layer: q the point on the layer in the face's own coordinates (blocks), seed its own, footprint a pixel's size there
// (blocks), t the time
vec3 endPortalStars(vec2 q, float cells, int seed, float footprint, float t, bool anime) {
    vec2 cell = floor(q*cells);
    ivec3 id = ivec3(cell, seed);
    float rank = galaxyHash(id);
    if (!(rank < END_PORTAL_STAR_CHANCE)) {
        return vec3(0.0);
    }
    vec2 at = (cell + 0.15 + 0.7*vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/cells;
    float size = max(0.02, footprint);
    vec2 d = (q - at)/size;
    float light = END_PORTAL_STAR_LIGHT*(0.35 + 0.65*pow(rank/END_PORTAL_STAR_CHANCE, 2.0))*(0.02/size)*(0.02/size);
    float twinkle = 0.75 + 0.25*sin(t*2.0 + galaxyHash(id + ivec3(0, 0, 3))*6.2831853);
    float star = exp(-dot(d, d)*0.5);
    if (anime) {
        star += 0.35*(exp(-abs(d.x)*1.5)*exp(-abs(d.y)*8.0) + exp(-abs(d.y)*1.5)*exp(-abs(d.x)*8.0));
    }
    float c = galaxyHash(id + ivec3(0, 0, 4));
    vec3 colour = anime ? (c < 0.5 ? vec3(1.0, 0.85, 0.97) : vec3(0.85, 0.85, 1.0)) : (c < 0.5 ? vec3(0.8, 0.9, 1.0) : vec3(1.0));
    return colour*star*light*twinkle;
}

//The End portal's look at a pixel: pos its world position (absolute blocks, as far as float keeps them), dir the view (world, unit), n
// the face's normal, pixelAngle a pixel's angle, dist its distance, t the time, look PORTAL_LOOK_*
vec3 endPortalLook(vec3 pos, vec3 dir, vec3 n, float pixelAngle, float dist, float t, float look) {
    bool anime = abs(look - PORTAL_LOOK_ANIME) < 0.5;
    vec3 an = abs(n);
    //The face's own axes
    int ua = an.y > 0.5 ? 0 : (an.x > 0.5 ? 2 : 0);
    int va = an.y > 0.5 ? 2 : 1;
    float facing = max(dot(-dir, n), 0.2);
    vec3 colour = anime ? endSkySrgb(END_PORTAL_ANIME_BASE_R, END_PORTAL_ANIME_BASE_G, END_PORTAL_ANIME_BASE_B)
            : endSkySrgb(END_PORTAL_BASE_R, END_PORTAL_BASE_G, END_PORTAL_BASE_B);
    for (int k = 0; k < 3; k++) {
        float depth = END_PORTAL_DEPTHS[k];
        vec3 q = pos + dir*(depth/facing);
        vec2 f = vec2(q[ua], q[va]);
        //The nebula on the deepest layers
        float neb = galaxyNoise(vec3(f*END_PORTAL_NEBULA_SCALE, float(k)*3.7 + t*0.05));
        neb = smoothstep(0.45, 0.85, neb)*END_PORTAL_NEBULA_LIGHT/float(k + 1);
        vec3 tint = anime ? mix(endSkySrgb(END_PORTAL_LAVENDER_R, END_PORTAL_LAVENDER_G, END_PORTAL_LAVENDER_B),
                endSkySrgb(END_PORTAL_PINK_R, END_PORTAL_PINK_G, END_PORTAL_PINK_B), galaxyNoise(vec3(f*0.2, 9.0)))
                : mix(endSkySrgb(END_PORTAL_TEAL_R, END_PORTAL_TEAL_G, END_PORTAL_TEAL_B),
                endSkySrgb(END_PORTAL_BLUE_R, END_PORTAL_BLUE_G, END_PORTAL_BLUE_B), galaxyNoise(vec3(f*0.2, 4.0)));
        colour += tint*neb*(anime ? 0.6 : 0.35);
        float footprint = pixelAngle*(dist + depth/facing);
        colour += endPortalStars(f, END_PORTAL_STAR_CELLS/(1.0 + float(k)), 31 + k, footprint, t, anime)/float(k + 1);
    }
    return colour;
}
