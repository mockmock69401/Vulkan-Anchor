//W6: water colour, reflectivity, ripples and the sun/moon disc, shared by reflection.comp and the composite (scene_copy.comp, W7).
// No uniforms or bindings: any shader can import it (it imports atmosphere.glsl, and with it postmath.glsl). WaterMath.java (and
// RippleMath.java for the RIPPLE_ consts) mirror every const and function here: change both together (WaterTest compares them).
//
// UNITS. Colours are W2's scene units (postmath.glsl: linear Rec. 709, 1.0 = a white surface in full daylight at exposure 100).
// Lengths and positions are blocks. Directions are WORLD space unit vectors (y up) from the camera. Disc sizes are the tangent of
// the half angle (a square of half size h covers the directions whose gnomonic projection onto the plane facing it is within h).
//
// WHAT THE COMPOSITE (W7) CALLS
//   water pixels (lightTex kind lt.z > 0): the lit pixel (vanilla's water blended over the floor, lit like any surface) seen
//     through the water: lit = waterColour(lit, lt.z * slot11.x, light * slot11.y), BEFORE the haze. light (rgb, scene units, per
//     pixel) is exactly what the composite's lighting turns a white texel at this pixel into: its sky-lit part (sky light through the
//     sky light curve, with the day/night and weather brightness the composite already applies) times W5's sun factor and the light
//     colour, plus its block light (held light and per-pixel light included). slot11.x is the water depth setting (0 gives the pixel
//     back unchanged), slot11.y WaterMath.ambient(day) (1 by day, 0.5 at night and in full rain). Night and rain dimming are already
//     in light; slot11.y halves the body's share again ON PURPOSE: the water body is lit mostly by direct sun scattered under the
//     surface, which moonlight and an overcast sky lack, so deep water darkens more than the land around it.
//     Ice, glass and slime (lt.z < 0) get no water colour.
//   output alpha (boundary 3.2): sceneOut.a = reflectionMark(lt.z): +WATER_REFLECTION (1) water, -GLASS_REFLECTION (-0.5) ice/
//     glass/slime, 0 everything else (also sky, and every pixel when the lighting pass did not run). Write the plain lit and
//     hazed colour: do NOT darken it by the reflectivity; reflection.comp mixes the reflection in with reflectivity(mark, cos).
//     The magnitude is a weight 0..1 the reflection pass multiplies the reflectivity with: the composite may write a smaller one
//     (never below REFLECT_MARK_MIN) to weaken a pixel's reflection, but never change the sign.
//   sky pixels: sky = skyColour(a, dir) + sunGlow(a, dir) + sunDisc(a, dir) + W4's vanillaStars, THEN the far clouds over it
//     (sky*(1 - r.a) + r.rgb), so clouds cover the disc. The disc is not hazed (a sky pixel is at infinity; skyColour already is the
//     air's colour along dir). The disc is W6's (boundary 6):
//     a square like vanilla's sun, SUN_DISC_LUMINANCE (24) times a luminance-1 tint inside, so the clean screen-space shafts
//     (SHAFT_THRESHOLD 1.5) see it wherever no cloud covers it. Keep vanilla's moon (vanillaStars keeps it, with its phase): the moon's
//     disc (moonRadiance) is drawn only in reflections (discGlint), where vanilla's moon is not where a reflected ray points.
//   reflectivity(mark, cosTheta) is there for a composite that wants the share the reflection pass will mirror (cosTheta the
//     cosine between the surface normal and the direction toward the camera); the composite does not need it for the above.

#import <vulkanmod:visuals/post/atmosphere.glsl>

// ---- water colour (Beer-Lambert: transmittance exp(-sigma L) per channel, and the in-scattered body colour for the rest) ----

//Extinction per block of water, red absorbed most (pure water absorbs red far more than blue; Minecraft's lakes are clearer than
// the ocean floor would allow at these numbers, so they are a game look, not measured). 2026-10-28 (the user: next to Complementary
// Reimagined the lakes were opaque): a third of 0.30 / 0.10 / 0.065, so a floor 3 blocks down seen 25 degrees below the horizon
// (about 7 blocks of water) keeps half its red and three quarters of its green and blue; vanilla's water is already tinted
const float WATER_EXTINCTION_RED = 0.10;
const float WATER_EXTINCTION_GREEN = 0.035;
const float WATER_EXTINCTION_BLUE = 0.025;
//The colour deep water tends to under a white light of 1 (scene units per unit light). 2026-10-21 (CTE2: deep sea seen from above was
// too dark, a navy of about sRGB 38 71 94 by day): raised to about sRGB 48 89 130, an ocean blue
const float WATER_BODY_RED = 0.03;
const float WATER_BODY_GREEN = 0.10;
const float WATER_BODY_BLUE = 0.22;
//The longest length used (W5's WATER_MAX_LENGTH: nothing behind the surface)
const float WATER_LONGEST = 1024.0;

