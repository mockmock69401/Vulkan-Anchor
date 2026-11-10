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
//   sky pixels: sky = skyColour(a, dir) + sunGlow(a, dir) + sunDisc(a, dir, celestial) + moonDisc(a, dir, celestial) + W4's
//     vanillaStars x vanillaMoonKept(a, dir, celestial) (+ aurora.glsl's light), THEN the far clouds over it (sky*(1 - r.a) + r.rgb), so
//     clouds cover the disc. The disc is not hazed (a sky pixel is at infinity; skyColour already is the air's colour along dir). The disc
//     is W6's (boundary 6): a square like vanilla's sun (or, session 5, round: the celestial slot, CelestialUniforms), SUN_DISC_LUMINANCE
//     (24) times a luminance-1 tint inside, so the clean screen-space shafts (SHAFT_THRESHOLD 1.5) see it wherever no cloud covers it.
//     With the square shape keep vanilla's moon (vanillaStars keeps it, with its phase; moonDisc is 0): the moon's disc (moonRadiance) is
//     then drawn only in reflections (discGlint), where vanilla's moon is not where a reflected ray points. With the round shape W6 draws
//     the moon itself with vanilla's phase (moonDisc) and vanillaMoonKept drops vanilla's square moon.
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

//2026-11-09 (session 5): the discs' plane. dir in the gnomonic plane facing centre: x along the sun's rotation axis (world z), y along
// its path (uv; vanilla's square has its edges along these). False behind the disc's hemisphere
bool discPlane(vec3 dir, vec3 centre, out vec2 uv) {
    uv = vec2(0.0);
    float c = dot(dir, centre);
    if (!(c > 0.0)) {
        return false;
    }
    vec3 axis = vec3(0.0, 0.0, 1.0) - centre*centre.z;
    float al = length(axis);
    axis = al > 0.0001 ? axis/al : vec3(1.0, 0.0, 0.0);
    vec3 side = cross(axis, centre);
    uv = vec2(dot(dir, axis), dot(dir, side))/c;
    return true;
}

//How much of direction dir lies inside the square disc of half size h facing centre (0..1, soft edge). The square's edges are
// vanilla's: one pair along the sun's rotation axis (world z), the other along its path
float discCover(vec3 dir, vec3 centre, float h) {
    vec2 uv;
    if (!discPlane(dir, centre, uv)) {
        return 0.0;
    }
    float m = max(abs(uv.x), abs(uv.y));
    return 1.0 - smoothstep(h - DISC_EDGE, h + DISC_EDGE, m);
}

// ---- 2026-11-09 (session 5, the user: "an option to make the sun and moon round"): the discs' shape ----
//The composite's slot 46 and the reflection's slot 27 (CelestialUniforms): x the shape (0 vanilla's squares, as before; CELESTIAL_ROUND
// round), y vanilla's moon phase 0..7 (Level.getMoonPhase: 0 full, 4 new), zw 0. The round sun's radius is the square's half size (the
// circle inside vanilla's square); the round moon's is vanilla's moon art (measured in session 5: about 0.045 at the zenith, FOV 30,
// smaller than MOON_HALF_SIZE, the reflection's square)
const float CELESTIAL_ROUND = 1.0;
const float SUN_ROUND_RADIUS = 0.084;
const float MOON_ROUND_RADIUS = 0.046;
//The round sun's limb darkening, the linear law I(mu) = 1 - u (1 - mu) with u about 0.6 in the visible (Milne 1921; values in Neckel
// and Labs 1994, "Solar limb darkening 1986-1990", Solar Physics 153), normalised to a mean of 1 over the disc (the mean of 1 - u + u mu
// over a disc is 1 - u/3)
const float SUN_LIMB_DARKENING = 0.6;
//The round moon: the terminator's softness (in the cosine between the surface and the sun) and the dark side's earthshine (share of the
// lit side's brightness)
const float MOON_TERMINATOR = 0.06;
const float MOON_EARTHSHINE = 0.02;
//The round moon hides vanilla's square moon in the composite: vanilla's moon residue (vanillaStars) is dropped inside the square of half
// size VANILLA_MOON_MASK around the moon (vanilla's moon art fills about 0.045; a little more for its soft edge)
const float VANILLA_MOON_MASK = 0.056;

