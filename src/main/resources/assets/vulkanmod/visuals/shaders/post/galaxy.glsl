//2026-11-13 (shader roadmap session 19, the user: "the End's sky as a galaxy, as AstraLex draws it"): the End's galaxy along a view
// direction: a Milky Way band with its bright core, star clouds and dark dust lanes, faint nebulae over the whole sky (GalaxyMap: baked
// once into an equirectangular map, read here) and three layers of stars drawn here, denser along the band. No uniforms or bindings: any
// shader can import it. GalaxyMath.java mirrors every const and function here: change both together (DimensionEffectsTest compares them).
//
// UNITS. Colours are W2's scene units (postmath.glsl). Directions are WORLD space unit vectors (y up) from the camera; the galaxy is at
// infinity (only the direction matters). Angles are radians.
//
// THE MODEL (a painted sky, not a simulation; the shapes after the Milky Way as photographed, e.g. Mellinger 2009, "A Color All-Sky
// Panorama Image of the Milky Way", PASP 121):
//   - Galactic coordinates: latitude b from the band's plane (pole GALAXY_POLE), longitude l from the core (GALAXY_CORE) around it.
//   - The disc: exp(-(b / width)^2), wider around the core (the bulge), dimmer toward the anticentre (down to GALAXY_DISC_FLOOR). The
//     band's light (bulge, star clouds, dust, nebulae) is GalaxyMap's: value-noise fBm the shader would take 4 to 5 ms a frame for on an
//     AMD iGPU at 1280x720 (session 19), baked once on the CPU into the map (galaxyMapCoord's equirectangular layout, the square root of
//     the light over GALAXY_MAP_SCALE in 8 bits).
//   - Stars: one per cell of a cube-map grid, kept inside its cell (so a pixel looks at its own cell only), present with a probability
//     that rises toward the band, brightness from a steep power of a uniform number (few bright, many faint), one of four colours, drawn
//     as a Gaussian about GALAXY_STAR_SIGMA pixels wide. A third, dense layer of faint stars lies only along the band (its grain).
//
// WHAT TO CALL
//   galaxy(dir, strength, pixelAngle, mapTexel): the light (rgb, scene units, >= 0) to add to a sky pixel along dir. strength 0 (or
//   less): nothing, and nothing is evaluated. pixelAngle: the angle one pixel spans (radians; the stars' width). mapTexel: the map read
//   at galaxyMapCoord(dir) (rgb as stored).

//The band's pole and its core's direction (unit vectors, the core at right angles to the pole; the band peaks 57 degrees up)
const float GALAXY_POLE_X = 0.6198;
const float GALAXY_POLE_Y = 0.5499;
const float GALAXY_POLE_Z = -0.5599;
const float GALAXY_CORE_X = 0.4188;
const float GALAXY_CORE_Y = 0.3715;
const float GALAXY_CORE_Z = 0.8286;
//The disc's half width (radians, e^-1), its widening around the core (share) over GALAXY_BULGE_REACH of longitude, and its light along
// the band: 1 at the core down to GALAXY_DISC_FLOOR past GALAXY_DISC_REACH
const float GALAXY_DISC_WIDTH = 0.11;
const float GALAXY_BULGE_WIDEN = 0.9;
const float GALAXY_BULGE_REACH = 0.7;
const float GALAXY_DISC_FLOOR = 0.35;
const float GALAXY_DISC_REACH = 1.3;
//The map's light at a stored 1 (scene units; the map stores the square root of the light over it)
const float GALAXY_MAP_SCALE = 0.6;
//Stars: per layer the cube-face grid size, the probability away from and added along the band, and the light of the brightest
const float GALAXY_FAINT_CELLS = 700.0;
const float GALAXY_FAINT_BASE = 0.05;
const float GALAXY_FAINT_BAND = 0.35;
const float GALAXY_FAINT_LIGHT = 0.6;
const float GALAXY_BRIGHT_CELLS = 220.0;
const float GALAXY_BRIGHT_BASE = 0.1;
const float GALAXY_BRIGHT_BAND = 0.25;
const float GALAXY_BRIGHT_LIGHT = 3.0;
//The band's grain: faint stars only along it (the probability to the band's square)
const float GALAXY_GRAIN_CELLS = 1500.0;
const float GALAXY_GRAIN_BAND = 0.55;
const float GALAXY_GRAIN_LIGHT = 0.25;
//A star's light: GALAXY_STAR_MIN + its rank to GALAXY_STAR_POWER; its centre within the middle GALAXY_STAR_INSET..1 - INSET of the cell;
// its width in pixels
const float GALAXY_STAR_MIN = 0.08;
const float GALAXY_STAR_POWER = 6.0;
const float GALAXY_STAR_INSET = 0.2;
const float GALAXY_STAR_SIGMA = 0.65;
//The stars' light (scene units at strength 1)
const float GALAXY_STAR_BRIGHTNESS = 0.1;
//Hash constants (Teschner et al. 2003 primes, Wellons 2018's finaliser, as aurora.glsl)
const int GALAXY_PRIME_X = 73856093;
const int GALAXY_PRIME_Y = 19349663;
const int GALAXY_PRIME_Z = 83492791;
const float GALAXY_PI = 3.14159265;