//The share of the light behind len blocks of water that reaches the surface, per channel in [0, 1]; never increases with len;
// 1 at len 0, NaN or negative len
vec3 waterTransmittance(float len) {
    float l = len > 0.0 ? min(len, WATER_LONGEST) : 0.0;
    return exp(-vec3(WATER_EXTINCTION_RED, WATER_EXTINCTION_GREEN, WATER_EXTINCTION_BLUE)*l);
}

//behind (scene units) seen through len blocks of water lit by light (rgb, scene units: what a white surface there would get)
vec3 waterColour(vec3 behind, float len, vec3 light) {
    vec3 t = waterTransmittance(len);
    return behind*t + vec3(WATER_BODY_RED, WATER_BODY_GREEN, WATER_BODY_BLUE)*max(light, vec3(0.0))*(vec3(1.0) - t);
}

// ---- reflectivity (Schlick 1994, "An inexpensive BRDF model for physically-based rendering": F = F0 + (1 - F0)(1 - cos)^5) ----

//Water's reflectance looking straight down (refractive index 1.33: ((n - 1)/(n + 1))^2 = 0.02); ice is about the same
const float WATER_F0 = 0.02;
const float FRESNEL_POWER = 5.0;
//The composite's alpha (reflectionMark): water mirrors fully, ice/glass/slime half as strongly (flat and weak, B9)
const float WATER_REFLECTION = 1.0;
const float GLASS_REFLECTION = 0.5;
//|alpha| below this is "no reflection" (rgba16f keeps 0 exactly; NaN counts as none)
const float REFLECT_MARK_MIN = 0.001;

//2026-10-29: a colour channel that moved more than this (1.5 steps of the 8-bit main colour) between the copy right after the translucent terrain and
// the final colour was drawn over (overdrawnAfterTranslucent)
const float OVERDRAW_MIN = 0.0058824;

//Whether the final colour differs from the copy taken right after the translucent terrain: something (rain, snow) was drawn over it
bool overdrawnAfterTranslucent(vec3 afterTranslucent, vec3 colour) {
    return any(greaterThan(abs(afterTranslucent - colour), vec3(OVERDRAW_MIN)));
}

//The composite's output alpha for a translucent kind (W5's lightOut.a / lightingAt's z)
float reflectionMark(float kind) {
    return kind > 0.0 ? WATER_REFLECTION : (kind < 0.0 ? -GLASS_REFLECTION : 0.0);
}

//The share of the reflection mixed into a pixel with alpha mark, cosTheta the cosine between its (rippled) normal and the
// direction toward the camera: lowest looking straight down (|mark| WATER_F0), |mark| at grazing angles, monotonic between
float reflectivity(float mark, float cosTheta) {
    float w = mark > 0.0 ? min(mark, 1.0) : (mark < 0.0 ? min(-mark, 1.0) : 0.0);
    float c = cosTheta > 0.0 ? min(cosTheta, 1.0) : 0.0;
    return w*(WATER_F0 + (1.0 - WATER_F0)*pow(1.0 - c, FRESNEL_POWER));
}

// ---- the sun and moon discs ----

//Half sizes (tangent of the half angle). The sun matches the square in refs/S2 (about 170 pixels wide at 2560x1440, FOV 70)
const float SUN_HALF_SIZE = 0.084;
const float MOON_HALF_SIZE = 0.06;
//The edge is smoothed over this much either side (about 2 pixels at 1440p, FOV 70)
const float DISC_EDGE = 0.002;
//The moon's brightness (scene units) and tint (normalised to luminance 1 in moonRadiance)
const float MOON_DISC_LUMINANCE = 0.6;
//Rain hides the discs: they fade by DISC_RAIN_HIDE times the overcast share (Atmosphere.sunLight.w, which is W4's RAIN_GREY 0.8 in
// full rain: gone there, as vanilla hides its sun and moon by the rain level)
const float DISC_RAIN_HIDE = 1.25;
const float MOON_RED = 0.92;
const float MOON_GREEN = 0.98;
const float MOON_BLUE = 1.12;
//Round glints on rippled water (B9): a lobe cos^GLINT_POWER around the sun's (moon's) direction (half width ~2.4 degrees), its peak
// GLINT_PEAK of the disc's brightness
const float GLINT_POWER = 800.0;
const float GLINT_PEAK = 0.5;

