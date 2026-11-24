//2026-11-24 (the user's six Anime Nether references: "작은 불꽃은 어두운 빨간색으로", "큰 불꽃의 몸통은 노란색이면서 테두리는 하얀색, 더 겉은
// 옅은 파란색으로", "용암 느낌도 그라데이션으로 살리는 게 좋아보임 ... 애니메이션 프리셋의 네더에서만"): the Nether's fire and lava under the
// Anime palette (scene_copy.comp endLightParams.w, DimensionUniforms.netherLook; Default and Horror keep them as they were). The small
// flames' dark red is the particles' (ParticleLook.NETHER_ANIME). Imported after lighting.glsl (the marks) and postmath.glsl; reads the
// composite's mainColour for the neighbours' marks.
//
// FIRE (FoliageMarker.FIRE_ALPHA: fire, soul fire, a lit campfire's flames): cel-shaded. Its bright texels take a flat yellow-orange
// body (FIRE_BODY, the brighter ones FIRE_CORE), a white rim where the flame meets anything else within FIRE_RIM_PIXELS (at 1440 rows;
// scaled with the height), and the pixels just outside the flame (within FIRE_FRINGE_PIXELS) a pale blue fringe. Each keeps the glow's
// brightness the composite gave it (its luminance), so the bloom spreads it as before.
//
// LAVA (EMISSIVE_FLUID_ALPHA): a gradient by how far the lava runs on around the pixel on screen: the share of lava among 8 taps
// LAVA_CORE_BLOCKS away (projected) blends a deep orange body (LAVA_BODY) into a bright yellow core (LAVA_CORE), and where a tap
// LAVA_EDGE_BLOCKS away is no lava (the bank) a thin pale cyan edge (LAVA_EDGE). The vanilla texture's light varies it by TEXTURE.

const float NETHER_FIRE_CORE_R = 255.0;
const float NETHER_FIRE_CORE_G = 222.0;
const float NETHER_FIRE_CORE_B = 92.0;
const float NETHER_FIRE_BODY_R = 255.0;
const float NETHER_FIRE_BODY_G = 158.0;
const float NETHER_FIRE_BODY_B = 36.0;
//The texel brightness (largest channel of vanilla's colour) where the body turns to the core, and the band's softness
const float NETHER_FIRE_CORE_AT = 0.82;
const float NETHER_FIRE_SOFT = 0.04;
//The rim's and the fringe's widths (pixels at 1440 rows) and lights (times the glow's luminance there)
const float NETHER_FIRE_RIM_PIXELS = 2.0;
const float NETHER_FIRE_RIM_LIGHT = 1.35;
const int NETHER_FIRE_RIM_OUTLINE = 4;
const float NETHER_FIRE_FRINGE_PIXELS = 3.0;
const float NETHER_FIRE_FRINGE_R = 150.0;
const float NETHER_FIRE_FRINGE_G = 220.0;
const float NETHER_FIRE_FRINGE_B = 255.0;
const float NETHER_FIRE_FRINGE_LIGHT = 2.2;
//A neighbour counts as fire only where vanilla's colour is that bright (a campfire's logs carry the mark too)
const float NETHER_FIRE_BRIGHT = 0.55;