//The hash of integer lattice point i: 0..1 from its low 16 bits
float galaxyHash(ivec3 i) {
    uint h = (uint(i.x)*uint(GALAXY_PRIME_X)) ^ (uint(i.y)*uint(GALAXY_PRIME_Y)) ^ (uint(i.z)*uint(GALAXY_PRIME_Z));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return float(h & 0xFFFFu)/65535.0;
}

//3D value noise 0..1 at x (lattice units, smoothstep fade; heat.glsl's too)
float galaxyNoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = x - i;
    vec3 u = f*f*(3.0 - 2.0*f);
    ivec3 c = ivec3(i);
    float a = mix(galaxyHash(c), galaxyHash(c + ivec3(1, 0, 0)), u.x);
    float b = mix(galaxyHash(c + ivec3(0, 1, 0)), galaxyHash(c + ivec3(1, 1, 0)), u.x);
    float d = mix(galaxyHash(c + ivec3(0, 0, 1)), galaxyHash(c + ivec3(1, 0, 1)), u.x);
    float e = mix(galaxyHash(c + ivec3(0, 1, 1)), galaxyHash(c + ivec3(1, 1, 1)), u.x);
    return mix(mix(a, b, u.y), mix(d, e, u.y), u.z);
}

//The map's coordinate (0..1) along dir: x the azimuth from -z through +x, y from straight up (0) to straight down (1)
vec2 galaxyMapCoord(vec3 dir) {
    return vec2(atan(dir.x, -dir.z)/(2.0*GALAXY_PI) + 0.5, acos(clamp(dir.y, -1.0, 1.0))/GALAXY_PI);
}

//The disc's light along dir (0..1): where the band is
float galaxyDisc(vec3 dir) {
    vec3 pole = vec3(GALAXY_POLE_X, GALAXY_POLE_Y, GALAXY_POLE_Z);
    vec3 core = vec3(GALAXY_CORE_X, GALAXY_CORE_Y, GALAXY_CORE_Z);
    float b = asin(clamp(dot(dir, pole), -1.0, 1.0));
    float l = atan(dot(dir, cross(pole, core)), dot(dir, core));
    float width = GALAXY_DISC_WIDTH*(1.0 + GALAXY_BULGE_WIDEN*exp(-(l/GALAXY_BULGE_REACH)*(l/GALAXY_BULGE_REACH)));
    float along = GALAXY_DISC_FLOOR + (1.0 - GALAXY_DISC_FLOOR)*exp(-(l/GALAXY_DISC_REACH)*(l/GALAXY_DISC_REACH));
    return exp(-(b/width)*(b/width))*along;
}

//One star layer's light along dir: the star of dir's cube-map cell (cells per face edge), present with probability chance
vec3 galaxyStars(vec3 dir, float cells, float chance, float light, int seed, float sigma) {
    vec3 a = abs(dir);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);
    float major = dir[axis];
    int ua = axis == 0 ? 2 : 0;
    int va = axis == 1 ? 2 : 1;
    vec2 uv = vec2(dir[ua], dir[va])/abs(major);
    vec2 cell = floor((uv*0.5 + 0.5)*cells);
    int face = axis*2 + (major > 0.0 ? 1 : 0);
    ivec3 id = ivec3(cell, face*997 + seed*7919);
    float rank = galaxyHash(id);
    if (!(rank < chance)) {
        return vec3(0.0);
    }
    vec2 at = (cell + GALAXY_STAR_INSET + (1.0 - 2.0*GALAXY_STAR_INSET)*vec2(galaxyHash(id + ivec3(0, 0, 1)), galaxyHash(id + ivec3(0, 0, 2))))/cells*2.0 - 1.0;
    vec3 s = vec3(0.0);
    s[axis] = sign(major);
    s[ua] = at.x;
    s[va] = at.y;
    vec3 d = dir - normalize(s);
    float flux = light*(GALAXY_STAR_MIN + pow(rank/chance, GALAXY_STAR_POWER));
    float t = galaxyHash(id + ivec3(0, 0, 3));
    vec3 colour = t < 0.35 ? vec3(0.7, 0.8, 1.0) : (t < 0.6 ? vec3(1.0) : (t < 0.82 ? vec3(1.0, 0.7, 0.92) : vec3(1.0, 0.86, 0.62)));
    return colour*(flux*exp(-dot(d, d)/(2.0*sigma*sigma)));
}

//The galaxy's light along dir (see the header)
vec3 galaxy(vec3 dir, float strength, float pixelAngle, vec3 mapTexel) {
    if (!(strength > 0.0)) {
        return vec3(0.0);
    }
    float band = clamp(galaxyDisc(dir)*1.5, 0.0, 1.0);
    float sigma = max(pixelAngle, 1e-5)*GALAXY_STAR_SIGMA;
    vec3 stars = galaxyStars(dir, GALAXY_FAINT_CELLS, GALAXY_FAINT_BASE + GALAXY_FAINT_BAND*band, GALAXY_FAINT_LIGHT, 1, sigma)
            + galaxyStars(dir, GALAXY_BRIGHT_CELLS, GALAXY_BRIGHT_BASE + GALAXY_BRIGHT_BAND*band, GALAXY_BRIGHT_LIGHT, 2, sigma)
            + galaxyStars(dir, GALAXY_GRAIN_CELLS, GALAXY_GRAIN_BAND*band*band, GALAXY_GRAIN_LIGHT, 3, sigma);
    vec3 light = mapTexel*mapTexel*GALAXY_MAP_SCALE;
    return (light + stars*GALAXY_STAR_BRIGHTNESS)*strength;
}