//How much of direction dir lies inside the square disc of half size h facing centre (0..1, soft edge). The square's edges are
// vanilla's: one pair along the sun's rotation axis (world z), the other along its path
float discCover(vec3 dir, vec3 centre, float h) {
    float c = dot(dir, centre);
    if (!(c > 0.0)) {
        return 0.0;
    }
    vec3 axis = vec3(0.0, 0.0, 1.0) - centre*centre.z;
    float al = length(axis);
    axis = al > 0.0001 ? axis/al : vec3(1.0, 0.0, 0.0);
    vec3 side = cross(axis, centre);
    vec2 uv = vec2(dot(dir, axis), dot(dir, side))/c;
    float m = max(abs(uv.x), abs(uv.y));
    return 1.0 - smoothstep(h - DISC_EDGE, h + DISC_EDGE, m);
}

//The sun disc's brightness (scene units): SUN_DISC_LUMINANCE times the sky's sunlight tint at luminance 1, gone in rain (the overcast
// share) and as the sun sets below the horizon. 0 outside ATMOSPHERE_SKY
vec3 sunRadiance(Atmosphere a) {
    if (!atmosphereSky(a)) {
        return vec3(0.0);
    }
    vec3 c = max(a.sunLight.rgb, vec3(0.0));
    float l = luminance(c);
    vec3 tint = l > 0.000001 ? c/l : vec3(1.0);
    float overcastShare = a.sunLight.w > 0.0 ? min(a.sunLight.w*DISC_RAIN_HIDE, 1.0) : 0.0;
    return tint*SUN_DISC_LUMINANCE*(1.0 - overcastShare)*smoothstep(-SUN_HALF_SIZE, SUN_HALF_SIZE, a.sun.y);
}

//The moon disc's brightness (scene units), by night() and gone in rain. 0 outside ATMOSPHERE_SKY
vec3 moonRadiance(Atmosphere a) {
    if (!atmosphereSky(a)) {
        return vec3(0.0);
    }
    vec3 tint = vec3(MOON_RED, MOON_GREEN, MOON_BLUE);
    float night = a.moonLight.w > 0.0 ? min(a.moonLight.w, 1.0) : 0.0;
    float overcastShare = a.sunLight.w > 0.0 ? min(a.sunLight.w*DISC_RAIN_HIDE, 1.0) : 0.0;
    return tint/luminance(tint)*MOON_DISC_LUMINANCE*night*(1.0 - overcastShare)*smoothstep(-MOON_HALF_SIZE, MOON_HALF_SIZE, -a.sun.y);
}

//The sun disc seen along dir (sky pixels and reflected sky): sunRadiance inside the square, 0 outside
vec3 sunDisc(Atmosphere a, vec3 dir) {
    return sunRadiance(a)*discCover(dir, a.sun.xyz, SUN_HALF_SIZE);
}

//One disc of brightness radiance facing centre (half size h) in a reflected direction r: the mirrored square on calm water, a round
// lobe cos^GLINT_POWER at GLINT_PEAK of its brightness where the water ripples (ripple 0..1, rippleAmount), mixed by ripple
vec3 glintOf(vec3 radiance, vec3 r, vec3 centre, float h, float ripple) {
    vec3 sharp = radiance*discCover(r, centre, h);
    float k = ripple > 0.0 ? min(ripple, 1.0) : 0.0;
    if (k <= 0.0) {
        return sharp;
    }
    return mix(sharp, radiance*(pow(max(dot(r, centre), 0.0), GLINT_POWER)*GLINT_PEAK), k);
}

