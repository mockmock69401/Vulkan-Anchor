//2026-11-16 (shader roadmap session 12 follow-up, the user: "add a way to show the night sky's stars like Genshin Impact"): the anime
// star type's stars and Milky Way along a sky pixel's direction (StarMath.TYPE_ANIME). AnimeStarMath.java mirrors every const and the
// Java-checkable functions here: change both together (AnimeStarTest compares them). Needs galaxy.glsl (its hash and map coordinate),
// atmosphere.glsl (the fan's FAN_SIDE_*), water.glsl (discPlane, discCover, roundCover, the moon's radii) imported first and the
// composite's galaxyMap sampler declared (the band's map, read at each star).
//
// UNITS. Colours are W2's scene units (postmath.glsl). Directions are WORLD space unit vectors (y up) from the camera.
//
// THE LOOK (a painted night after the user's screenshot, not a photograph): AnimeSkyMap's band (a soft teal glow with darker gaps) and
// three star layers: very fine dust stars where the map's density says (the band), crisp cool-white points of three distinct sizes over
// the whole sky, a few more along the band, and sparse big ones with a small soft glow. Everything turns with the sun (animeSkyDir) and
// fades toward the horizon.
//
// SCATTER (2026-11-17, the user: "the stars look lined up on a grid; scatter them more randomly, like Genshin"): a layer is at most one
// star per cell of a cube-map grid, anywhere in its cell (a pixel looks at the four cells around its nearest cell corner, and near a
// face's edge at the neighbouring face's too; a star's light never reaches past half a cell, so none is cut); each layer's grid is
// turned its own way (ANIME_TURN_*), so no rows line up across layers or with the cube's faces; and the map's clump field (b), read at
// each star, makes the small and big stars gather in places and thin out in others.
//
// WHAT TO CALL
//   animeSkyDir(dir, sun): dir in the stars' frame (read the map at galaxyMapCoord of it).
//   animeStars(a, dir, skyDir, params, gate, pixelAngle, mapTexel, celestial, twinkle): the light (rgb, scene units, >= 0) to add to a sky
//   pixel; twinkle (2026-11-21) skyevents.glsl's starTwinkle on the small and big stars (1: steady; the dust and the band stay steady).
//   params the composite's animeStarParams (AnimeStarMath's slot: x brightness, 0 = off and nothing evaluated; y count; z size; w 1 with
//   the map); gate the vanilla stars' STAR_GATE (x the star weight, y the fan); pixelAngle the angle one pixel spans (radians).
//   2026-11-17 (a desert's night): x negative draws only the band's glow and dust stars at brightness -x, times gate.z (a desert's
//   Milky Way over the Vanilla and Round star types); gate.w (0..1) turns the band and its dust lavender.

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
const float ANIME_SIZE_A = 1.0;
const float ANIME_SIZE_B = 1.7;
const float ANIME_SIZE_C = 2.6;
const float ANIME_CLASS_B = 0.62;
const float ANIME_CLASS_C = 0.9;
const float ANIME_GLOW_WIDTH = 3.0;
const float ANIME_GLOW_SHARE = 0.16;
const float ANIME_STAR_BRIGHTNESS = 0.11;
const float ANIME_BAND_BRIGHTNESS = 0.07;
const float ANIME_BAND_R = 0.32;
const float ANIME_BAND_G = 0.78;
const float ANIME_BAND_B = 1.0;
//2026-11-17: a desert's lavender band and dust stars (gate.w mixes toward them)
const float ANIME_LAVENDER_R = 0.42;
const float ANIME_LAVENDER_G = 0.45;
const float ANIME_LAVENDER_B = 1.0;
const float ANIME_LAVENDER_DUST_R = 0.9;
const float ANIME_LAVENDER_DUST_G = 0.86;
const float ANIME_LAVENDER_DUST_B = 1.0;
const float ANIME_DUST_R = 0.78;
const float ANIME_DUST_G = 0.95;
const float ANIME_DUST_B = 1.0;
const float ANIME_COOL_R = 0.9;
const float ANIME_COOL_G = 0.95;
const float ANIME_COOL_B = 1.0;
const float ANIME_HORIZON_LOW = 0.0;
const float ANIME_HORIZON_HIGH = 0.32;
const float ANIME_MOON_MARGIN = 1.25;
//The clump field's hold on the small and big stars' chance: x (LOW + GAIN x clump), the clump 0..1 from the map's b (mean about a half)
const float ANIME_CLUMP_LOW = 0.2;
const float ANIME_CLUMP_GAIN = 1.6;
//Each layer's grid turned its own way (rotations about tilted axes; AnimeStarTest checks they are rotations)
const mat3 ANIME_TURN_DUST = mat3(0.836211, 0.333510, -0.435342, -0.245174, 0.937429, 0.247218, 0.490552, -0.099992, 0.865656);
const mat3 ANIME_TURN_SMALL = mat3(0.709748, 0.536468, -0.456574, -0.698233, 0.449769, -0.556937, -0.093426, 0.714079, 0.693803);
const mat3 ANIME_TURN_BIG = mat3(-0.177780, 0.248293, 0.952232, -0.983941, -0.029164, -0.176095, -0.015952, -0.968246, 0.249491);
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