const float NETHER_LAVA_CORE_R = 255.0;
const float NETHER_LAVA_CORE_G = 212.0;
const float NETHER_LAVA_CORE_B = 80.0;
const float NETHER_LAVA_BODY_R = 250.0;
const float NETHER_LAVA_BODY_G = 120.0;
const float NETHER_LAVA_BODY_B = 24.0;
const float NETHER_LAVA_EDGE_R = 120.0;
const float NETHER_LAVA_EDGE_G = 232.0;
const float NETHER_LAVA_EDGE_B = 240.0;
const float NETHER_LAVA_CORE_BLOCKS = 2.2;
const float NETHER_LAVA_EDGE_BLOCKS = 0.5;
const float NETHER_LAVA_EDGE_LIGHT = 1.1;
const float NETHER_LAVA_TEXTURE = 0.3;
//The faces that take the gradient: from a normal's y of POOL_FROM (none) to POOL_TO (all): a pool's top, not a fall's sides
const float NETHER_LAVA_POOL_FROM = 0.6;
const float NETHER_LAVA_POOL_TO = 0.9;
//2026-11-24 (the user, after an outline whose width wavered: "파란 테두리를 테두리로 구현하는 방식 말고 나타났다 사라지는 방식으로"): the blue
// light is no outline that is always there. It shows in patches that come and go: where a noise drifting with time (GLINT_SPEED per
// second) rises over GLINT_FROM .. GLINT_TO. The lava's patches lie along its banks in the world (LAVA_GLINT_SCALE per block) and glow
// softly into the lava; the flames' flicker over the screen (FIRE_GLINT_SCALE per 1440-row pixel), faster
const float NETHER_GLINT_FROM = 0.46;
const float NETHER_GLINT_TO = 0.62;
const float NETHER_LAVA_GLINT_SCALE = 0.6;
const float NETHER_LAVA_GLINT_SPEED = 0.5;
const float NETHER_FIRE_GLINT_SCALE = 0.03;
const float NETHER_FIRE_GLINT_SPEED = 1.4;

//How much of the blue shows at q (xy where, z the time): 0 most of the time, rising to 1 in a passing patch
float netherGlint(vec3 q) {
    float n = 0.7*galaxyNoise(q) + 0.3*galaxyNoise(q*2.1 + vec3(17.0, 5.0, 9.0));
    return smoothstep(NETHER_GLINT_FROM, NETHER_GLINT_TO, n);
}

vec3 netherSrgb(float r, float g, float b) {
    return displayToLinear(vec3(r, g, b)/255.0);
}

int netherCodeAt(ivec2 q, ivec2 size) {
    return alphaCode(texelFetch(mainColour, clamp(q, ivec2(0), size - 1), 0).a);
}

bool netherFireAt(ivec2 q, ivec2 size) {
    vec4 c = texelFetch(mainColour, clamp(q, ivec2(0), size - 1), 0);
    return alphaCode(c.a) == FIRE_ALPHA_CODE && max(c.r, max(c.g, c.b)) > NETHER_FIRE_BRIGHT;
}

//colour scaled to the luminance of lit (the glow the composite gave the pixel)
vec3 netherKeepLight(vec3 colour, vec3 lit) {
    return colour*(luminance(lit)/max(luminance(colour), 1e-4));
}

//A fire pixel's cel look: lit the composite's glow there, vanilla its colour (display)
vec3 netherFireLook(ivec2 p, ivec2 size, vec3 lit, vec3 vanilla) {
    float m = max(vanilla.r, max(vanilla.g, vanilla.b));
    vec3 body = mix(netherSrgb(NETHER_FIRE_BODY_R, NETHER_FIRE_BODY_G, NETHER_FIRE_BODY_B),
            netherSrgb(NETHER_FIRE_CORE_R, NETHER_FIRE_CORE_G, NETHER_FIRE_CORE_B),
            smoothstep(NETHER_FIRE_CORE_AT - NETHER_FIRE_SOFT, NETHER_FIRE_CORE_AT + NETHER_FIRE_SOFT, m));
    //The rim only on the flame's outline: no fire r pixels away and still none RIM_OUTLINE times as far that way (not the texture's
    // small gaps inside the flame)
    int r = max(1, int(round(NETHER_FIRE_RIM_PIXELS*float(size.y)/1440.0)));
    bool rim = false;
    ivec2 dirs[4] = ivec2[4](ivec2(1, 0), ivec2(-1, 0), ivec2(0, 1), ivec2(0, -1));
    for (int k = 0; k < 4 && !rim; k++) {
        rim = !netherFireAt(p + dirs[k]*r, size) && !netherFireAt(p + dirs[k]*(r*NETHER_FIRE_RIM_OUTLINE), size);
    }
    vec3 shown = netherKeepLight(body, lit);
    return rim ? vec3(luminance(lit)*NETHER_FIRE_RIM_LIGHT) : shown;
}