// ---- 2026-11-10 (session 10, the user: "when the round moon is selected, add craters to its surface"): the round moon's face ----
//Drawn on the sphere seen from the front: p = (e, sqrt(1 - |e|^2)) with e the disc-plane position over the radius (discPlane: e.x along
// the sun's rotation axis, world z; e.y along its path), so the face turns with the disc's plane and never with the camera. The face is
// the Moon's near side as photographed (the named features' latitude and longitude turned into the unit sphere's orthographic view):
// lunar north is -e.y and lunar east -e.x, so vanilla's phases (lit toward +z waning, -z waxing) sweep the terminator across the face
// from east to west as the real Moon's do.
//  maria: dark basalt plains, albedo about 0.07 against 0.11..0.18 for the highlands (Lucey et al. 2006, "Understanding the lunar surface
//   and space-Moon interactions", Rev. Mineral. Geochem. 60): MOON_MARE's caps (xyz centre on the unit sphere, w radius in its units)
//   darken by MOON_MARIA, their edges wobbled by two octaves of value noise; the highlands mottle by MOON_MOTTLE.
//  craters: bowls (depth about a fifth of the diameter, Pike 1977, "Size-dependence in the shape of fresh impact craters on the moon")
//   with a raised rim: the slope 2 MOON_CRATER_DEPTH s inside (s = distance / radius), a gaussian rim MOON_CRATER_RIM high and
//   MOON_CRATER_RIM_WIDTH wide outside. They tilt the surface normal and the sun lights the tilted normal: across the terminator the
//   rims catch the light and the bowls' sunward walls fall in shade. The relief is a ratio of the Lommel-Seeliger law mu0 / (mu0 + mu)
//   (the dark regolith's reflectance, which makes the full Moon flat), so craters stand out near the terminator and fade toward the
//   full Moon, as the real ones do. MOON_CRATER: ten named ones (Tycho, Copernicus, Kepler, Aristarchus, Plato, Clavius, Ptolemaeus,
//   Theophilus, Petavius, Grimaldi; radii two to four times the real ones, so they read on a disc a hundred pixels across) and
//   MOON_CRATER_LOOK their albedo: x the bright young ray systems (MOON_RAY_BINS streaks around the crater, hidden at a low sun as on the
//   real Moon), y the dark lava floors (Plato, Grimaldi). Smaller craters on a grid of MOON_CRATER_CELL cells (in e), one per cell with
//   probability MOON_CRATER_SHARE, radius MOON_CRATER_MIN..MOON_CRATER_MAX (mostly small), fainter on the maria. Every crater's relief
//   fades toward the limb (where it would be a sliver of a few pixels).
//The moon colour (moonRadiance) multiplies the whole face, so the colour picker and the palettes keep it. The square moon is vanilla's art.
const float MOON_MARIA = 0.36;
const float MOON_MOTTLE = 0.16;
const int MOON_MARIA_SEED = 9;
const float MOON_CRATER_DEPTH = 0.32;
const float MOON_CRATER_RIM = 0.10;
const float MOON_CRATER_RIM_WIDTH = 0.45;
const float MOON_CRATER_CELL = 0.16;
const float MOON_CRATER_SHARE = 0.45;
const float MOON_CRATER_MIN = 0.022;
const float MOON_CRATER_MAX = 0.075;
const int MOON_CRATER_SEED = 41;
//Every crater's rim brightening (fresh ejecta), and the named craters' ray streaks
const float MOON_RIM_ALBEDO = 0.03;
const int MOON_RAY_BINS = 20;
const int MOON_RAY_SEED = 31;
//The relief ratio's ceiling (a rim facing a grazing sun)
const float MOON_RELIEF_MAX = 1.8;
//How far past the terminator (in the smooth sphere's cosine to the sun) a crater rim can still catch the light
const float MOON_NIGHT_REACH = 0.25;
//How far out (share of the radius) the face is read: the limb's z stays above 0.17, so a new moon's limb stays dark
const float MOON_LIMB = 0.985;
//The reflections' plain face (MOON_PLAIN_FACE, reflection.comp): session 5's maria noise, this many cells across the disc
const float MOON_PLAIN_CELLS = 2.5;
const int MOON_MARE_COUNT = 17;
const vec4 MOON_MARE[17] = vec4[](
        vec4(0.2312, -0.5446, 0.8062, 0.3), // Imbrium
        vec4(-0.2655, -0.4695, 0.8421, 0.18), // Serenitatis
        vec4(-0.5094, -0.1478, 0.8478, 0.21), // Tranquillitatis
        vec4(-0.8197, -0.2924, 0.4925, 0.13), // Crisium
        vec4(-0.7618, 0.1045, 0.6393, 0.15), // Fecunditatis
        vec4(-0.5401, 0.2588, 0.8008, 0.09), // Nectaris
        vec4(0.2573, 0.3584, 0.8974, 0.16), // Nubium
        vec4(0.3848, 0.1736, 0.9065, 0.1), // Cognitum
        vec4(0.5749, 0.4067, 0.71, 0.1), // Humorum
        vec4(0.7595, -0.3746, 0.5318, 0.26), // Procellarum, north
        vec4(0.788, 0.0, 0.6157, 0.22), // Procellarum, south
        vec4(0.4924, -0.6428, 0.5868, 0.15), // between Imbrium and Procellarum
        vec4(0.51, -0.1392, 0.8488, 0.12), // Insularum
        vec4(-0.068, -0.225, 0.972, 0.08), // Vaporum
        vec4(0.1447, -0.829, 0.5401, 0.08), // Frigoris, west
        vec4(-0.1102, -0.848, 0.5183, 0.08), // Frigoris, east
        vec4(-0.3744, -0.0349, 0.9266, 0.1)); // Tranquillitatis, west