//The sun and moon in a reflected direction r: the mirrored square discs on calm water, round glints where the water ripples
// (ripple 0..1, rippleAmount). The sun's is multiplied by the sun visibility at the water (0..1: an island's shadow takes the glint);
// the moon's is not: the shadow maps are cast from the sun's direction and say nothing about the moon (at night its glint is unshadowed)
vec3 discGlint(Atmosphere a, vec3 r, float ripple, float sunVisibility) {
    float v = sunVisibility > 0.0 ? min(sunVisibility, 1.0) : 0.0;
    return glintOf(sunRadiance(a), r, a.sun.xyz, SUN_HALF_SIZE, ripple)*v + glintOf(moonRadiance(a), r, -a.sun.xyz, MOON_HALF_SIZE, ripple);
}

//The sky seen in a reflected direction r without the clouds (B8): the sky colour for the direction, its glow and the discs (or
// glints, discGlint with the sun visibility at the water). Finite and non-negative
vec3 reflectedSky(Atmosphere a, vec3 r, float ripple, float sunVisibility) {
    return skyColour(a, r) + sunGlow(a, r) + discGlint(a, r, ripple, sunVisibility);
}

//What a ray that finds nothing on screen reflects: the (hazed, clouded) sky where the water sees it (skyAccess 1), the water's own
// colour where it does not (under an overhang or in a cave: the reflection then changes nothing)
vec3 reflectFallback(vec3 sky, vec3 own, float skyAccess) {
    float s = skyAccess > 0.0 ? min(skyAccess, 1.0) : 0.0;
    return mix(own, sky, s);
}

// ---- ripples: four octaves of value noise with analytic derivatives (Perlin 2002, "Improving noise": the quintic fade) ----
//
// 2026-11-09 (session 2a; the user: the ripples' wavelength was so long the water looked like plastic): each octave is a train of
// short waves. Its lattice is turned to the octave's own direction of travel and stretched along the crests (STRETCH cells), so the
// value noise reads as elongated crests rather than round blobs, and the four directions (within about 55 degrees of the wind,
// RippleMath.FLOW[0]) cross into a chop, as in the directional wave spectra of Tessendorf 2001 ("Simulating Ocean Water",
// SIGGRAPH course notes) without their FFT. The density setting divides every cell; the steepness (slope per octave) stays, so
// denser water is finer, not rougher. The total slope (about 0.06 RMS; the old three octaves about 0.04) is a calm lake's: Cox
// and Munk 1954 ("Measurement of the roughness of the sea surface from photographs of the sun's glitter", JOSA 44) give a slope
// variance of 0.003 + 0.00512 U for a wind of U m/s, about 0.06 RMS in no wind.
//
// The turn keeps the noise periodic: a direction of whole numbers (u, v) puts the world point p at u p.x + v p.z across the crests
// and -v p.x + u p.z along them; a step of RIPPLE_PERIOD in x or z moves both by whole multiples of RIPPLE_PERIOD, and the lattice
// has a whole number of cells per RIPPLE_PERIOD on each axis, so the offsets' wrap (RippleMath.PERIOD) never shows a seam.

//The noise repeats every RIPPLE_PERIOD blocks (RippleMath.PERIOD: the offsets wrap there)
const float RIPPLE_PERIOD = 240.0;
//Per octave: the direction the waves travel (whole numbers, see above; RippleMath.FLOW drifts the same way), the cell across the
// crests in blocks at density 100%, the crests' length in cells, and the steepness (the slope's scale: height / cell)
const float RIPPLE_DIR_X_0 = 5.0;
const float RIPPLE_DIR_Z_0 = 2.0;
const float RIPPLE_CELL_0 = 2.0;
const float RIPPLE_STRETCH_0 = 2.2;
const float RIPPLE_STEEP_0 = 0.035;
const float RIPPLE_DIR_X_1 = 6.0;
const float RIPPLE_DIR_Z_1 = -1.0;
const float RIPPLE_CELL_1 = 0.95;
const float RIPPLE_STRETCH_1 = 2.0;
const float RIPPLE_STEEP_1 = 0.035;
const float RIPPLE_DIR_X_2 = 2.0;
const float RIPPLE_DIR_Z_2 = 3.0;
const float RIPPLE_CELL_2 = 0.45;
const float RIPPLE_STRETCH_2 = 1.8;
const float RIPPLE_STEEP_2 = 0.03;
const float RIPPLE_DIR_X_3 = 3.0;
const float RIPPLE_DIR_Z_3 = -2.0;
const float RIPPLE_CELL_3 = 0.22;
const float RIPPLE_STRETCH_3 = 1.5;
const float RIPPLE_STEEP_3 = 0.025;
//The density setting's range (ratio; the settings screen's 25..200%)
const float RIPPLE_DENSITY_MIN = 0.25;
const float RIPPLE_DENSITY_MAX = 2.0;
//An octave fades out as one cell across its crests covers fewer pixels along the view: none at RIPPLE_FADE_NONE pixels, full from
// RIPPLE_FADE_FULL (so a finer octave fades nearer the camera and never shimmers far away)
const float RIPPLE_FADE_NONE = 2.0;
const float RIPPLE_FADE_FULL = 8.0;
//The foreshortening guard (the smallest cosine the footprint divides by)
const float RIPPLE_MIN_COS = 0.02;
//A ray reflected off a rippled normal is lifted to rise at least this much (sine) above the surface it leaves
const float REFLECT_MIN_UP = 0.02;
//Hash constants: Teschner et al. 2003 ("Optimized spatial hashing for collision detection", VMV) primes combine the cell, then a
// 32-bit finaliser (Wellons 2018, "Prospecting for hash functions", public domain)
const int RIPPLE_PRIME_X = 73856093;
const int RIPPLE_PRIME_Z = 19349663;
const int RIPPLE_PRIME_SEED = 83492791;