//One cube face's stars along d (the stars' frame turned the layer's way: d = turn x skyDir), the face across axis n (a signed unit axis;
// eu, ev the face's own axes): at most one star per cell of the face's grid (cells per face edge), anywhere in its cell, of its size
// class (classes: false = all ANIME_SIZE_A), sigma its base width (radians), glow its glow's share (0: none). The four cells around d's
// nearest cell corner hold every star of this face whose light reaches d: a star's width and glow are kept within a sixth of a cell
// (three widths inside half a cell, the cell's angle shrinking off the face's middle by 1 + |uv|^2 at most), so none is cut at its
// cell's edge. d may lie a little past the face's edge (animeStarLayer asks the neighbouring face too there); cells past the edge are the
// other face's and are skipped. (The axes are vectors, not indices: indexing a vector by a variable is slow on some GPUs.)
// A star is there when its hash is under its chance, (chance.x x the clump + chance.y x the dust's density) x chance.z, read from the
// map (map: true) AT THE STAR, so every pixel of a star agrees on it (read at the pixel, a star near its threshold was cut along the
// map's texels); chance.w is its most. Cells and stars too far to reach d are passed over before anything else is read.
float animeFaceStars(vec3 d, vec3 n, vec3 eu, vec3 ev, float face, float cells, vec4 chance, mat3 turn, bool map, float light, int seed,
        float sigma, bool classes, float glow) {
    vec2 uv = vec2(dot(d, eu), dot(d, ev))/dot(d, n);
    vec2 f = (uv*0.5 + 0.5)*cells;
    vec2 base = floor(f - 0.5);
    float reach = 1.0/(3.0*cells*(1.0 + dot(uv, uv)));
    //the farthest any star of the layer reaches (four of its widest widths: past that its light is under 0.04 %), as an angle and in
    // cells (an angle stretches to at most 1 + |uv|^2 of uv here, a cell is 2 / cells of uv)
    float widest = min(sigma*(classes ? ANIME_SIZE_C : ANIME_SIZE_A), reach);
    widest = glow > 0.0 && classes ? max(widest, min(widest*ANIME_GLOW_WIDTH, reach)) : widest;
    float far2 = 16.0*widest*widest;
    float farCells = 4.0*widest*(1.0 + dot(uv, uv))*cells*0.5;
    float sum = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 cell = base + vec2(float(k & 1), float(k >> 1));
        if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= cells || cell.y >= cells) {
            continue;
        }
        vec2 gap = clamp(f, cell, cell + 1.0) - f;
        if (dot(gap, gap) > farCells*farCells) {
            continue;
        }
        ivec3 id = ivec3(cell, int(face)*997 + seed*7919);
        float rank = galaxyHash(id);
        if (!(rank < chance.w)) {
            continue;
        }
        vec2 at = (cell + vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/cells*2.0 - 1.0;
        vec3 sd = normalize(n + at.x*eu + at.y*ev);
        vec3 off = d - sd;
        float r2 = dot(off, off);
        if (r2 > far2) {
            continue;
        }
        float c = chance.x;
        if (map) {
            //the map is in the stars' frame: the star's direction there is turn's transpose x sd
            vec3 t = textureLod(galaxyMap, galaxyMapCoord(sd*turn), 0.0).rgb;
            c = chance.x*(ANIME_CLUMP_LOW + ANIME_CLUMP_GAIN*t.b) + chance.y*t.g;
        }
        if (!(rank < min(c*chance.z, 1.0))) {
            continue;
        }
        float size = classes ? animeSizeClass(galaxyHash(id + ivec3(0, 0, 3))) : ANIME_SIZE_A;
        float w = min(sigma*size, reach);
        //a little brightness spread inside a class (0.75..1), more light for the bigger classes
        float flux = light*size*(0.75 + 0.25*galaxyHash(id + ivec3(0, 0, 4)));
        float l = exp(-r2/(2.0*w*w));
        if (glow > 0.0 && size > ANIME_SIZE_A) {
            float g = min(w*ANIME_GLOW_WIDTH, reach);
            l += glow*exp(-r2/(2.0*g*g));
        }
        sum += flux*l;
    }
    return sum;
}