const int MOON_CRATER_COUNT = 10;
const vec4 MOON_CRATER[10] = vec4[](
        vec4(0.1395, 0.682, 0.7179, 0.045), // Tycho
        vec4(0.3372, -0.1668, 0.9265, 0.05), // Copernicus
        vec4(0.6097, -0.1392, 0.7803, 0.03), // Kepler
        vec4(0.674, -0.4019, 0.6198, 0.025), // Aristarchus
        vec4(0.1014, -0.7837, 0.6128, 0.045), // Plato
        vec4(0.1282, 0.848, 0.5142, 0.09), // Clavius
        vec4(0.0345, 0.1564, 0.9871, 0.065), // Ptolemaeus
        vec4(-0.4359, 0.1977, 0.878, 0.05), // Theophilus
        vec4(-0.7849, 0.4226, 0.4532, 0.065), // Petavius
        vec4(0.9237, 0.0872, 0.3732, 0.06)); // Grimaldi
const vec2 MOON_CRATER_LOOK[10] = vec2[](
        vec2(0.22, 0.0), vec2(0.12, 0.0), vec2(0.1, 0.0), vec2(0.12, 0.0), vec2(0.0, -0.35),
        vec2(0.0, 0.0), vec2(0.0, 0.0), vec2(0.0, 0.0), vec2(0.0, 0.0), vec2(0.0, -0.3));

bool celestialRound(vec4 celestial) {
    return celestial.x > CELESTIAL_ROUND*0.5;
}

