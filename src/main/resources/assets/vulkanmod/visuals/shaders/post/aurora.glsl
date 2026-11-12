//2026-11-09 (session 5, the user: "auroras that spread across the sky at night, and in the End, as other shader packs have"): the
// aurora's light along a view direction. No uniforms or bindings: any shader can import it. AuroraMath.java mirrors every const and
// function here: change both together (AuroraTest compares them). AuroraUniforms writes the parameters.
//
// UNITS. Colours are W2's scene units (postmath.glsl). Directions are WORLD space unit vectors (y up) from the camera. Lengths in the
// layer are in units of its lower border's height (about 100 km): the aurora is so far away that the camera's position does not matter,
// only its direction (the sky is at infinity, as for skyColour).
//
// THE MODEL (after Baranoski, Rokne, Shirley, Trondsen and Bastos 2003, "Simulating the aurora", The Journal of Visualization and Computer
// Animation 14, and Lawlor and Genetti 2011, "Interactive volume rendering aurora on the GPU", WSCG 19):
//   - Auroras are thin folded curtains of emitting air, hanging along the magnetic field lines (here vertical) over a footprint of long
//     arcs; the arcs run roughly east-west (along the magnetic latitude), so the footprint is stretched along x (Baranoski et al. 2003).
//     Here the footprint is the crest line of a domain-warped 2D value noise (1 - |2n - 1| raised to a power: thin winding curves).
//   - The emission is optically thin: the view ray is marched through the layer and the emission summed without absorption (Lawlor and
//     Genetti 2011: the curtain is a 2D footprint times a function of the height above its lower border).
//   - The lower border (about 100 km) is sharp and its height varies along the arc; the light falls off above it. Its colour follows the
//     height: the green oxygen line (557.7 nm) from the border up, the red oxygen line (630.0 nm) at the top (above about 200 km), and in
//     bright displays a pink-violet fringe at the very bottom (N2+ 427.8 nm and the N2 first positive bands) (Baranoski et al. 2003).
//   - Rays: vertical striations along the field lines, a brightness that changes along the arc but not with height.
// The End (params.z 1) uses the same curtains in that dimension's colours: pink-violet below, violet above.
//
// WHAT TO CALL
//   aurora(dir, params, tintLow, tintMid, tintTop, steps, jitter): the light (rgb, scene units, >= 0) to add to a sky pixel along dir, BEFORE the far clouds go
//   over it (they hide it). params: x the strength (0: nothing, and nothing is evaluated), y the time in seconds (wrapped at
//   AURORA_TIME_WRAP: every drift repeats then), z 1 in the End, w 0. steps: AURORA_STEPS for the sky, AURORA_REFLECTION_STEPS for
//   reflections. jitter 0..1 per pixel (the march's start; any value is fine). 2026-11-10 (session 8, the colour pickers): tintLow,
//   tintMid, tintTop multiply the lower fringe's, the main body's and the top's emission colours (CelestialUniforms' AURORA_TINTS slots:
//   the picked colours over their defaults, exactly 1 at the defaults; in the End all three the End colour's).