//The face of cube axis i (0 x, 1 y, 2 z) on d's side: its signed normal, its own axes (x: z and y, y: x and z, z: x and y, as before) and
// its number (2 i, + 1 on the positive side)
void animeFace(vec3 d, int i, out vec3 n, out vec3 eu, out vec3 ev, out float face) {
    vec3 e = i == 0 ? vec3(1.0, 0.0, 0.0) : (i == 1 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
    float side = dot(d, e) > 0.0 ? 1.0 : -1.0;
    n = e*side;
    eu = i == 0 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    ev = i == 1 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    face = float(i*2) + (side > 0.0 ? 1.0 : 0.0);
}

//One layer's light along skyDir, its grid turned by turn: its face's stars, and within about a cell of a face's edge the neighbouring
// face's too (a star near the edge on the other side reaches across; each star is its own face's only, so none is counted twice)
float animeStarLayer(vec3 skyDir, mat3 turn, float cells, vec4 chance, bool map, float light, int seed, float sigma, bool classes, float glow) {
    if (!(chance.w > 0.0)) {
        return 0.0;
    }
    vec3 d = turn*skyDir;
    vec3 a = abs(d);
    float top = max(a.x, max(a.y, a.z));
    float near = top/(1.0 + 2.0/cells);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);
    float sum = 0.0;
    for (int i = 0; i < 3; i++) {
        float ai = i == 0 ? a.x : (i == 1 ? a.y : a.z);
        if (i != axis && !(ai >= near)) {
            continue;
        }
        vec3 n, eu, ev;
        float face;
        animeFace(d, i, n, eu, ev, face);
        sum += animeFaceStars(d, n, eu, ev, face, cells, chance, turn, map, light, seed, sigma, classes, glow);
    }
    return sum;
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
vec3 animeStars(Atmosphere a, vec3 dir, vec3 skyDir, vec4 params, vec4 gate, float pixelAngle, vec3 mapTexel, vec4 celestial, float twinkle) {
    bool bandOnly = params.x < 0.0;
    float brightness = bandOnly ? -params.x*clamp(gate.z, 0.0, 1.0) : params.x;
    if (!(brightness > 0.0) || !(gate.x > 0.0)) {
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
    //more than 100 % stars: finer grids (the same chance per cell), so a high count does not fill every cell in rows
    float count = min(params.y, 1.0);
    float finer = sqrt(max(params.y, 1.0));
    //each star's chance from the map at the star (animeFaceStars): x its share by the clump, y by the dust's density, z the count, w its most
    float clumpMost = band ? ANIME_CLUMP_LOW + ANIME_CLUMP_GAIN : 1.0;
    float bandMost = band ? 1.0 : 0.0;
    //(the dust's from the pixel's own density: its stars are a pixel wide, so one cut along a texel cannot show, and it saves most reads)
    vec4 dustChance = vec4(ANIME_DUST_CHANCE*density, 0.0, count, min(ANIME_DUST_CHANCE*density*count, 1.0));
    vec4 smallChance = vec4(ANIME_SMALL_CHANCE, ANIME_SMALL_BAND, count, min((ANIME_SMALL_CHANCE*clumpMost + ANIME_SMALL_BAND*bandMost)*count, 1.0));
    vec4 bigChance = vec4(ANIME_BIG_CHANCE, 0.0, count, min(ANIME_BIG_CHANCE*clumpMost*count, 1.0));
    float sigma = max(pixelAngle, 1e-5)*ANIME_STAR_SIGMA*params.z;
    //the dust only near the band (its density is 0 well off it); a desert's band alone (bandOnly) has no small or big stars
    float dust = density > 0.0 ? animeStarLayer(skyDir, ANIME_TURN_DUST, ANIME_DUST_CELLS*finer, dustChance, false, ANIME_DUST_LIGHT, 11, sigma, false, 0.0) : 0.0;
    float small = bandOnly ? 0.0 : animeStarLayer(skyDir, ANIME_TURN_SMALL, ANIME_SMALL_CELLS*finer, smallChance, band, ANIME_SMALL_LIGHT, 12, sigma, true, 0.0);
    float big = bandOnly ? 0.0 : animeStarLayer(skyDir, ANIME_TURN_BIG, ANIME_BIG_CELLS*finer, bigChance, band, ANIME_BIG_LIGHT, 13, sigma, true, ANIME_GLOW_SHARE);
    float lavender = clamp(gate.w, 0.0, 1.0);
    vec3 dustColour = mix(vec3(ANIME_DUST_R, ANIME_DUST_G, ANIME_DUST_B), vec3(ANIME_LAVENDER_DUST_R, ANIME_LAVENDER_DUST_G, ANIME_LAVENDER_DUST_B), lavender);
    vec3 bandColour = mix(vec3(ANIME_BAND_R, ANIME_BAND_G, ANIME_BAND_B), vec3(ANIME_LAVENDER_R, ANIME_LAVENDER_G, ANIME_LAVENDER_B), lavender);
    vec3 stars = dust*dustColour + (small + big)*twinkle*vec3(ANIME_COOL_R, ANIME_COOL_G, ANIME_COOL_B);
    vec3 glow = band ? mapTexel.r*mapTexel.r*ANIME_MAP_SCALE*ANIME_BAND_BRIGHTNESS*bandColour : vec3(0.0);
    return (stars*(ANIME_STAR_BRIGHTNESS*moonKeep.y) + glow*moonKeep.x)*brightness*weight;
}