//The round disc of radius r in the plane (soft edge)
float roundCover(vec2 uv, float r) {
    return 1.0 - smoothstep(r - DISC_EDGE, r + DISC_EDGE, length(uv));
}

//The sun's shape along dir (0..): the square of half size SUN_HALF_SIZE (1 inside), or the round disc with limb darkening (mean 1)
float sunShape(vec3 dir, vec3 centre, vec4 celestial) {
    if (!celestialRound(celestial)) {
        return discCover(dir, centre, SUN_HALF_SIZE);
    }
    vec2 uv;
    if (!discPlane(dir, centre, uv)) {
        return 0.0;
    }
    float r = SUN_ROUND_RADIUS;
    float e = min(length(uv)/r, 1.0);
    float mu = sqrt(max(1.0 - e*e, 0.0));
    return roundCover(uv, r)*(1.0 - SUN_LIMB_DARKENING*(1.0 - mu))/(1.0 - SUN_LIMB_DARKENING/3.0);
}

//The share of the moon's face that is lit at phase 0..7 (1 full, 0 new): (1 + cos phi)/2 with phi = phase x 45 degrees; 1 for the square
float moonLit(vec4 celestial) {
    if (!celestialRound(celestial)) {
        return 1.0;
    }
    return 0.5 + 0.5*cos(clamp(celestial.y, 0.0, 8.0)*0.7853982);
}

//The moon's hash 0..1 of the integer cell i (the ripples' hash and primes, below, with a seed; written out here as GLSL needs it first)
float moonHash(ivec2 i, int seed) {
    uint h = (uint(i.x)*73856093u) ^ (uint(i.y)*19349663u) ^ (uint(seed)*83492791u);
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return float(h & 0xFFFFFFu)/16777215.0;
}

//The moon's value noise 0..1 at x
float moonNoise(vec2 x, int seed) {
    vec2 f = floor(x);
    ivec2 i = ivec2(f);
    f = x - f;
    vec2 u = f*f*(3.0 - 2.0*f);
    return mix(mix(moonHash(i, seed), moonHash(i + ivec2(1, 0), seed), u.x), mix(moonHash(i + ivec2(0, 1), seed), moonHash(i + ivec2(1, 1), seed), u.x), u.y);
}

//One crater of radius radius centred on c (unit) at the face point p (unit): adds its slope (times strength, fading toward the limb) to
// slope and its rim's brightening to rim; returns s, p's distance from c over the radius (9 behind it)
float moonCrater(vec3 p, vec3 c, float radius, float strength, inout vec3 slope, inout float rim) {
    float along = dot(p, c);
    vec3 d = p - c*along;
    float dl = length(d);
    float s = along > 0.0 ? dl/radius : 9.0;
    if (s < 3.0 && dl > 0.000001) {
        float o = (s - 1.0)/MOON_CRATER_RIM_WIDTH;
        float g = s < 1.0 ? 2.0*MOON_CRATER_DEPTH*s : -2.0*MOON_CRATER_RIM*o/MOON_CRATER_RIM_WIDTH*exp(-o*o);
        float k = strength*smoothstep(0.05, 0.3, c.z);
        slope += d*(g*k/dl);
        rim += k*MOON_RIM_ALBEDO*exp(-(s - 1.0)*(s - 1.0)*16.0);
    }
    return s;
}

//A named crater's bright rays and halo at p (s: moonCrater's): MOON_RAY_BINS streaks of random strength around it, fading over about
// ten radii
float moonRays(vec3 p, vec3 c, float radius, float s) {
    vec3 b1 = normalize(cross(vec3(0.0, 1.0, 0.0), c));
    vec3 b2 = cross(c, b1);
    vec3 d = p - c*dot(p, c);
    float t = (atan(dot(d, b2), dot(d, b1))/6.2831853 + 0.5)*float(MOON_RAY_BINS);
    float i = floor(t);
    float f = t - i;
    int i0 = int(i) % MOON_RAY_BINS;
    float h0 = moonHash(ivec2(i0, 0), MOON_RAY_SEED);
    float h1 = moonHash(ivec2((i0 + 1) % MOON_RAY_BINS, 0), MOON_RAY_SEED);
    float streak = smoothstep(0.5, 1.0, mix(h0, h1, f*f*(3.0 - 2.0*f)));
    float halo = s < 3.0 ? 0.5*exp(-max(s - 1.0, 0.0)*1.2) : 0.0;
    return streak*exp(-length(d)/(10.0*radius))*smoothstep(1.0, 1.6, s) + halo;
}

