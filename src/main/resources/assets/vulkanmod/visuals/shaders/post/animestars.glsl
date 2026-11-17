//2026-11-16 (shader roadmap session 12 follow-up, the user: "add a way to show the night sky's stars like Genshin Impact"): the anime
// star type's stars and Milky Way along a sky pixel's direction (StarMath.TYPE_ANIME). AnimeStarMath.java mirrors every const and the
// Java-checkable functions here: change both together (AnimeStarTest compares them). Needs galaxy.glsl (its hash and map coordinate),
// atmosphere.glsl (the fan's FAN_SIDE_*) and water.glsl (discPlane, discCover, roundCover, the moon's radii) imported first.
//
// UNITS. Colours are W2's scene units (postmath.glsl). Directions are WORLD space unit vectors (y up) from the camera.
//
// THE LOOK (a painted night after the user's screenshot, not a photograph): AnimeSkyMap's band (a soft teal glow with darker gaps) and
// three star layers: very fine dust stars where the map's density says (the band), crisp cool-white points of three distinct sizes over
// the whole sky, a few more along the band, and sparse big ones with a small soft glow. Stars are one per cell of a cube-map grid (a
// pixel looks at its own cell only), as galaxy.glsl's. Everything turns with the sun (animeSkyDir) and fades toward the horizon.
//
// WHAT TO CALL
//   animeSkyDir(dir, sun): dir in the stars' frame (read the map at galaxyMapCoord of it).
//   animeStars(a, dir, skyDir, params, gate, pixelAngle, mapTexel, celestial): the light (rgb, scene units, >= 0) to add to a sky pixel.
//   params the composite's animeStarParams (AnimeStarMath's slot: x brightness, 0 = off and nothing evaluated; y count; z size; w 1 with
//   the map); gate the vanilla stars' STAR_GATE (x the star weight, y the fan); pixelAngle the angle one pixel spans (radians).

const float ANIME_DUST_CELLS = 500.0;
const float ANIME_DUST_CHANCE = 0.16;
const float ANIME_DUST_LIGHT = 0.35;
const float ANIME_SMALL_CELLS = 120.0;
const float ANIME_SMALL_CHANCE = 0.22;
const float ANIME_SMALL_BAND = 0.18;
const float ANIME_SMALL_LIGHT = 1.0;
const float ANIME_BIG_CELLS = 40.0;
const float ANIME_BIG_CHANCE = 0.12;
const float ANIME_BIG_LIGHT = 3.2;
const float ANIME_STAR_SIGMA = 0.55;
const float ANIME_STAR_INSET = 0.32;
const float ANIME_SIZE_A = 1.0;
const float ANIME_SIZE_B = 1.7;
const float ANIME_SIZE_C = 2.6;
const float ANIME_CLASS_B = 0.62;
const float ANIME_CLASS_C = 0.9;
const float ANIME_GLOW_WIDTH = 3.0;
const float ANIME_GLOW_CELL = 0.08;
const float ANIME_GLOW_SHARE = 0.16;
const float ANIME_STAR_BRIGHTNESS = 0.11;
const float ANIME_BAND_BRIGHTNESS = 0.07;
const float ANIME_BAND_R = 0.32;
const float ANIME_BAND_G = 0.78;
const float ANIME_BAND_B = 1.0;
const float ANIME_DUST_R = 0.78;
const float ANIME_DUST_G = 0.95;
const float ANIME_DUST_B = 1.0;
const float ANIME_COOL_R = 0.9;
const float ANIME_COOL_G = 0.95;
const float ANIME_COOL_B = 1.0;
const float ANIME_HORIZON_LOW = 0.0;
const float ANIME_HORIZON_HIGH = 0.32;
const float ANIME_MOON_MARGIN = 1.25;
//AnimeSkyMap.ANIME_MAP_SCALE: the band's glow over the map's r squared
const float ANIME_MAP_SCALE = 1.3;

//dir in the stars' frame: the sun's direction goes to straight up (vanilla's stars and sun turn together about the z axis)
vec3 animeSkyDir(vec3 dir, vec3 sun) {
    float l = length(sun.xy);
    if (!(l > 1e-4)) {
        return dir;
    }
    vec2 s = sun.xy/l;
    return vec3(s.y*dir.x - s.x*dir.y, s.x*dir.x + s.y*dir.y, dir.z);
}

//The fade toward the horizon for a world direction's y
float animeHorizon(float y) {
    return smoothstep(ANIME_HORIZON_LOW, ANIME_HORIZON_HIGH, y);
}

float animeSizeClass(float h) {
    return h < ANIME_CLASS_B ? ANIME_SIZE_A : (h < ANIME_CLASS_C ? ANIME_SIZE_B : ANIME_SIZE_C);
}

