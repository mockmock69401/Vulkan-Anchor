//2026-11-13 (shader roadmap session 19, the user: "the Nether's heat, as AstraLex draws it"): the Nether's heat haze. The composite's
// .HEAT build (HEAT_PASS) works out, at a quarter of the size (each texel 4x4 pixels: the offsets are smooth; session 19 cost on an AMD
// iGPU at 1280x720: half size took 0.93 ms), how far the final colour reads the scene away from each pixel; postfinal.glsl
// reads the scene and the bloom there. Runs before the hand and the HUD are drawn, so neither shimmers. No uniforms or bindings; import
// galaxy.glsl first (its value noise). HeatMath.java mirrors every const and function here: change both together (DimensionEffectsTest compares them).
//
// UNITS. Distances in blocks, heights in world y, the time in seconds, offsets in pixels of a screen HEAT_REFERENCE_HEIGHT tall
// (scaled to the real height so the shimmer covers the same share of the view at any resolution).
//
// THE MODEL (heat shimmer is refraction by turbulent hot air: the refractive index of air falls as it warms, so rising eddies of hot air
// bend the light passing through them; e.g. Hecht, "Optics", 5th ed. 2017, 3.5 and 4.11; the image of a far object wavers more the
// longer the path through the hot air):
//   - Hot air everywhere in the Nether: the shimmer grows with the view distance past HEAT_NEAR (1 - exp(-path / HEAT_REACH)).
//   - The lava sea: the air is hotter near the sea level (HEAT_LAVA_LEVEL): the mean of exp(-height / HEAT_LAVA_HEIGHT) along the path,
//     times the same growth with distance.
//   - Lava on screen: right above lava (any lake or fall) the air rises off it: a lava pixel within HEAT_LAVA_REACH of the view height
//     below, at the pixel's distance or nearer, adds HEAT_LAVA by how close it is on screen (none past HEAT_NEAR_FULL's fade near the
//     camera: what is close does not shimmer).
//   - The offset: value noise over the view direction (one lattice per axis of the offset), drifting up (rising eddies) and sideways,
//     scaled by the heat.

//Where the shimmer starts and is full (the view distance, blocks), and the hot air's growth length
const float HEAT_NEAR = 4.0;
const float HEAT_NEAR_FULL = 16.0;
const float HEAT_REACH = 64.0;
//The sky's (no geometry: the Nether's far fog) distance
const float HEAT_SKY_DISTANCE = 256.0;
//The lava sea's level (vanilla's Nether: lava up to y 31) and its hot layer's height scale
const float HEAT_LAVA_LEVEL = 31.0;
const float HEAT_LAVA_HEIGHT = 12.0;
//The heat of the Nether's air, of the lava sea's layer and right above lava on screen, and the most heat
const float HEAT_AIR = 0.35;
const float HEAT_SEA = 0.9;
const float HEAT_LAVA = 1.0;
const float HEAT_MAX = 1.5;
//Lava on screen: how far below (share of the view height) and in how many taps
const float HEAT_LAVA_REACH = 0.12;
const int HEAT_LAVA_TAPS = 4;
//A lava pixel counts while the pixel is at least HEAT_LAVA_DEPTH_LOW of its distance (full at HEAT_LAVA_DEPTH_HIGH): nearer things are
// in front of the hot air
const float HEAT_LAVA_DEPTH_LOW = 0.6;
const float HEAT_LAVA_DEPTH_HIGH = 0.9;
//An offset that would read a surface nearer than this share of the pixel's distance is dropped
const float HEAT_NEARER_KEEP = 0.85;
//The noise: cells per radian of view direction, the rise and the sideways drift (cells per second)
const float HEAT_CELLS = 24.0;
const float HEAT_RISE = 1.4;
const float HEAT_DRIFT = 0.3;
//The offset at heat 1 (pixels of a HEAT_REFERENCE_HEIGHT screen)
const float HEAT_PIXELS = 5.0;
const float HEAT_REFERENCE_HEIGHT = 1440.0;
//The time wraps here (DimensionUniforms; the noise is not periodic, so the shimmer jumps once in about 7 hours)
const float HEAT_TIME_WRAP = 25600.0;

//How much a lava pixel found at share below (0 = this pixel .. 1 = HEAT_LAVA_REACH below) heats a pixel dist blocks away, the lava
// lavaDist blocks away
float heatLavaWeight(float below, float dist, float lavaDist) {
    float b = clamp(1.0 - below, 0.0, 1.0);
    return b*b*smoothstep(HEAT_LAVA_DEPTH_LOW, HEAT_LAVA_DEPTH_HIGH, dist/max(lavaDist, 0.001));
}

//The heat (0..HEAT_MAX x strength) of a pixel dist blocks away along a direction rising dirY, the camera at world y camY, lava the lava
// weight (heatLavaWeight, 0 without)
float heatAmount(float strength, float dist, float camY, float dirY, float lava) {
    if (!(strength > 0.0)) {
        return 0.0;
    }
    float far = 1.0 - exp(-max(dist - HEAT_NEAR, 0.0)/HEAT_REACH);
    float h0 = max(camY - HEAT_LAVA_LEVEL, 0.0)/HEAT_LAVA_HEIGHT;
    float h1 = max(camY + dirY*dist - HEAT_LAVA_LEVEL, 0.0)/HEAT_LAVA_HEIGHT;
    float sea = abs(h1 - h0) < 0.001 ? exp(-h0) : (exp(-h0) - exp(-h1))/(h1 - h0);
    float near = smoothstep(HEAT_NEAR, HEAT_NEAR_FULL, dist);
    return strength*min(HEAT_AIR*far + HEAT_SEA*sea*far + HEAT_LAVA*lava*near, HEAT_MAX);
}

//The offset (pixels) for a pixel along dir at time t (seconds) with heat, on a screen height pixels tall
vec2 heatOffsetPixels(vec3 dir, float t, float heat, float height) {
    vec3 x = dir*HEAT_CELLS + vec3(t*HEAT_DRIFT, -t*HEAT_RISE, 0.7*t*HEAT_DRIFT);
    vec2 n = (vec2(galaxyNoise(x), galaxyNoise(x + 57.0)) - 0.5)*2.0;
    return n*(heat*HEAT_PIXELS*height/HEAT_REFERENCE_HEIGHT);
}