//The moon's shape along dir (0..1): the square of half size MOON_HALF_SIZE (1 inside, no phase: vanilla's moon keeps its own), or the
// round disc lit by phase: a sphere seen from the front, lit from phi = phase x 45 degrees off the view (0 full, 180 new) toward the
// sun's rotation axis (world +z while waning, phases 1..3: sin phi > 0; -z while waxing, 5..7), as vanilla's phase art grows and
// shrinks; the dark side keeps MOON_EARTHSHINE. Session 10: the face above (maria, craters, rays)
float moonShape(vec3 dir, vec3 centre, vec4 celestial) {
    if (!celestialRound(celestial)) {
        return discCover(dir, centre, MOON_HALF_SIZE);
    }
    vec2 uv;
    if (!discPlane(dir, centre, uv)) {
        return 0.0;
    }
    float r = MOON_ROUND_RADIUS;
    float cover = roundCover(uv, r);
    if (!(cover > 0.0)) {
        return 0.0;
    }
    //Session 10: the face is read no further out than MOON_LIMB (the soft edge's outer half was read at the limb itself, z 0, where the
    // new moon's terminator lit a thin bright ring)
    vec2 e = uv/r;
    float l = length(e);
    if (l > MOON_LIMB) {
        e *= MOON_LIMB/l;
    }
    float z = sqrt(max(1.0 - dot(e, e), 0.0));
    vec3 p = vec3(e, z);
    float phase = clamp(celestial.y, 0.0, 8.0);
    float phi = phase*0.7853982;
    vec3 sunward = vec3(sin(phi), 0.0, cos(phi));
#ifdef MOON_PLAIN_FACE
    //reflection.comp: session 5's plain face (phase, earthshine, a value-noise patch of maria). The full face below cost the reflection
    // pass about 0.12 ms over the sea (2560x1440) whatever the moon's shape, being compiled in at all; the mirrored moon is small and
    // usually rippled
    float plain = 1.0 - MOON_MARIA*smoothstep(0.45, 0.75, moonNoise(e*MOON_PLAIN_CELLS + vec2(3.7, 1.9), MOON_MARIA_SEED));
    return min(cover*plain*mix(MOON_EARTHSHINE, 1.0, smoothstep(-MOON_TERMINATOR, MOON_TERMINATOR, dot(p, sunward))), 1.0);
#else
    //The albedo: the maria (wobbled edges, uneven darkness) over the mottled highlands
    float wobble = (moonNoise(e*5.0 + vec2(1.3, 7.1), MOON_MARIA_SEED + 1) - 0.5)*0.16
            + (moonNoise(e*13.0 + vec2(4.1, 2.2), MOON_MARIA_SEED + 2) - 0.5)*0.06;
    float mare = 0.0;
    for (int k = 0; k < MOON_MARE_COUNT; k++) {
        vec4 m = MOON_MARE[k];
        mare = max(mare, 1.0 - smoothstep(0.6*m.w, 1.15*m.w, length(p - m.xyz) + wobble));
    }
    float mottle = 0.6*moonNoise(e*3.5 + vec2(3.7, 1.9), MOON_MARIA_SEED) + 0.4*moonNoise(e*9.0 + vec2(0.7, 5.3), MOON_MARIA_SEED + 3);
    float albedo = (1.0 - MOON_MOTTLE*mottle)*(1.0 - MOON_MARIA*mare*(0.8 + 0.4*moonNoise(e*7.0 + vec2(9.1, 3.3), MOON_MARIA_SEED + 4)));
    //The craters' slopes, rims, rays and dark floors
    vec3 slope = vec3(0.0);
    float rim = 0.0;
    float rays = 0.0;
    for (int k = 0; k < MOON_CRATER_COUNT; k++) {
        vec4 c = MOON_CRATER[k];
        vec2 look = MOON_CRATER_LOOK[k];
        float s = moonCrater(p, c.xyz, c.w, 1.0, slope, rim);
        if (look.x > 0.0 && s < 30.0) {
            rays += look.x*moonRays(p, c.xyz, c.w, s);
        }
        if (look.y < 0.0) {
            albedo *= 1.0 + look.y*(1.0 - smoothstep(0.75, 0.95, s));
        }
    }
    ivec2 cell = ivec2(floor(e/MOON_CRATER_CELL));
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 i = cell + ivec2(x, y);
            float h = moonHash(i, MOON_CRATER_SEED);
            vec2 at = (vec2(i) + 0.2 + 0.6*vec2(moonHash(i, MOON_CRATER_SEED + 2), moonHash(i, MOON_CRATER_SEED + 6)))*MOON_CRATER_CELL;
            float ll = dot(at, at);
            if (h < MOON_CRATER_SHARE && ll < 0.96) {
                float w = h/MOON_CRATER_SHARE;
                moonCrater(p, vec3(at, sqrt(1.0 - ll)), MOON_CRATER_MIN + (MOON_CRATER_MAX - MOON_CRATER_MIN)*w*w*w, 1.0 - 0.65*mare, slope, rim);
            }
        }
    }
    //Lit along the tilted normal; the relief is the Lommel-Seeliger ratio against the smooth sphere (1 without craters)
    vec3 n = normalize(p - slope);
    float mu0 = dot(n, sunward);
    float smooth0 = dot(p, sunward);
    //Only near the terminator: a rim far into the night (the new moon's limb) never catches the sun
    float lit = min(smoothstep(-MOON_TERMINATOR, MOON_TERMINATOR, mu0), smoothstep(-MOON_NIGHT_REACH, -MOON_NIGHT_REACH*0.3, smooth0));
    float relief = 1.0;
    if (smooth0 > 0.0) {
        float tilted = max(mu0, 0.0)/(max(mu0, 0.0) + max(n.z, 0.05));
        float even = smooth0/(smooth0 + max(z, 0.05));
        relief = clamp(tilted/max(even, 0.0001), 0.0, MOON_RELIEF_MAX);
    }
    albedo *= 1.0 + (rim + rays)*smoothstep(0.1, 0.6, smooth0);
    return min(cover*albedo*mix(MOON_EARTHSHINE, relief, lit), 1.0);