//The layer: its lower border at 1 and its top at AURORA_TOP (about 100 to 300 km)
const float AURORA_TOP = 3.0;
//March samples through the layer: the sky and the reflections (session 5 cost: 16 cost about 1.5 ms at 2560x1440 over a whole-sky view;
// the per-pixel jitter, turning each frame with TAA, hides the fewer samples)
const int AURORA_STEPS = 12;
const int AURORA_REFLECTION_STEPS = 6;
//The march spans the layer, but no more than AURORA_REACH layer heights horizontally past where it enters it (low rays cross the layer
// over hundreds of heights), and the light fades with the horizontal distance d as exp(-d / AURORA_EXTINCTION): the air near the horizon
// hides what lies beyond
const float AURORA_REACH = 10.0;
const float AURORA_EXTINCTION = 12.0;
//Fade toward the horizon: by the sine of the elevation (the long low path through the lower air dims it)
const float AURORA_HORIZON_LOW = 0.0;
const float AURORA_HORIZON_HIGH = 0.22;
//The footprint: noise cells per layer height across the arcs (z) and AURORA_STRETCH times longer along them (x)
const float AURORA_SCALE = 0.9;
const float AURORA_STRETCH = 2.5;
//Domain warp (the curtains' folds): its cells are AURORA_WARP_SCALE of the footprint's, it moves points by up to AURORA_WARP cells
const float AURORA_WARP_SCALE = 0.35;
const float AURORA_WARP = 2.6;
//The crest's sharpness (power of 1 - |2n - 1|): higher is thinner curtains
const float AURORA_SHARPNESS = 18.0;
//Drift of the warp and of the crests (cells per second; multiples of 1 / 100 so they repeat every AURORA_TIME_WRAP seconds)
const float AURORA_WARP_DRIFT = 0.01;
const float AURORA_CREST_DRIFT = 0.02;
const float AURORA_TIME_WRAP = 25600.0;
//The noise repeats every AURORA_PERIOD cells (so the drifts wrap without a seam)
const float AURORA_PERIOD = 256.0;
//Rays: AURORA_RAY_CELLS cells per footprint cell along the arc, their depth (share of the light they modulate) and their drift
const float AURORA_RAY_CELLS = 24.0;
const float AURORA_RAY_DEPTH = 0.8;
const float AURORA_RAY_DRIFT = 0.05;
//2026-11-12 (session 14 follow-up, the half-resolution sky): rays finer than a pixel fold back into coarse ripples (moire at 1280x720 drawn
// at half size), so where a pixel spans AURORA_RAY_ALIAS_LOW..HIGH ray cells or more they fade to their mean AURORA_RAY_MEAN (the light
// they average to); only with a pixel's angle given (the half-size pass), the full-size sky is drawn as before
const float AURORA_RAY_MEAN = 0.5;
const float AURORA_RAY_ALIAS_LOW = 0.2;
const float AURORA_RAY_ALIAS_HIGH = 0.4;
//How the brightness changes along and across the arcs (a slow noise, cells of the warp): the darkest part keeps AURORA_PATCH_MIN
const float AURORA_PATCH_MIN = 0.15;
//The lower border's height above 1 varies by up to AURORA_BORDER_LIFT along the arc, and it is soft over AURORA_BORDER_EDGE
const float AURORA_BORDER_LIFT = 0.5;
const float AURORA_BORDER_EDGE = 0.04;
//The green light falls off above the border with this scale; the red grows from AURORA_RED_LOW to AURORA_RED_HIGH and falls off with
// AURORA_RED_SCALE above that; the fringe at the border falls off with AURORA_FRINGE_SCALE
const float AURORA_GREEN_SCALE = 0.6;
const float AURORA_RED_LOW = 1.4;
const float AURORA_RED_HIGH = 2.2;
const float AURORA_RED_SCALE = 0.8;
const float AURORA_RED_SHARE = 0.2;
const float AURORA_FRINGE_SCALE = 0.07;
const float AURORA_FRINGE_SHARE = 0.35;
//Emission colours (linear Rec. 709): the Overworld's oxygen green (toward teal, as photographs and the references show it), the oxygen
// red at the top (mixed with the blue nitrogen light it reads magenta) and the nitrogen violet fringe
const float AURORA_GREEN_R = 0.12;
const float AURORA_GREEN_G = 1.0;
const float AURORA_GREEN_B = 0.62;
const float AURORA_RED_R = 0.8;
const float AURORA_RED_G = 0.12;
const float AURORA_RED_B = 0.55;
const float AURORA_FRINGE_R = 0.7;
const float AURORA_FRINGE_G = 0.2;
const float AURORA_FRINGE_B = 1.0;
//The End's colours (the references draw the End's curtains purple): pink-violet at the border, violet above, blue-violet at the top
const float AURORA_END_LOW_R = 0.95;
const float AURORA_END_LOW_G = 0.35;
const float AURORA_END_LOW_B = 1.0;
const float AURORA_END_MID_R = 0.6;
const float AURORA_END_MID_G = 0.25;
const float AURORA_END_MID_B = 1.0;
const float AURORA_END_TOP_R = 0.3;
const float AURORA_END_TOP_G = 0.35;
const float AURORA_END_TOP_B = 1.0;
//The light per unit length of curtain at strength 1 (scene units)
const float AURORA_BRIGHTNESS = 1.6;
//Hash constants (Teschner et al. 2003 primes, Wellons 2018's finaliser, as water.glsl)
const int AURORA_PRIME_X = 73856093;
const int AURORA_PRIME_Z = 19349663;
const int AURORA_PRIME_SEED = 83492791;