//One layer's light along d (the stars' frame): the star of d's cube-map cell (cells per face edge), present with probability chance,
// of its size class (classes: false = all ANIME_SIZE_A), sigma its base width (radians), glow its glow's share (0: none)
float animeStarLayer(vec3 d, float cells, float chance, float light, int seed, float sigma, bool classes, float glow) {
    vec3 a = abs(d);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);
    float major = d[axis];
    int ua = axis == 0 ? 2 : 0;
    int va = axis == 1 ? 2 : 1;
    vec2 uv = vec2(d[ua], d[va])/abs(major);
    vec2 cell = floor((uv*0.5 + 0.5)*cells);
    int face = axis*2 + (major > 0.0 ? 1 : 0);
    ivec3 id = ivec3(cell, face*997 + seed*7919);
    float rank = galaxyHash(id);
    if (!(rank < chance)) {
        return 0.0;
    }
    vec2 at = (cell + ANIME_STAR_INSET + (1.0 - 2.0*ANIME_STAR_INSET)*vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/cells*2.0 - 1.0;
    vec3 s = vec3(0.0);
    s[axis] = sign(major);
    s[ua] = at.x;
    s[va] = at.y;
    vec3 off = d - normalize(s);
    float r2 = dot(off, off);
    float size = classes ? animeSizeClass(galaxyHash(id + ivec3(0, 0, 3))) : ANIME_SIZE_A;
    float w = sigma*size;
    //a little brightness spread inside a class (0.75..1), more light for the bigger classes
    float flux = light*size*(0.75 + 0.25*galaxyHash(id + ivec3(0, 0, 4)));
    float l = exp(-r2/(2.0*w*w));
    if (glow > 0.0 && size > ANIME_SIZE_A) {
        float g = min(w*ANIME_GLOW_WIDTH, ANIME_GLOW_CELL*2.0/cells);
        l += glow*exp(-r2/(2.0*g*g));
    }
    return flux*l;
}

//2026-11-17 (session 15 follow-up, the user: "the round moon's picture is drawn with its background": a dark square around the moon over
// the band): what the moon leaves of the band (x) and of the stars (y) along dir, moon its direction. The moon hides what lies behind it
// and nothing more: the round moon's disc (its soft edge) covers the band and the stars, and around it, out to ANIME_MOON_MARGIN of its
// radius, no stars touch the limb. Vanilla's square moon is drawn additively (its black shows the sky behind it), so the band goes on
// behind it and only the stars stay out of its square (vanilla's mask x ANIME_MOON_MARGIN). Before, both were cut out of a square
// around either moon, and the band's missing teal showed as a dark square around the round moon.
vec2 animeMoonKeep(vec3 dir, vec3 moon, vec4 celestial) {
    if (!celestialRound(celestial)) {
        return vec2(1.0, 1.0 - discCover(dir, moon, VANILLA_MOON_MASK*ANIME_MOON_MARGIN));
    }
    vec2 uv;
    if (!discPlane(dir, moon, uv)) {
        return vec2(1.0);
    }
    float r = MOON_ROUND_RADIUS*moonScale(celestial);
    return vec2(1.0 - roundCover(uv, r), 1.0 - roundCover(uv, r*ANIME_MOON_MARGIN));
}

//The anime stars' light along a sky pixel (see the header)
vec3 animeStars(Atmosphere a, vec3 dir, vec3 skyDir, vec4 params, vec4 gate, float pixelAngle, vec3 mapTexel, vec4 celestial) {
    if (!(params.x > 0.0) || !(gate.x > 0.0)) {
        return vec3(0.0);
    }
    float horizon = animeHorizon(dir.y);
    if (!(horizon > 0.0)) {
        return vec3(0.0);
    }
    //the vanilla stars' fan (vanillaStars)
    float ld = length(dir.xz);
    float ls = length(a.sun.xz);
    float side = ld > 0.0001 && ls > 0.0001 ? dot(dir.xz/ld, a.sun.xz/ls) : 0.0;
    float fan = gate.y > 0.0 ? min(gate.y, 1.0) : 0.0;
    float keep = 1.0 - fan*smoothstep(FAN_SIDE_LOW, FAN_SIDE_HIGH, side);
    //the weight's square below 1 (as vanilla's stars add b x b: they fade out at dawn as fast), a desert's boost above it as it is
    float weight = min(gate.x, STAR_GATE_MAX)*min(gate.x, 1.0)*horizon*keep;
    vec2 moonKeep = animeMoonKeep(dir, -a.sun.xyz, celestial);
    if (!(weight > 0.0) || !(max(moonKeep.x, moonKeep.y) > 0.0)) {
        return vec3(0.0);
    }
    bool band = params.w > 0.5;
    float density = band ? mapTexel.g : 0.0;
    float count = params.y;
    float sigma = max(pixelAngle, 1e-5)*ANIME_STAR_SIGMA*params.z;
    float dust = density > 0.0 ? animeStarLayer(skyDir, ANIME_DUST_CELLS, min(ANIME_DUST_CHANCE*density*count, 1.0), ANIME_DUST_LIGHT, 11, sigma, false, 0.0) : 0.0;
    float small = animeStarLayer(skyDir, ANIME_SMALL_CELLS, min((ANIME_SMALL_CHANCE + ANIME_SMALL_BAND*density)*count, 1.0), ANIME_SMALL_LIGHT, 12, sigma, true, 0.0);
    float big = animeStarLayer(skyDir, ANIME_BIG_CELLS, min(ANIME_BIG_CHANCE*count, 1.0), ANIME_BIG_LIGHT, 13, sigma, true, ANIME_GLOW_SHARE);
    vec3 stars = dust*vec3(ANIME_DUST_R, ANIME_DUST_G, ANIME_DUST_B) + (small + big)*vec3(ANIME_COOL_R, ANIME_COOL_G, ANIME_COOL_B);
    vec3 glow = band ? mapTexel.r*mapTexel.r*ANIME_MAP_SCALE*ANIME_BAND_BRIGHTNESS*vec3(ANIME_BAND_R, ANIME_BAND_G, ANIME_BAND_B) : vec3(0.0);
    return (stars*(ANIME_STAR_BRIGHTNESS*moonKeep.y) + glow*moonKeep.x)*params.x*weight;
}