//The pale blue fringe just outside a flame, added to a pixel that is no fire (0 away from any)
vec3 netherFireFringe(ivec2 p, ivec2 size, float t) {
    float px = float(size.y)/1440.0;
    float glint = netherGlint(vec3(vec2(p)/px*NETHER_FIRE_GLINT_SCALE, t*NETHER_FIRE_GLINT_SPEED));
    if (!(glint > 0.0)) {
        return vec3(0.0);
    }
    int r = max(1, int(round(NETHER_FIRE_FRINGE_PIXELS*px)));
    float hits = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k)*0.78539816;
        if (netherFireAt(p + ivec2(round(vec2(cos(a), sin(a))*float(r))), size)) {
            hits += 1.0;
        }
    }
    return netherSrgb(NETHER_FIRE_FRINGE_R, NETHER_FIRE_FRINGE_G, NETHER_FIRE_FRINGE_B)*NETHER_FIRE_FRINGE_LIGHT*min(hits/3.0, 1.0)*glint;
}

//A lava pixel's gradient: lit the composite's glow, vanilla its colour (display), dist its distance (blocks), pixelAngle a pixel's angle
//pos the pixel's world position (absolute blocks, where its blue patches lie), t the time (seconds), up how much its face points up
// (the normal's y). 2026-11-24 (the user: "내려오는 용암과 바닥용암이 겹치는 부분에서 색감이 이상하게 보이고 있어"): only the pools' tops take
// the gradient; a lava fall's faces (their shade made the core's yellow a dull olive, and their sides took the cyan) keep vanilla's
vec3 netherLavaLook(ivec2 p, ivec2 size, vec3 lit, vec3 vanilla, float dist, float pixelAngle, vec3 pos, float t, float up) {
    float pool = smoothstep(NETHER_LAVA_POOL_FROM, NETHER_LAVA_POOL_TO, up);
    if (!(pool > 0.0)) {
        return lit;
    }
    float perBlock = 1.0/max(dist*pixelAngle, 1e-4);
    float rCore = clamp(NETHER_LAVA_CORE_BLOCKS*perBlock, 3.0, 80.0);
    float rEdge = clamp(NETHER_LAVA_EDGE_BLOCKS*perBlock, 1.5, 12.0);
    float core = 0.0, edge = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k)*0.78539816 + 0.3926991;
        vec2 d = vec2(cos(a), sin(a));
        core += netherCodeAt(p + ivec2(round(d*rCore)), size) == EMISSIVE_FLUID_ALPHA_CODE ? 1.0 : 0.0;
        edge += netherCodeAt(p + ivec2(round(d*rEdge)), size) == EMISSIVE_FLUID_ALPHA_CODE ? 1.0 : 0.0;
    }
    core /= 8.0;
    edge /= 8.0;
    float m = max(vanilla.r, max(vanilla.g, vanilla.b));
    vec3 c = mix(netherSrgb(NETHER_LAVA_BODY_R, NETHER_LAVA_BODY_G, NETHER_LAVA_BODY_B),
            netherSrgb(NETHER_LAVA_CORE_R, NETHER_LAVA_CORE_G, NETHER_LAVA_CORE_B), smoothstep(0.45, 1.0, core));
    vec3 shown = netherKeepLight(c, lit)*(1.0 - NETHER_LAVA_TEXTURE + NETHER_LAVA_TEXTURE*2.0*m*m);
    float bank = (1.0 - smoothstep(0.55, 0.95, edge))*netherGlint(vec3(pos.xz*NETHER_LAVA_GLINT_SCALE + pos.y*0.2, t*NETHER_LAVA_GLINT_SPEED));
    vec3 cyan = netherKeepLight(netherSrgb(NETHER_LAVA_EDGE_R, NETHER_LAVA_EDGE_G, NETHER_LAVA_EDGE_B), lit)*NETHER_LAVA_EDGE_LIGHT;
    return mix(lit, mix(shown, cyan, bank), pool);
}