uint rippleHash(ivec2 c, uint seed) {
    uint h = (uint(c.x)*uint(RIPPLE_PRIME_X)) ^ (uint(c.y)*uint(RIPPLE_PRIME_Z)) ^ (seed*uint(RIPPLE_PRIME_SEED));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

//The value -1..1 at lattice corner i (wrapped into n cells per axis: the noise repeats every n cells)
float rippleCorner(vec2 i, vec2 n, uint seed) {
    vec2 w = i - n*floor(i/n);
    return float(rippleHash(ivec2(w), seed) & 0xFFFFFFu)/16777215.0*2.0 - 1.0;
}

//The value noise's gradient at x (lattice units), repeating every n cells per axis
vec2 rippleGradient(vec2 x, vec2 n, uint seed) {
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 u = f*f*f*(f*(f*6.0 - 15.0) + 10.0);
    vec2 du = 30.0*f*f*(f*(f - 2.0) + 1.0);
    float a = rippleCorner(i, n, seed);
    float b = rippleCorner(i + vec2(1.0, 0.0), n, seed);
    float c = rippleCorner(i + vec2(0.0, 1.0), n, seed);
    float d = rippleCorner(i + vec2(1.0, 1.0), n, seed);
    float k = a - b - c + d;
    return du*vec2(b - a + k*u.y, c - a + k*u.x);
}

//The whole number of cells (at least 1) of `span` blocks in one period (the division rounded: 240/1.2 is 199.99998 in float)
float rippleCells(float span) {
    return max(floor(RIPPLE_PERIOD/span + 0.5), 1.0);
}

//The density setting as the octaves take it: clamped to its range, 1 when not a number
float rippleDensity(float density) {
    return density >= RIPPLE_DENSITY_MIN ? min(density, RIPPLE_DENSITY_MAX) : (density < RIPPLE_DENSITY_MIN ? RIPPLE_DENSITY_MIN : 1.0);
}

//An octave's share 0..1 for a cell of `cell` blocks at `footprint` blocks per pixel along the view
float rippleFade(float cell, float footprint) {
    float pixels = footprint > 0.000001 ? cell/footprint : 1000000.0;
    return smoothstep(RIPPLE_FADE_NONE, RIPPLE_FADE_FULL, pixels);
}

//One octave's slope (d height / d x, d height / d z) at p (the world x, z less the octave's flow, blocks): waves travelling along
// dir (whole numbers), `cell` blocks across the crests and stretch cells along them at density 1, scaled to `steep`, faded by the
// footprint. density is rippleDensity's
vec2 rippleOctave(vec2 p, vec2 dir, float cell, float stretch, float steep, float density, float footprint, uint seed) {
    float m = length(dir);
    //Cells per period across and along the crests (in the turned frame a period is RIPPLE_PERIOD times m blocks long)
    vec2 n = vec2(rippleCells(cell*m/density), rippleCells(cell*stretch*m/density));
    vec2 q = vec2(dir.x*p.x + dir.y*p.y, dir.x*p.y - dir.y*p.x)*n/RIPPLE_PERIOD;
    vec2 g = rippleGradient(q, n, seed);
    //The height is steep times the cell across (RIPPLE_PERIOD/(n.x m) blocks): the slope across is steep times g.x, along it
    // scaled down by the longer cell
    float w = steep*rippleFade(RIPPLE_PERIOD/(n.x*m), footprint);
    g.y *= n.y/n.x;
    return vec2(g.x*dir.x - g.y*dir.y, g.x*dir.y + g.y*dir.x)*(w/m);
}

//The rippled water's world normal (unit, y up) at rel (the surface's x, z relative to the camera, blocks). offsetsA, offsetsB are
// the reflection's slots 11 and 12 (RippleMath.offsets: each octave's world offset, wrapped), strength the ripple setting (0 = a flat
// mirror), density the ripple density setting (1 = 100%; 2 halves every wavelength), footprint the blocks one pixel covers along the
// view on the surface (blocks per pixel / cos of the view to the surface). The same world position gives the same normal from any
// camera (world anchored, B9)
vec3 rippleNormal(vec2 rel, vec4 offsetsA, vec4 offsetsB, float strength, float density, float footprint) {
    float s = strength > 0.0 ? strength : 0.0;
    if (s <= 0.0) {
        return vec3(0.0, 1.0, 0.0);
    }
    float d = rippleDensity(density);
    vec2 slope = rippleOctave(rel + offsetsA.xy, vec2(RIPPLE_DIR_X_0, RIPPLE_DIR_Z_0), RIPPLE_CELL_0, RIPPLE_STRETCH_0, RIPPLE_STEEP_0, d, footprint, 1u);
    slope += rippleOctave(rel + offsetsA.zw, vec2(RIPPLE_DIR_X_1, RIPPLE_DIR_Z_1), RIPPLE_CELL_1, RIPPLE_STRETCH_1, RIPPLE_STEEP_1, d, footprint, 2u);
    slope += rippleOctave(rel + offsetsB.xy, vec2(RIPPLE_DIR_X_2, RIPPLE_DIR_Z_2), RIPPLE_CELL_2, RIPPLE_STRETCH_2, RIPPLE_STEEP_2, d, footprint, 3u);
    slope += rippleOctave(rel + offsetsB.zw, vec2(RIPPLE_DIR_X_3, RIPPLE_DIR_Z_3), RIPPLE_CELL_3, RIPPLE_STRETCH_3, RIPPLE_STEEP_3, d, footprint, 4u);
    slope *= s;
    return normalize(vec3(-slope.x, 1.0, -slope.y));
}

//How much the water ripples at this footprint, 0 (calm: mirror discs) .. 1 (round glints): the share of the longest waves (the first
// octave's crests, RIPPLE_CELL_0 x RIPPLE_STRETCH_0 at the density) times the strength
float rippleAmount(float strength, float density, float footprint) {
    float s = strength > 0.0 ? min(strength, 1.0) : 0.0;
    return s*rippleFade(RIPPLE_CELL_0*RIPPLE_STRETCH_0/rippleDensity(density), footprint);
}

// ---- reflection hits (B6, B7) ----

//A hit must stand at least this far above the reflecting surface (blocks, plus a share of the surface's view distance: far away
// one pixel spans many blocks)
const float HIT_CLEARANCE = 0.1;
const float HIT_CLEARANCE_SHARE = 0.002;
//Broken cover (B7): a hit whose neighbourhood is covered by less than COVER_LOW fades fully toward what lies behind it (the sky),
// one covered above COVER_HIGH counts fully
const float COVER_LOW = 0.3;
const float COVER_HIGH = 0.9;

float reflectHitClearance(float dist) {
    return HIT_CLEARANCE + HIT_CLEARANCE_SHARE*max(dist, 0.0);
}

//Whether a pixel the reflected ray reached counts as its hit: never a reflective translucent pixel (its alpha hitMark: water or
// ice/glass/slime; 2026-10-29: a polished or metal block's mark, below -1 (blockreflect.glsl), is solid ground a ray may stop on), and
// only above the surface (heightAbove: the hit's height over the reflecting plane along its normal, blocks; dist the surface's view
// distance)
bool reflectAcceptsHit(float hitMark, float heightAbove, float dist) {
    bool translucent = abs(hitMark) >= REFLECT_MARK_MIN && hitMark >= -WATER_REFLECTION;
    return !translucent && heightAbove > reflectHitClearance(dist);
}

//The weight of a hit by the share 0..1 of its neighbourhood the hit surface covers
float reflectCoverWeight(float covered) {
    return smoothstep(COVER_LOW, COVER_HIGH, covered);
}