//The hash of lattice corner c (wrapped into AURORA_PERIOD cells per axis) for seed
uint auroraHash(vec2 c, uint seed) {
    vec2 w = c - AURORA_PERIOD*floor(c/AURORA_PERIOD);
    ivec2 i = ivec2(w);
    uint h = (uint(i.x)*uint(AURORA_PRIME_X)) ^ (uint(i.y)*uint(AURORA_PRIME_Z)) ^ (seed*uint(AURORA_PRIME_SEED));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

//Two values 0..1 at corner c from one hash (its low and high 16 bits)
vec2 auroraCorner(vec2 c, uint seed) {
    uint h = auroraHash(c, seed);
    return vec2(float(h & 0xFFFFu), float(h >> 16u))/65535.0;
}

//2D value noise 0..1 at x (lattice units, smoothstep fade), repeating every AURORA_PERIOD cells: two independent channels (session 5
// cost: one hash per corner serves both)
vec2 auroraNoise(vec2 x, uint seed) {
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 u = f*f*(3.0 - 2.0*f);
    vec2 a = auroraCorner(i, seed);
    vec2 b = auroraCorner(i + vec2(1.0, 0.0), seed);
    vec2 c = auroraCorner(i + vec2(0.0, 1.0), seed);
    vec2 d = auroraCorner(i + vec2(1.0, 1.0), seed);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

//1D value noise 0..1 at x (two hashes), repeating every AURORA_PERIOD cells
float auroraNoise1(float x, uint seed) {
    float i = floor(x);
    float f = x - i;
    return mix(auroraCorner(vec2(i, 0.0), seed).x, auroraCorner(vec2(i + 1.0, 0.0), seed).x, f*f*(3.0 - 2.0*f));
}

//The footprint at horizontal point q (layer heights) at time t: x the curtain (0..1, 1 on a crest), y the patch brightness 0..1, z the
// lower border's lift 0..1, w the coordinate along the arc (for the rays)
vec4 auroraFootprint(vec2 q, float t) {
    vec2 p = vec2(q.x/AURORA_STRETCH, q.y)*AURORA_SCALE;
    vec2 w = auroraNoise(p*AURORA_WARP_SCALE + vec2(t*AURORA_WARP_DRIFT, -t*AURORA_WARP_DRIFT), 1u);
    vec2 warped = p + (w - 0.5)*AURORA_WARP;
    float n = auroraNoise(warped + vec2(t*AURORA_CREST_DRIFT, 0.0), 3u).x;
    float crest = pow(1.0 - abs(2.0*n - 1.0), AURORA_SHARPNESS);
    return vec4(crest, w, warped.x);
}

//The emission colour at height h (layer heights, 1 = the lowest border) above a border at border, in the End's colours when end, each
// times its tint (session 8)
vec3 auroraColour(float h, float border, bool end, vec3 tintLow, vec3 tintMid, vec3 tintTop) {
    float above = h - border;
    float bottom = smoothstep(-AURORA_BORDER_EDGE, AURORA_BORDER_EDGE, above);
    float a = max(above, 0.0);
    float green = exp(-a/AURORA_GREEN_SCALE);
    float red = AURORA_RED_SHARE*smoothstep(AURORA_RED_LOW, AURORA_RED_HIGH, h)*exp(-max(h - AURORA_RED_HIGH, 0.0)/AURORA_RED_SCALE);
    float fringe = AURORA_FRINGE_SHARE*exp(-a/AURORA_FRINGE_SCALE);
    vec3 low = (end ? vec3(AURORA_END_LOW_R, AURORA_END_LOW_G, AURORA_END_LOW_B) : vec3(AURORA_FRINGE_R, AURORA_FRINGE_G, AURORA_FRINGE_B))*tintLow;
    vec3 mid = (end ? vec3(AURORA_END_MID_R, AURORA_END_MID_G, AURORA_END_MID_B) : vec3(AURORA_GREEN_R, AURORA_GREEN_G, AURORA_GREEN_B))*tintMid;
    vec3 top = (end ? vec3(AURORA_END_TOP_R, AURORA_END_TOP_G, AURORA_END_TOP_B) : vec3(AURORA_RED_R, AURORA_RED_G, AURORA_RED_B))*tintTop;
    return (mid*green + top*red + low*fringe)*bottom;
}

//The aurora's light along dir (see the header). 0 with params.x <= 0, at or below the horizon, and for steps < 1
vec3 aurora(vec3 dir, vec4 params, vec3 tintLow, vec3 tintMid, vec3 tintTop, int steps, float jitter, float pixelAngle) {
    if (!(params.x > 0.0) || !(dir.y > AURORA_HORIZON_LOW) || steps < 1) {
        return vec3(0.0);
    }
    float fade = smoothstep(AURORA_HORIZON_LOW, AURORA_HORIZON_HIGH, dir.y);
    float t = params.y;
    bool end = params.z > 0.5;
    //The layer between heights 1 and AURORA_TOP along dir, no farther than AURORA_REACH horizontally past its entry
    vec2 ground = dir.xz;
    float horizontal = length(ground);
    float enter = 1.0/dir.y;
    float leave = AURORA_TOP/dir.y;
    if (horizontal > 0.0001) {
        leave = min(leave, enter + AURORA_REACH/horizontal);
    }
    float dt = (leave - enter)/float(steps);
    float j = clamp(jitter, 0.0, 1.0);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < steps; i++) {
        float s = enter + (float(i) + j)*dt;
        vec3 x = dir*s;
        vec4 f = auroraFootprint(x.xz, t);
        if (f.x < 0.001) {
            continue;
        }
        float rayNoise = auroraNoise1(f.w*AURORA_RAY_CELLS + t*AURORA_RAY_DRIFT, 4u);
        if (pixelAngle > 0.0) {
            //Ray cells across the pixel at this point: its angle times the distance (s, dir is a unit vector) in footprint cells along the arc
            float cells = pixelAngle*s*AURORA_SCALE/AURORA_STRETCH*AURORA_RAY_CELLS;
            rayNoise = mix(rayNoise, AURORA_RAY_MEAN, smoothstep(AURORA_RAY_ALIAS_LOW, AURORA_RAY_ALIAS_HIGH, cells));
        }
        float rays = 1.0 - AURORA_RAY_DEPTH*rayNoise;
        float bright = mix(AURORA_PATCH_MIN, 1.0, smoothstep(0.25, 0.75, f.y));
        float border = 1.0 + AURORA_BORDER_LIFT*f.z;
        sum += auroraColour(x.y, border, end, tintLow, tintMid, tintTop)*(f.x*rays*bright*exp(-s*horizontal/AURORA_EXTINCTION));
    }
    return sum*(dt*AURORA_BRIGHTNESS*params.x*fade);
}

//The aurora's light along dir at full size (no ray fading: see AURORA_RAY_ALIAS_LOW)
vec3 aurora(vec3 dir, vec4 params, vec3 tintLow, vec3 tintMid, vec3 tintTop, int steps, float jitter) {
    return aurora(dir, params, tintLow, tintMid, tintTop, steps, jitter, 0.0);
}
