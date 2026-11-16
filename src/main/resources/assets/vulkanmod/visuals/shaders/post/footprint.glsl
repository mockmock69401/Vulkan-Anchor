//2026-11-15 (shader roadmap session 25, the user: footprints in snow and sand that stay a while and fade): the composite's footprints.
// FootprintMath.java mirrors the constants (FootprintTest compares them). The includer declares, before importing this:
//   usampler2D footprintMap   FootprintMath's map: FOOT_MAP x FOOT_MAP R32UI words, world texel & FOOT_MASK, FOOT_PER_BLOCK per block
//   vec4 footParams           x the strength (0: off), y now's time step, z the camera's y, w the depth and the duration (2026-11-16, the
//                             Experimental page: percents packed as depth + FOOT_PACK x duration)
//   vec4 footCamera           xy the camera's world texel modulo FOOT_CAMERA_WRAP, zw the camera within it
// A word: bits 0..11 the print's time step (FOOT_TIME_STEP s, wrapping at FOOT_TIME_WRAP), 12..19 the depth 0..255, 20..23 the surface's
// block y & 15, 24..27 the wrap tag ((texel x >> FOOT_MAP_SHIFT) & 3 | ((texel z >> FOOT_MAP_SHIFT) & 3) << 2), 31 sand.
// THE LOOK: the hollow's depth h (the four texels around the pixel, bilinear, each faded by its own age: held for FOOT_HOLD of its fade time,
// then gone smoothly) shades the top face: darker toward the bottom (FOOT_DARK, sand FOOT_SAND_DARK), and its walls lit as a surface FOOT_DEPTH
// (sand FOOT_SAND_DEPTH) deep would be by the sun or the moon (the direct light's share only), so the sun-facing wall is bright and the far
// one shaded; snow's hollow a little blue (FOOT_SNOW_BLUE: the sky fills its shade).

const int FOOT_MAP = 1024;
const int FOOT_MASK = 1023;
const int FOOT_PER_BLOCK = 16;
const int FOOT_MAP_SHIFT = 10;
const int FOOT_TIME_WRAP = 4096;
const float FOOT_TIME_STEP = 0.125;
const int FOOT_CAMERA_WRAP = 4096;
const float FOOT_SNOW_FADE = 25.0;
const float FOOT_SAND_FADE = 15.0;
const float FOOT_HOLD = 0.4;
const float FOOT_END = 28.0;
const float FOOT_FADE_END = 6.0;
const float FOOT_DEPTH = 0.12;
const float FOOT_SAND_DEPTH = 0.05;
const float FOOT_DARK = 0.45;
const float FOOT_SAND_DARK = 0.3;
const float FOOT_SNOW_BLUE = 0.15;
const float FOOT_LIT_MIN = 0.25;
const float FOOT_LIT_MAX = 1.8;
const float FOOT_PACK = 1024.0;

//2026-11-16: footParams.w's duration (x, a factor of the fade times) and depth (y, a factor of FOOT_DEPTH / FOOT_SAND_DEPTH)
vec2 footTuning() {
    float duration = floor(footParams.w/FOOT_PACK);
    return vec2(max(duration, 1.0)/100.0, (footParams.w - duration*FOOT_PACK)/100.0);
}

//A texel's depth 0..1 faded by its age, 0 when it holds nothing for this surface (another height or wrap); sand set when it is sand
float footDepthAt(ivec2 t, int surfaceY, inout float sand) {
    uint w = texelFetch(footprintMap, t & FOOT_MASK, 0).r;
    if (w == 0u) {
        return 0.0;
    }
    int tag = ((t.x >> FOOT_MAP_SHIFT) & 3) | (((t.y >> FOOT_MAP_SHIFT) & 3) << 2);
    if (int((w >> 20u) & 15u) != (surfaceY & 15) || int((w >> 24u) & 15u) != tag) {
        return 0.0;
    }
    bool isSand = (w & 0x80000000u) != 0u;
    float age = float((int(footParams.y) - int(w & 4095u)) & (FOOT_TIME_WRAP - 1))*FOOT_TIME_STEP;
    float a = age/((isSand ? FOOT_SAND_FADE : FOOT_SNOW_FADE)*footTuning().x);
    float fade = a >= 1.0 ? 0.0 : (a <= FOOT_HOLD ? 1.0 : 1.0 - smoothstep(0.0, 1.0, (a - FOOT_HOLD)/(1.0 - FOOT_HOLD)));
    float d = float((w >> 12u) & 255u)/255.0*fade;
    sand = max(sand, isSand ? d : 0.0);
    return d;
}

//The footprints' factor on a top face's lit colour at rel (blocks from the camera, world axes) with the direct light's share sunPart (0..1)
// coming from lightDir (unit, toward the light); 1 (and nothing read) where there are none
vec3 footprintShade(vec3 rel, float dist, float sunPart, vec3 lightDir) {
    if (!(footParams.x > 0.0) || dist > FOOT_END) {
        return vec3(1.0);
    }
    //The pixel in world texels (camera texel + its offset within it + rel), less half a texel for the bilinear taps
    vec2 q = footCamera.zw + rel.xz*float(FOOT_PER_BLOCK) - 0.5;
    vec2 base = floor(q);
    vec2 f = q - base;
    ivec2 t = ivec2(footCamera.xy) + ivec2(base);
    int surfaceY = int(floor(footParams.z + rel.y - 0.01));
    float sand = 0.0;
    float h00 = footDepthAt(t, surfaceY, sand);
    float h10 = footDepthAt(t + ivec2(1, 0), surfaceY, sand);
    float h01 = footDepthAt(t + ivec2(0, 1), surfaceY, sand);
    float h11 = footDepthAt(t + ivec2(1, 1), surfaceY, sand);
    if (h00 + h10 + h01 + h11 <= 0.0) {
        return vec3(1.0);
    }
    float h = mix(mix(h00, h10, f.x), mix(h01, h11, f.x), f.y);
    //The depth's slope per block (the bilinear's derivatives)
    vec2 g = vec2(mix(h10 - h00, h11 - h01, f.y), mix(h01 - h00, h11 - h10, f.x))*float(FOOT_PER_BLOCK);
    float strength = footParams.x*(1.0 - smoothstep(FOOT_END - FOOT_FADE_END, FOOT_END, dist));
    bool isSand = sand > 0.5*h;
    //The Experimental depth: steeper walls, and a darker floor (by its square root, at least half: depth 0 still leaves a flat mark)
    float deep = footTuning().y;
    float depth = (isSand ? FOOT_SAND_DEPTH : FOOT_DEPTH)*strength*deep;
    //The hollow's surface y = -depth h: its normal (depth g.x, 1, depth g.y); the direct light it gets against the flat ground's
    vec3 n = normalize(vec3(depth*g.x, 1.0, depth*g.y));
    float level = max(lightDir.y, 0.05);
    float lit = clamp(max(dot(n, lightDir), 0.0)/level, FOOT_LIT_MIN, FOOT_LIT_MAX);
    float shade = mix(1.0, lit, clamp(sunPart, 0.0, 1.0))*(1.0 - (isSand ? FOOT_SAND_DARK : FOOT_DARK)*h*min(strength*sqrt(max(deep, 0.25)), 1.5));
    vec3 tint = isSand ? vec3(1.0) : vec3(1.0 - FOOT_SNOW_BLUE*h, 1.0 - 0.5*FOOT_SNOW_BLUE*h, 1.0);
    return shade*tint;
}