#endif
}

//vanillaStars' residue kept along dir: all of it with the square moon; with the round moon none inside vanilla's square moon (W6 draws the
// moon itself, moonDisc). 0..1
float vanillaMoonKept(Atmosphere a, vec3 dir, vec4 celestial) {
    if (!celestialRound(celestial)) {
        return 1.0;
    }
    return 1.0 - discCover(dir, -a.sun.xyz, VANILLA_MOON_MASK);
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
#ifdef ATMOSPHERE_TINTS
    //2026-11-10 (session 8): times the picked moon colour's tint (Atmosphere.moonTint, exactly 1 at its default), its brightness kept relative
    // to the default moon's
    return tint*a.moonTint.rgb/luminance(tint)*MOON_DISC_LUMINANCE*night*(1.0 - overcastShare)*smoothstep(-MOON_HALF_SIZE, MOON_HALF_SIZE, -a.sun.y);
#else
    return tint/luminance(tint)*MOON_DISC_LUMINANCE*night*(1.0 - overcastShare)*smoothstep(-MOON_HALF_SIZE, MOON_HALF_SIZE, -a.sun.y);
#endif
}

//The sun disc seen along dir (sky pixels and reflected sky): sunRadiance times its shape (celestial: the shape slot), 0 outside
vec3 sunDisc(Atmosphere a, vec3 dir, vec4 celestial) {
    return sunRadiance(a)*sunShape(dir, a.sun.xyz, celestial);
}

