//2026-11-13 (shader roadmap session 20, the user after AstraLex: "수중에서의 빛 굴절"): sunlight focused by the water's rippled surface onto
// what lies under it, for the composite (scene_copy.comp). No uniforms or bindings: the composite passes its causticParams (slot 71) in.
// CausticMath.java mirrors every const and function here: change both together (CausticTest compares them).
//
// The surface is a sum of CAUSTIC_WAVES travelling sine waves, h(s) = sum a_i sin(k_i . s - w_i t), whose wave vectors are whole numbers
// of cycles per CAUSTIC_PERIOD blocks (so the camera's wrap there never shows a seam) spread around the compass (fewer, or closer
// directions, drew a regular lattice of diagonal stripes), and whose angular speeds are whole numbers of cycles per CAUSTIC_TIME_PERIOD seconds (so the clock's wrap is seamless).
// A ray of sunlight through the surface at s is bent by the slope there and reaches the floor depth blocks down displaced by about
// depth eta grad h(s) (small slopes; eta = 1 - 1/1.333 for light coming down, Snell's law). The light that fell on a patch of the surface
// lands on a patch det(J) times as large, J = I + depth eta H(s) the mapping's Jacobian (H the Hessian of h), so the floor receives 1/|det J|
// of the sun's light: brighter where the surface focuses it (the bright web where J is singular: the caustic curves), darker between
// (Watt 1990, "Light-water interaction using backward beam tracing", SIGGRAPH; Stam 1996, "Random caustics: natural textures and wave
// theory revisited", SIGGRAPH technical sketch). Evaluated at the floor point's own surface point, s = x moved toward the sun by the refracted
// ray's run over the depth, as backward beam tracing would. CAUSTIC_SOFT keeps 1/|det J| finite on the curves, CAUSTIC_MAX caps it.
// The capped mean of 1/|det J| rises above 1 once the curves form (about 2 at 6 blocks): it is divided by CAUSTIC_MEAN (measured over 40 x 40
// blocks at three times, every CAUSTIC_MEAN_STEP blocks of depth) so the floor keeps the sun's light on average, brighter on the web and
// darker between, as it should.
//
// What the composite does with it: the floor's direct sunlight (its sun share of the light, sunPart) is scaled by the caustic, faded in
// over the first CAUSTIC_DEPTH_IN blocks under the surface and out as one wave's length covers fewer than CAUSTIC_FADE_FULL pixels (no
// shimmer far away), then by the strength setting.

const int CAUSTIC_WAVES = 6;
//The waves repeat every CAUSTIC_PERIOD blocks (RippleMath.PERIOD: the camera is passed wrapped there) and every CAUSTIC_TIME_PERIOD seconds
const float CAUSTIC_PERIOD = 240.0;
const float CAUSTIC_TIME_PERIOD = 120.0;
//Per wave: cycles per period along x and z (headings about 8, 47, 101, 139, 206 and 277 degrees; wavelengths 2.0..2.8 blocks: the web's
// cells about two blocks across, as the references show), cycles per time period (about 0.8..1.4 rad/s) and the slope's amplitude (a |k|)
const vec2 CAUSTIC_K[CAUSTIC_WAVES] = vec2[CAUSTIC_WAVES](vec2(99.0, 14.0), vec2(77.0, 83.0), vec2(-17.0, 86.0), vec2(-91.0, 79.0),
        vec2(-85.0, -42.0), vec2(13.0, -107.0));
const float CAUSTIC_CYCLES[CAUSTIC_WAVES] = float[CAUSTIC_WAVES](19.0, 23.0, 16.0, 27.0, 21.0, 25.0);
const float CAUSTIC_SLOPE[CAUSTIC_WAVES] = float[CAUSTIC_WAVES](0.17, 0.15, 0.14, 0.13, 0.14, 0.12);
//1 - 1/n for water's index 1.333
const float CAUSTIC_ETA = 0.25;
const float CAUSTIC_SOFT = 0.08;
const float CAUSTIC_MAX = 5.0;
//The floor is reached by the full pattern from CAUSTIC_DEPTH_IN blocks under the surface
const float CAUSTIC_DEPTH_IN = 0.75;
//Deeper than CAUSTIC_DEPTH_MAX blocks the pattern is taken at that depth (the waves' focus lies within it)
const float CAUSTIC_DEPTH_MAX = 12.0;
//A wavelength over fewer pixels than CAUSTIC_FADE_NONE shows no pattern, over CAUSTIC_FADE_FULL the full pattern
const float CAUSTIC_FADE_NONE = 3.0;
const float CAUSTIC_FADE_FULL = 10.0;
//The shortest wavelength (blocks), the one the fade is measured by
const float CAUSTIC_SHORTEST = 1.99;
//The capped gain's mean at 0, 2, 4 .. 12 blocks of depth (linear between)
const int CAUSTIC_MEAN_COUNT = 7;
const float CAUSTIC_MEAN_STEP = 2.0;
const float CAUSTIC_MEAN[CAUSTIC_MEAN_COUNT] = float[CAUSTIC_MEAN_COUNT](1.0, 1.113, 1.648, 2.082, 2.029, 1.822, 1.585);
//Water's refractive index
const float CAUSTIC_WATER_IOR = 1.333;
//Water seen from above whose length behind its surface is at least this has nothing behind it (lighting.comp's WATER_MAX_LENGTH, 1024)
const float CAUSTIC_BEHIND_MAX = 1000.0;