//The round moon on sky pixels (the composite): moonRadiance times its phase-lit shape; 0 with the square moon (vanilla's moon, with its
// phase, stays in the sky through vanillaStars)
vec3 moonDisc(Atmosphere a, vec3 dir, vec4 celestial) {
    if (!celestialRound(celestial)) {
        return vec3(0.0);
    }
    return moonRadiance(a)*moonShape(dir, -a.sun.xyz, celestial);
}

//One disc of brightness radiance facing centre in a reflected direction r: the mirrored disc (sharp: its shape along r) on calm water, a
// round lobe cos^GLINT_POWER at GLINT_PEAK of its brightness (times lit, the share of the disc that shines) where the water ripples
// (ripple 0..1, rippleAmount), mixed by ripple
vec3 glintOf(vec3 radiance, vec3 r, vec3 centre, float sharp, float lit, float ripple) {
    vec3 mirrored = radiance*sharp;
    float k = ripple > 0.0 ? min(ripple, 1.0) : 0.0;
    if (k <= 0.0) {
        return mirrored;
    }
    return mix(mirrored, radiance*(pow(max(dot(r, centre), 0.0), GLINT_POWER)*GLINT_PEAK*lit), k);
}

//The sun and moon in a reflected direction r: the mirrored discs (in the shape celestial chooses; the round moon with its phase) on calm
// water, round glints where the water ripples (ripple 0..1, rippleAmount). The sun's is multiplied by the sun visibility at the water (0..1:
// an island's shadow takes the glint); the moon's is not: the shadow maps are cast from the sun's direction and say nothing about the moon
// (at night its glint is unshadowed)
vec3 discGlint(Atmosphere a, vec3 r, float ripple, float sunVisibility, vec4 celestial) {
    float v = sunVisibility > 0.0 ? min(sunVisibility, 1.0) : 0.0;
    vec3 moon = -a.sun.xyz;
    return glintOf(sunRadiance(a), r, a.sun.xyz, sunShape(r, a.sun.xyz, celestial), 1.0, ripple)*v
            + glintOf(moonRadiance(a), r, moon, moonShape(r, moon, celestial), moonLit(celestial), ripple);
}

//The sky seen in a reflected direction r without the clouds (B8): the sky colour for the direction, its glow and the discs (or
// glints, discGlint with the sun visibility at the water). Finite and non-negative. 2026-11-09 (the user: at sunrise the sun's
// reflection showed through icebergs and hills): sunOpen 0..1 is how much of the reflected ray the scene leaves open toward the sky
// (reflection.comp: 0 where the march saw the ray pass behind terrain beyond the water, 1 - the hit's weight where it hit). It takes
// the glow around the sun (or the moon) and both glints: the march sees terrain at any distance on screen, while the shadow maps
// (sunVisibility, the sun's glint only) know it only near the camera. The sky colour itself stays (B8). celestial: the discs' shape slot
vec3 reflectedSky(Atmosphere a, vec3 r, float ripple, float sunVisibility, float sunOpen, vec4 celestial) {
    float open = sunOpen > 0.0 ? min(sunOpen, 1.0) : 0.0;
    return skyColour(a, r) + (sunGlow(a, r) + discGlint(a, r, ripple, sunVisibility, celestial))*open;
}

//What a ray that finds nothing on screen reflects: the (hazed, clouded) sky where the water sees it (skyAccess 1), the water's own
// colour where it does not (under an overhang or in a cave: the reflection then changes nothing)
vec3 reflectFallback(vec3 sky, vec3 own, float skyAccess) {
    float s = skyAccess > 0.0 ? min(skyAccess, 1.0) : 0.0;
    return mix(own, sky, s);
}

// ---- the sun's open share (2026-11-10, sunopen.glsl) ----
//
// The user: at sunrise and sunset the sun shone through ice spikes and hills. The haze's sun-side glow (atmosphere.glsl's glowLobe) was
// added in front of every surface as if the air there saw the sun, so a hill standing in front of the low sun wore the sun's halo.
// The air in front of a surface sees the sun about as much as the camera does: the composite and the reflection pass look at the
// sun's disc on screen through a 3 x 3 grid of taps at SUN_OPEN_TAP of its half size and count the taps that show the sky (any surface
// hides it: terrain, packed ice, and the sea or an ice sheet at the horizon for a sun half below it). Taps off the screen
// and a sun behind the camera count as open (nothing known: the glow as before). The glow lobe is so narrow (GLOW_G) that the share
// only matters near the sun: it is blended in from SUN_OPEN_COS_START to SUN_OPEN_COS_FULL of the cosine to the sun (the lobe is
// 0.1% of its peak at the start), and nothing is sampled farther out
const float SUN_OPEN_TAP = 0.66;
const float SUN_OPEN_COS_START = 0.7;
const float SUN_OPEN_COS_FULL = 0.85;

//How much of the open share a pixel cosToSun from the sun takes (0: the glow as before)
float sunOpenWeight(float cosToSun) {
    return smoothstep(SUN_OPEN_COS_START, SUN_OPEN_COS_FULL, cosToSun);
}

//The sun's open share applied at weight (sunOpenWeight) to a pixel: 1 at weight 0, share at weight 1
float sunOpenAt(float share, float weight) {
    float s = share > 0.0 ? min(share, 1.0) : 0.0;
    float w = weight > 0.0 ? min(weight, 1.0) : 0.0;
    return mix(1.0, s, w);
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
    //The height is steep times the cell across (RIPPLE_PERIOD/(n.x m) blocks): the slope across is steep times g.x, along it
    // scaled down by the longer cell
    float w = steep*rippleFade(RIPPLE_PERIOD/(n.x*m), footprint);
    //2026-11-10 (session 7): an octave faded out (under RIPPLE_FADE_NONE pixels a cell: the finer ones on far water) adds nothing;
    // its gradient's four hashes are skipped
    if (!(w > 0.0)) {
        return vec2(0.0);
    }
    vec2 q = vec2(dir.x*p.x + dir.y*p.y, dir.x*p.y - dir.y*p.x)*n/RIPPLE_PERIOD;
    vec2 g = rippleGradient(q, n, seed);
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

//2026-11-09 (the user: at sunrise the sun's reflection showed through icebergs and hills): whether a step of the reflected ray says
// the sky along it is blocked, though no hit was taken (the thickness test misses rays that graze a far hill near its foot, and the
// clearance rejects the hill's foot itself). The ray (view z rayZ) has passed behind the surface the screen shows there (view z
// sceneZ; view z is negative in front of the camera), that surface lies beyond the reflecting point (originZ), stands above its plane
// (heightAbove, blocks, more than the hit clearance) and is no reflective translucent (hitMark, as reflectAcceptsHit). The water lies
// below the camera, so a ray from it that passes behind ground standing beyond it runs into that ground (a height field: only an
// overhang, a canopy, lets such a ray through)
bool reflectBlocksSky(float hitMark, float heightAbove, float dist, float rayZ, float sceneZ, float originZ) {
    bool translucent = abs(hitMark) >= REFLECT_MARK_MIN && hitMark >= -WATER_REFLECTION;
    return !translucent && rayZ < sceneZ && sceneZ < originZ && heightAbove > reflectHitClearance(dist);
}