//CAUSTIC_MEAN at a depth (0..CAUSTIC_DEPTH_MAX)
float causticMean(float depth) {
    float x = clamp(depth/CAUSTIC_MEAN_STEP, 0.0, float(CAUSTIC_MEAN_COUNT - 1));
    int i = min(int(x), CAUSTIC_MEAN_COUNT - 2);
    return mix(CAUSTIC_MEAN[i], CAUSTIC_MEAN[i + 1], x - float(i));
}

//The light's gain 1/|det J| (over its mean) at the surface point s (world x, z, wrapped; blocks) for a floor depth blocks down, at time t (seconds)
float causticGain(vec2 s, float depth, float t) {
    float h00 = 0.0;
    float h01 = 0.0;
    float h11 = 0.0;
    for (int i = 0; i < CAUSTIC_WAVES; i++) {
        vec2 k = CAUSTIC_K[i]*(6.2831853/CAUSTIC_PERIOD);
        float phase = dot(k, s) - CAUSTIC_CYCLES[i]*(6.2831853/CAUSTIC_TIME_PERIOD)*t;
        //a = slope / |k|; the Hessian of a sin(phase) is -a sin(phase) k k^T
        float c = -CAUSTIC_SLOPE[i]/length(k)*sin(phase);
        h00 += c*k.x*k.x;
        h01 += c*k.x*k.y;
        h11 += c*k.y*k.y;
    }
    float d = min(depth, CAUSTIC_DEPTH_MAX);
    float f = d*CAUSTIC_ETA;
    float det = (1.0 + f*h00)*(1.0 + f*h11) - f*h01*f*h01;
    return min(1.0/(abs(det) + CAUSTIC_SOFT), CAUSTIC_MAX)*(1.0 + CAUSTIC_SOFT)/causticMean(d);
}

//The surface point whose refracted sunlight reaches the floor point x (world x, z) depth blocks down: x moved toward the sun (toSun, a
// world direction) by the refracted ray's run
vec2 causticSurfacePoint(vec2 x, float depth, vec3 toSun) {
    float horizontal = length(toSun.xz);
    if (horizontal < 1e-4) {
        return x;
    }
    float st = horizontal/CAUSTIC_WATER_IOR;
    float ct = sqrt(max(1.0 - st*st, 1e-4));
    return x + toSun.xz/horizontal*(st/ct)*depth;
}

//The factor on a floor pixel's light: 1 + (gain - 1) x its sun share x the fades x strength. world: the floor point relative to the
// camera; camera: the camera's x, z wrapped (CAUSTIC_PERIOD); footprint: blocks per pixel at the floor
float causticLight(vec3 world, float depth, vec3 toSun, float t, vec2 camera, float footprint, float sunShare, float strength) {
    if (!(strength > 0.0) || !(sunShare > 0.0) || !(depth > 0.0) || toSun.y <= 0.0) {
        return 1.0;
    }
    float pixels = footprint > 1e-6 ? CAUSTIC_SHORTEST/footprint : 1e6;
    float fade = smoothstep(CAUSTIC_FADE_NONE, CAUSTIC_FADE_FULL, pixels)*smoothstep(0.0, CAUSTIC_DEPTH_IN, depth);
    if (fade <= 0.0) {
        return 1.0;
    }
    vec2 s = causticSurfacePoint(world.xz + camera, depth, toSun);
    float gain = causticGain(s, depth, t);
    return max(1.0 + (gain - 1.0)*min(sunShare, 1.0)*fade*strength, 0.0);
}
